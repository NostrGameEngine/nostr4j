---
title: Security
description: Security boundaries for keys, relay data, NWC connections, Blossom and RTC rooms.
---

# Security

Nostr4J verifies protocol data, but application security still depends on where keys live, which endpoints you trust and how untrusted event content reaches your UI. These are the boundaries to keep explicit.

## Private keys

- Prefer `NostrSigner` over passing private keys through application code.
- In a browser, use `NostrNIP07Signer` so the extension owns the key.
- For a remote signer, request only the NIP-46 permissions the application needs.
- If a local key must be persisted, store a NIP-49 `ncryptsec`, never a raw `nsec`.
- Close `NostrSigner`, `NostrKeyPair` and `NostrPrivateKey` instances when their owner stops. Closing destroys key material held by the local implementations; it cannot erase copies already made as Java `String` values.
- Never log NWC connection URIs, bunker secrets, raw private keys or passphrases.

## Relays and event content

Relay traffic is untrusted input. Signature verification proves who signed an event; it does not make its content safe HTML, a safe URL or a true statement.

- Keep relay event verification enabled.
- Use `subscription.setVerifyMatchLocally(true)` when you do not want to trust a relay to enforce the filter.
- Render event content with text APIs (`textContent` in a browser), not HTML insertion.
- Validate and constrain URLs before loading avatars, media or links. A remote image can track a reader even if its event is correctly signed.
- Bound event counts, timeouts and local caches. Use `ForwardSlidingWindowEventTracker` for long-lived feeds instead of an unbounded tracker.

## Network destinations

`nge-platform` blocks `localhost` and loopback destinations by default. Treat that as a production safety control, not an inconvenience. If a development environment needs local relays, enable the override only in that process and never accept arbitrary user-supplied URLs while it is active.

Use `wss://` relays and `https://` Blossom/LNURL endpoints in production. TLS terminates outside the reference TURN process, so expose it through a reverse proxy as `wss://…/turn`.

## Wallets and zaps

An NWC URI is a bearer capability. Give applications a narrowly scoped, low-balance connection and revoke it when it is no longer needed. Check `isMethodSupported(...)` before invoking an operation and treat all amounts as millisatoshis.

For zaps, validate receipts with `Nip57.parseAndValidateZapReceipt(...)` against the invoice and request you actually created. A kind-9735 event should not be counted as value merely because it parses.

## RTC rooms and TURN

Possession of the room private key grants room membership. A human-readable room seed is therefore suitable only for a public demo, never for a private room. Generate a high-entropy room key for real applications and distribute it over an authenticated channel.

The reference TURN server verifies event signatures, proof of work, a challenge-bound room proof and socket ownership. It also limits frame size, queued bytes, virtual sockets and unauthenticated session time. Keep it behind TLS, retain those limits, run the container as a non-root user and set a stable encrypted signer key for a production identity.

## Demo credentials

Use temporary identities and limited wallet capabilities when trying the live demos. Sats Seas points are not money, and its game state is not a payment record. No browser demo needs a real `nsec`.
