from hashlib import sha256
from pathlib import Path
from shutil import move, rmtree
from urllib.request import urlopen
from zipfile import ZipFile

extensions = {
    "lnurlp": (
        "https://github.com/lnbits/lnurlp/archive/refs/tags/v1.3.2.zip",
        "391efc171716860486ee7a594bc00f82046a91df8b456c9b4feb697173c44b58",
    ),
    "nwcprovider": (
        "https://github.com/lnbits/nwcprovider/archive/refs/tags/v1.1.2.zip",
        "82282235dfd683cd0fdc2a25d3ac4c1f80c1fd5effc7696ee16b3408d2b3b219",
    ),
}

destination = Path("/app/lnbits/extensions")
destination.mkdir(parents=True, exist_ok=True)
for extension_id, (url, expected_hash) in extensions.items():
    archive = Path("/tmp") / f"{extension_id}.zip"
    archive.write_bytes(urlopen(url).read())
    actual_hash = sha256(archive.read_bytes()).hexdigest()
    if actual_hash != expected_hash:
        raise RuntimeError(f"Invalid SHA-256 for {extension_id}: {actual_hash}")
    unpacked = Path("/tmp") / f"unpacked-{extension_id}"
    with ZipFile(archive) as zip_file:
        zip_file.extractall(unpacked)
    source = next(path for path in unpacked.iterdir() if path.is_dir())
    target = destination / extension_id
    if target.exists():
        rmtree(target)
    move(str(source), target)
    if extension_id == "nwcprovider":
        migrations = target / "migrations.py"
        source_text = migrations.read_text()
        replacements = {
            "from coincurve import PrivateKey": "import os\n\nfrom coincurve import PrivateKey",
            "VALUES ('relay', 'nostrclient')": "VALUES ('relay', 'ws://relay1:8080')",
            "private_key = PrivateKey()": (
                'private_key = PrivateKey.from_hex(os.environ["NOSTR4J_NWC_PROVIDER_SECRET"])'
            ),
            '{"value": ""}': '{"value": os.environ["NOSTR4J_NWC_RELAY_ALIAS"]}',
        }
        for old, new in replacements.items():
            if old not in source_text:
                raise RuntimeError(f"Expected NWC Provider source fragment not found: {old}")
            source_text = source_text.replace(old, new, 1)
        migrations.write_text(source_text)
    archive.unlink()
    rmtree(unpacked)
