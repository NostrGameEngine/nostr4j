---
title: NIPs
---

# NIPs

Nostr4J implements NIPs as small helper classes (`Nip01`, `Nip05`, …) on top of the core primitives. One page per NIP, each with the code you actually need.

| NIP | What it is
|-----|------------|
| [01](nip-01.md) | Profile metadata (kind 0): read and update name, picture, about
| [04](nip-04.md) | Legacy encrypted messages
| [05](nip-05.md) | Internet identifiers (`alice@example.com`) → public key
| [07](nip-07.md) | Browser extension signing
| [09](nip-09.md) | Deletion requests (kind 5)
| [24](nip-24.md) | Extra profile metadata fields
| [39](nip-39.md) | External identities (GitHub, Twitter, …) linked to your profile
| [40](nip-40.md) | Expiring events
| [44](nip-44.md) | Versioned encryption for modern private messages
| [46](nip-46.md) | Remote signing via bunker
| [47](nip-47.md) | Wallet Connect: talk to a Lightning wallet
| [49](nip-49.md) | Encrypted private keys (`ncryptsec`)
| [50](nip-50.md) | Search queries
| [57](nip-57.md) | Zaps: Lightning tips on events and profiles

Beyond the NIPs: [Blossom](blossom.md) (content-addressed media storage with Nostr auth) and [RTC rooms](rtc-rooms.md) (realtime data channels, draft NIP-DC).

## Conventions

The `NipXX` helpers follow the same shape: static `fetch(pool, pubkey)` to read, static `update(pool, signer, metadata)` to write. Everything async returns `AsyncTask`, chained with `.then()` or waited with `.get()`.
