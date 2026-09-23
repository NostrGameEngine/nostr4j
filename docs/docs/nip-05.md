---
title: NIP-05 Identifiers
---

# NIP-05 - Internet identifiers

A NIP-05 identifier looks like an email address (`alice@example.com`) and maps to a Nostr public key. The domain serves a small JSON file at `/.well-known/nostr.json`; `Nip05` reads it for you.

```java
// split "alice@example.com" into [name, domain]
String[] parts = Nip05.parseIdentifier("alice@example.com");

Nip05Identity id = Nip05.fetch("alice@example.com", Duration.ofSeconds(10)).get();

NostrPublicKey pubkey = id.getPublicKey();
id.getName();        // "alice"
id.getDomain();      // "example.com"
id.getIdentifier();  // "alice@example.com"
```

There is an overload of `fetch` with a `useHttps` flag if you ever need plain HTTP (don't, outside tests).

The identity object also carries the raw JSON (`id.data`) and, when the domain advertises it, NIP-46 bunker information via `id.getNip46Data()` - that's how some apps turn an identifier into a remote-signer connection.

## Where next

- [NIPs index](nips.md)
- [Keys & signers](signers.md) - NIP-46 remote signing
