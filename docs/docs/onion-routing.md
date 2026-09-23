---
title: Onion routing
---

# Onion routing

A room can hold more peers than anyone connects to directly (`RTCSettings` caps direct peers at 16 by default). Peers beyond that - or peers you simply can't reach directly - are still reachable: the room routes frames **multi-hop** across the mesh, each hop encrypted like an onion layer. This is the "dc4 room routing" transport from the NIP-DC draft, next to direct WebRTC and TURN.

## What the application does

Nothing special. You keep using `room.send(...)` and `room.broadcast(...)`:

```java
// no direct socket to farPeer? the room routes through the mesh
room.send(farPeer, ByteBuffer.wrap(payload));
```

When no direct path exists, the room's internal routing engine picks a path through peers you *can* reach and forwards the frame hop by hop. Routing is transparent: the same message listener fires on arrival, with the same `turn` flag semantics. The engine itself is wired internally by the room - it's not something you instantiate or drive directly.

## How it works, in plain words

Every peer periodically shares a **signed topology snapshot**: "here's who I can reach right now, revision N, valid until T". The room collects snapshots into a **graph** of the mesh, and a **route planner** picks the cheapest path from you to the destination. Cost is simple: direct links are cheapest, TURN links cost more, unknown links more still, and every extra hop adds cost - so short direct paths win whenever they exist. Broadcasts don't flood: they travel over a **tree** built from the graph, so each peer forwards each message once.

A **routing scope** (room key + protocol id + application id) keeps routing domains separate: snapshots from another app's room are never mixed into yours.

## The public pieces

The classes are public for inspection and advanced use; normal apps don't need them:

| Class | Role |
|-------|------|
| `NodeId` | a node's identity: routing scope + pubkey + session id |
| `TopologySnapshot` | one peer's signed neighbor list (`getNeighbors()`, `getRevision()`, `isExpired(now)`) |
| `TopologyGraph` | nodes + edges assembled from snapshots |
| `RoutePath` | an ordered list of edges from source to destination |
| `WeightedRoutePlanner` | `plan(graph, source, destination, now)` → cheapest paths first |
| `RouteCostModel` | the costs: direct vs TURN vs unknown, per-hop, failed-route penalty |
| `RouteTransportProfile` | constraints a route must satisfy (ordered, reliable, …) |
| `RoutingScope` | the domain a graph belongs to |

Two honest limits: the room exposes **no public accessor** for the live graph - to see the mesh from app code, enumerate `room.getSockets()` - and the routing engine stays internal.

The full wire spec is in [NIP-DC.md](https://github.com/NostrGameEngine/nostr4j/blob/master/NIP-DC.md) at the repo root.

## Where next

- [RTC rooms](rtc-rooms.md) - joining, channels, direct vs TURN
- [NIPs index](nips.md)
