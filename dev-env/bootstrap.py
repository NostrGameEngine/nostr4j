#!/usr/bin/env python3

import argparse
import base64
import json
import ssl
import time
import urllib.error
import urllib.request
from pathlib import Path

USERNAME = "nostr4j"
PASSWORD = "nostr4j-local"
FIRST_INSTALL_TOKEN = "nostr4j-dev-install"
PROVIDER_SECRET = "44" * 32
CLIENTS = (
    ("wallet1", "11" * 32, "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"),
    ("wallet2", "22" * 32, "466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27"),
    ("noBudget", "33" * 32, "3c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1"),
)
ALL_PERMISSIONS = ["pay", "invoice", "lookup", "history", "balance", "info"]


class Api:
    def __init__(self, base_url: str, ca_file: Path):
        self.base_url = base_url.rstrip("/")
        self.ssl_context = ssl.create_default_context(cafile=str(ca_file))

    def request(self, method, path, data=None, headers=None, expected=(200,)):
        body = None if data is None else json.dumps(data).encode()
        request = urllib.request.Request(
            self.base_url + path,
            data=body,
            method=method,
            headers={"Content-Type": "application/json", **(headers or {})},
        )
        try:
            with urllib.request.urlopen(request, context=self.ssl_context, timeout=15) as response:
                payload = response.read()
                if response.status not in expected:
                    raise RuntimeError(f"{method} {path}: HTTP {response.status}")
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")
            raise RuntimeError(f"{method} {path}: HTTP {error.code}: {detail}") from error


def jwt_user_id(access_token: str) -> str:
    payload = access_token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["usr"]


def authenticate(api: Api) -> tuple[str, str]:
    install_data = {
        "username": USERNAME,
        "password": PASSWORD,
        "password_repeat": PASSWORD,
        "first_install_token": FIRST_INSTALL_TOKEN,
    }
    try:
        result = api.request("PUT", "/api/v1/auth/first_install", install_data)
    except RuntimeError as error:
        if "HTTP 403" not in str(error):
            raise
        result = api.request("POST", "/api/v1/auth", {"username": USERNAME, "password": PASSWORD})
    token = result["access_token"]
    return token, jwt_user_id(token)


def ensure_wallets(api: Api, token: str, user_id: str):
    auth = {"Authorization": f"Bearer {token}"}
    wallets = api.request("GET", f"/users/api/v1/user/{user_id}/wallet", headers=auth)
    if not wallets:
        wallets.append(
            api.request("POST", f"/users/api/v1/user/{user_id}/wallet", {"name": "Sandbox"}, auth)
        )
    first = wallets[0]
    second = next((wallet for wallet in wallets if wallet["name"] == "Zap Receiver"), None)
    if second is None:
        second = api.request(
            "POST", f"/users/api/v1/user/{user_id}/wallet", {"name": "Zap Receiver"}, auth, (200, 201)
        )
    for wallet in (first, second):
        api.request("PUT", "/users/api/v1/balance", {"id": wallet["id"], "amount": 1_000_000}, auth)
    return first, second


def enable_extensions(api: Api, token: str):
    auth = {"Authorization": f"Bearer {token}"}
    for extension in ("lnurlp", "nwcprovider"):
        api.request("PUT", f"/api/v1/extension/{extension}/enable", headers=auth)


def configure_extensions(api: Api, token: str, receiver_wallet: dict):
    auth = {"Authorization": f"Bearer {token}"}
    api.request(
        "POST",
        "/nwcprovider/api/v1/config",
        {
            "provider_key": PROVIDER_SECRET,
            "relay": "ws://relay1:8080",
            "relay_alias": api.base_url.replace("https://lnbits", "wss://relay1"),
            "handle_missed_events": "0",
        },
        auth,
    )
    api.request(
        "PUT",
        "/lnurlp/api/v1/settings",
        {"nostr_private_key": "55" * 32},
        auth,
    )

    invoice_headers = {"X-Api-Key": receiver_wallet["adminkey"]}
    links = api.request("GET", "/lnurlp/api/v1/links", headers=invoice_headers)
    if not any(link.get("username") == "unit" for link in links):
        api.request(
            "POST",
            "/lnurlp/api/v1/links",
            {
                "description": "nostr4j local test address",
                "min": 1,
                "max": 100000,
                "username": "unit",
                "zaps": True,
                "disposable": False,
            },
            invoice_headers,
            (201,),
        )


def ensure_nwc(api: Api, wallet: dict, client, zero_budget=False) -> str:
    name, secret, pubkey = client
    headers = {"X-Api-Key": wallet["adminkey"]}
    existing = api.request("GET", "/nwcprovider/api/v1/nwc", headers=headers)
    if not any(item["data"]["pubkey"] == pubkey for item in existing):
        budget = {
            "pubkey": None,
            "budget_msats": 0 if zero_budget else 5_000_000_000,
            "refresh_window": 31_536_000,
            "created_at": int(time.time()),
        }
        api.request(
            "PUT",
            f"/nwcprovider/api/v1/nwc/{pubkey}",
            {
                "permissions": ALL_PERMISSIONS,
                "description": f"nostr4j {name}",
                "expires_at": 0,
                "budgets": [budget],
            },
            headers,
            (201,),
        )
    return api.request("GET", f"/nwcprovider/api/v1/pairing/{secret}")


def write_properties(output: Path, port: int, pairings: list[str]):
    output.parent.mkdir(parents=True, exist_ok=True)
    relay_urls = [f"wss://relay{number}.localhost:{port}" for number in range(1, 4)]
    values = {
        "nostr4j.test.relayUrl": relay_urls[0],
        "nostr4j.test.relayUrls": ",".join(relay_urls),
        "nostr4j.test.nwcWallet1": pairings[0],
        "nostr4j.test.nwcWallet2": pairings[1],
        "nostr4j.test.nwcNoBudget": pairings[2],
        "nostr4j.test.lnAddress": f"unit@lnbits.localhost:{port}",
    }
    output.write_text("".join(f"{key}={value}\n" for key, value in values.items()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--ca", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    api = Api(f"https://lnbits.localhost:{args.port}", args.ca)
    token, user_id = authenticate(api)
    enable_extensions(api, token)
    wallet1, wallet2 = ensure_wallets(api, token, user_id)
    configure_extensions(api, token, wallet2)
    pairings = [
        ensure_nwc(api, wallet1, CLIENTS[0]),
        ensure_nwc(api, wallet2, CLIENTS[1]),
        ensure_nwc(api, wallet2, CLIENTS[2], zero_budget=True),
    ]
    write_properties(args.output, args.port, pairings)


if __name__ == "__main__":
    main()
