---
title: Tune RTC connections
---

# Tune RTC connections

Use `RTCSettings` to configure direct connections, retries and incoming sessions. Pass the settings when creating the peer and room, as shown in the [room example](rtc-rooms.md).

## Direct connections and retries

Set the number of direct connections and how failed connections are retried:

```java
RTCSettings settings = RTCSettings.getDefault("my-app", "chat-v1")
    .withMaxDirectPeers(4)
    .withMaxConcurrentConnectionAttempts(2)
    .withConnectionRetryInitialDelay(Duration.ofSeconds(1))
    .withConnectionRetryMaxDelay(Duration.ofSeconds(30))
    .withConnectionRetryMultiplier(2.0f)
    .withConnectionRetryJitter(0.2f)
    .withConnectionMinimumLifetime(Duration.ofSeconds(10));
```

This example allows four direct connections and two simultaneous connection attempts. Failed attempts wait longer between retries, up to 30 seconds. Jitter adds variation to the delay so peers avoid retrying together. The minimum lifetime helps keep working connections stable.

!!! note "Direct connection budget"
    The default is 16, with a minimum of two. Connections being opened or closed also occupy capacity. The room can reach more participants through [multi-hop routing](onion-routing.md).

## Prefer a known peer

If your app has a reliable host, give its public key a higher connection priority:

```java
room.setDiscoveryPriority(peer ->
    peer.getPubkey().equals(author) ? 10.0f : 1.0f);
```

Higher values are preferred when choosing direct neighbors. The room also considers available capacity and the connections needed to keep participants reachable.

!!! note "Excluding a direct connection"
    Zero keeps a peer eligible. A negative value excludes its direct connection, but the peer can still communicate through others.

!!! tip
    Keep the priority callback quick and avoid changing the room from inside it.

## Limit incoming session requests

A room can receive connection messages before a peer's signed announcement arrives. Session limits control how much information it keeps while waiting:

```java
int pendingSessions = 64;
int pendingSessionsPerIdentity = 2;
int announcedSessionsPerIdentity = 4;
int presenceRecordsPerIdentity = 16;
Duration announcementTimeout = Duration.ofSeconds(20);

RTCSessionLimits limits = new RTCSessionLimits(
    pendingSessions,
    pendingSessionsPerIdentity,
    announcedSessionsPerIdentity,
    presenceRecordsPerIdentity,
    announcementTimeout);
RTCSettings settings = RTCSettings.getDefault("my-app", "chat-v1")
    .withSessionLimits(limits);
```

An identity is a peer's public key; it can have several sessions. Presence records track its announcements.

| Setting | Default |
|---|---|
| Pending sessions across the room | 128 |
| Pending sessions per identity | 2 |
| Announced sessions per identity | 8 |
| Presence records per identity | 32 |
| Time to wait for an announcement | 30 seconds |

!!! note "Presence capacity"
    Keep the presence-record limit at least as large as the announced-session limit.

These settings limit memory used during discovery. Your app decides who may join the room.

