---
title: Getting started
---

# Getting started

<a class="doc-run-button" href="../demos.html#publish">Run this example in the browser</a>

Let's publish a note and receive it back from a relay. You'll need a key to sign with, a relay connection and a subscription for the incoming event.

In this guide, you will learn how to set up the project, then publish a note and receive it back from a relay.

## Set up the project

Add `org.ngengine:nostr4j` and the matching platform adapter to your build. For the coordinates and requirements of each platform, follow the [platform setup](platforms.md).

## Publish and receive a note

Save the following as `QuickStart.java` and run it. Use a temporary identity: connect to a relay, subscribe to that identity's notes before publishing, then wait briefly so the subscription receives the note before the program exits.

```java
--8<-- "site-demos/src/backend/java/QuickStart.java:example"
```

After `publish(note).await()` returns, at least one relay has accepted the event. Seeing it in the subscription is a separate confirmation: a relay has sent the event back to you. In a running application, keep the pool open for as long as you need it.

For a persistent account, load an existing [key](keys.md) or use a browser or remote [signer](signers.md). You can then change the [filter](filters.md) to read other authors and choose how many relay acknowledgements to wait for when [publishing](relays.md).
