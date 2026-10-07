---
title: Documentation
---

# Nostr4J documentation

Nostr4J is a Java library for Nostr apps. It handles events, feeds and identities, and it also covers wallets, media storage and real-time peer-to-peer connections. It runs on desktop, mobile and in the browser, with the same API on every platform.

## Platforms

The library runs on Windows, Linux, macOS, Android and iOS, and in the browser with TeaVM.

| Target | Platform adapter |
|---|---|
| Windows, Linux and macOS | `nge-platform-jvm` |
| Android | `nge-platform-android` |
| iOS | `nge-platform-ios` |
| Browser, with JavaScript or WasmGC output | `nge-platform-teavm` |

See [platform setup](platforms.md) for more details.

## Start building

Follow the [quickstart example](getting-started.md) to sign a note, publish it and receive it through a subscription. You can also try the [live demos](../demos.md) before setting up a project.

From there, choose the part your app needs:

| I want to… | Start here |
|---|---|
| Let someone sign in with their Nostr identity | [Keys](keys.md) and [signers](signers.md) |
| Publish a note or another kind of event | [Creating events](events.md) and [publishing](relays.md) |
| Load a feed and keep it up to date | [Filters](filters.md), [fetch and subscribe](fetch-subscribe.md) |
| Show or edit a profile | [Profile metadata](nip-01.md) and [internet identifiers](nip-05.md) |
| Encrypt data for another user | [Message encryption](nip-44.md) |
| Accept payments or upload media | [Wallets](wallets.md), [zaps](nip-57.md) and [Blossom](blossom.md) |
| Exchange data between app users in real time | [Peer-to-peer real-time connections](p2p-overview.md) |
| Connect JVM code through input and output streams | [RTC streams](rtc-streams.md) |

For individual methods, consult the [Javadoc](../api/index.html). If you already know a NIP number, use the [protocol index](nips.md).

## Asynchronous calls

Most network operations return an `AsyncTask`. Chain calls with `.then()` or `.compose()` in application code; the standalone examples often use `.await()` to keep the sequence easy to follow. Close subscriptions, pools and signers when you no longer need them, along with any keys your app owns.
