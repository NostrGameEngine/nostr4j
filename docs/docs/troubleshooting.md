---
title: Troubleshooting
description: Diagnose common Nostr4j setup, relay, wallet, RTC and TeaVM failures.
---

# Troubleshooting

## “Failed to load default platform”

Check that the adapter for your target is on the classpath, then initialize it before creating Nostr4j objects. Having only the core library is not enough; see [platform setup](platforms.md).

## Local relay or wallet URL is rejected

Loopback destinations are blocked by default. For JVM development only, start with `-Dnge-platforms.allowLoopbackInURIs=true`. Avoid this flag in production, unless you really know what you're doing.

## A fetch returns fewer events than the limit

The limit only caps the number of results, so getting fewer is normal: the timeout may have expired, the filter may have matched little data, or several relays may have returned the same events. If you enabled stopping at EOSE, the fetch also returns when the relays finish their stored results. Check your [fetch policy](fetch-subscribe.md#fetch-policies) before increasing the timeout.

## NWC never becomes ready

Check that the URI parses, at least one advertised relay is reachable, the wallet service is online and the capability has not been revoked. Use `waitForReady()` before balance or payment methods and close the wallet after a failed attempt.

## RTC peer discovered, but no data arrives

Discovery means Nostr signaling worked; it does not prove the RTC data channel opened. Check whether both peers can exchange ICE candidates, whether the configured TURN service is reachable, and whether the signaling relays accept topology events. Restrictive NATs can prevent a direct connection. A successful TURN WebSocket upgrade alone does not prove its authenticated virtual-socket handshake completed.

A TURN WebSocket close code of `1011` indicates an internal error at the endpoint. Check the server logs, or try another compatible service.

## TeaVM compilation fails before analysis

Start by checking that the platform dependencies resolve to compatible versions. For browser builds, choose the task for your output target in [platform setup](platforms.md#browser-via-teavm). See the [TeaVM documentation](https://teavm.org/) for more.
