---
title: Troubleshooting
description: Diagnose common Nostr4J setup, relay, wallet, RTC and TeaVM failures.
---

# Troubleshooting

## “Failed to load default platform”

The core library is present but no runtime platform implementation is on the classpath. Add the artifact for your target and initialize it before constructing Nostr4J objects. See [Platforms & setup](platforms.md).

## Local relay or wallet URL is rejected

Loopback destinations are blocked by default. For a controlled JVM development process only, start with `-Dnge-platforms.allowLoopbackInURIs=true`. Do not enable that flag around untrusted URLs.

## A fetch returns fewer events than the limit

The limit is a maximum, not a promise. The timeout may expire first, relays may send EOSE, filters may match fewer events, or duplicate events may collapse through the tracker. Use `NostrAllEOSEPoolFetchPolicy` when relay completeness matters more than a fixed deadline.

## A subscription receives duplicates

Choose an event tracker when subscribing. `ForwardSlidingWindowEventTracker` is bounded by time and suits long-lived feeds; `NaiveEventTracker` remembers every ID and is better for finite fetches. `PassthroughEventTracker` deliberately allows duplicates.

## NWC never becomes ready

Check that the URI parses, at least one advertised relay is reachable, the wallet service is online and the capability has not been revoked. Do not print the URI while diagnosing it. Use `waitForReady()` before balance or payment methods and close the wallet after a failed attempt.

## RTC peer discovered, but no data arrives

Discovery means Nostr signaling worked; it does not prove the RTC data channel opened. Check whether both peers can exchange ICE candidates, whether the configured TURN service is reachable, and whether the signaling relays accept topology events. Restrictive NATs can prevent a direct connection. A successful TURN WebSocket upgrade alone does not prove its authenticated virtual-socket handshake completed.

If a TURN WebSocket closes with code `1011`, the endpoint reported an internal server error. Inspect or replace that TURN service; changing the room seed or JavaScript UI will not repair its server-side failure.

## TeaVM compilation fails before analysis

Use the concrete `GenerateJavaScriptTask`, not the abstract TeaVM task base. Custom tasks must include `teavmClasspath`, use ES2015 modules and ship the NGE `TeaVMBinds.bundle.js` resource next to the generated entry point. If Java compilation reports classes missing from `nge-platform`, check dependency resolution and version alignment.
