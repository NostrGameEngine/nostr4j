---
title: Authenticate to a relay
---

# Authenticate to a relay

Some relays ask you to identify yourself before reading their feeds. Add `Nip42RelayAuth` to a trusted relay before connecting it:

```java
String relayUrl = "wss://relay.example.com";
NostrRelay relay = new NostrRelay(relayUrl);
relay.addComponent(new Nip42RelayAuth(relayUrl, signer));
pool.addRelay(relay).await();
```

When the relay sends a challenge, the component asks your signer to sign a response and sends it back using NIP-42. The signer may prompt the user for approval.

!!! warning "Authentication reveals your identity"
    Authenticate only to relays your app trusts. Review URLs received through relay hints before sharing the same identity with them.
