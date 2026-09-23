# Sats Seas — wire protocol (`ss1`)

All game traffic runs over a public dc4 `NostrRTCRoom` (Nostr4J onion-routed
WebRTC mesh). Two application channels exist on every logical socket:

| RTC channel | Use |
|---|---|
| `sats-pos` | Position broadcasts (tree broadcast) |
| `sats-game` | Everything else: unicast (onion-routed) and broadcasts |

Framing: every datagram is one `WireCodec` frame —
`[u32 big-endian length][UTF-8 JSON]`. Nostr4J delivers DataChannel messages
whole, so one frame == one message.

Every game message is a JSON object with `"v":"ss1"` and a `"type"`.
`from` fields are identity pubkeys (hex). Treasure rewards are game points;
this protocol does not move real satoshis.

## Presence

### `hello` — broadcast on `sats-game`
```json
{"v":"ss1","type":"hello","name":"Anne","pub":"<hex>",
 "island":{"x":1234.5,"y":2345.6},
 "hp":100}
```
Announce (or re-announce) a ship. Sent on join and every 30 s (soft state).

### `despawn` — broadcast on `sats-game`
```json
{"v":"ss1","type":"despawn","pub":"<hex>"}
```
Sent on leave/unload. There is **no offline raiding**: a despawned ship's
treasure is unclaimable until the owner is back online (claims are verified
by the owner's peer, which is gone).

## Positions (`sats-pos`, tree broadcast)

### `pos` — broadcast on `sats-pos`
```json
{"v":"ss1","type":"pos","pub":"<hex>","x":1.5,"y":2.5,"a":0.7,
 "hp":82}
```
All participants broadcast their current position and heading at a regular
cadence, regardless of distance. The browser interpolates received positions
and headings for smooth rendering. Onion routing still limits direct links.

## Combat

### `hit` — unicast on `sats-game`
```json
{"v":"ss1","type":"hit","target":"<hex>","dmg":14,"by":"<hex>"}
```
The shooter's client does the hit test (cannonball vs rendered ship position)
and sends damage to the target. Simulated captains apply bounded damage and
reply with `hit_ack` containing their remaining HP. This is a demonstration,
not an authoritative anti-cheat system; real clients can still forge hits.

### `sink` — broadcast on `sats-game`
```json
{"v":"ss1","type":"sink","pub":"<hex>","x":1.5,"y":2.5}
```
The sunk player drops their own map plus every carried map as floating
scroll pickups at `(x,y)`. Simulated captains also drop a map and remain sunk
for eight seconds before respawning:

### `scroll` — broadcast on `sats-game`
```json
{"v":"ss1","type":"scroll","id":"<mapId>","x":1.5,"y":2.5,
 "victim":"<hex>","victimName":"Anne","ix":1234.5,"iy":2345.6}
```
A floating treasure-map pickup. `ix,iy` is the victim's island. Picking it
up renders the winding dotted trail (see `MapTrail`).

## Digging & treasure (owner-authoritative)

The burial tile `(bx,by)` is known **only** to the island owner's client.
In the current demo, sats are game points; no wallet is connected and no payment is made.

### `taken` — broadcast on `sats-game`
```json
{"v":"ss1","type":"taken","id":"<mapId>","by":"<hex>"}
```
Someone picked up a floating scroll: everyone removes it locally so it
can't be double-picked. (Best-effort; a race between two grabbers is
possible and harmless for a demo.)

### `shot` — broadcast on `sats-game`
```json
{"v":"ss1","type":"shot","from":"<hex>","x":1234,"y":567,"a":1.234}
```
Someone fired a cannon. Purely visual for receivers: each ball is rendered
locally, but only the shooter is authoritative for hits (it unicasts `hit`
on collision). Keeps cannonball physics cheat-free without per-frame sync.

### `dig` — unicast to the island owner
```json
{"v":"ss1","type":"dig","x":1.5,"y":2.5,"nonce":"<rand>"}
```
Metal-detector probe. The owner replies with `ping`; rate-limited to
1 probe / 2 s per digger (enforced by the owner's client).

### `ping` — unicast back to the digger
```json
{"v":"ss1","type":"ping","heat":0.83,"nonce":"<same>"}
```
`heat` in [0,1]: 1.0 = standing on the tile, 0.0 = at/beyond the detector
range (600 px). The exact tile is never revealed.

### `claim` — unicast to the island owner
```json
{"v":"ss1","type":"claim","islandId":"<hex>","x":1.5,"y":2.5,
 "nonce":"<rand>"}
```
Sent when the digger believes they are on the tile. The owner's client
checks `dist((x,y),(bx,by)) < 30` px.

### `digResult` — unicast back to the digger
```json
{"v":"ss1","type":"digResult","ok":true,"sats":5,"nonce":"<same>"}
```
Phase 1: `sats` are **points** credited to the digger's score.
Real-satoshi settlement would require a separate payment protocol and an
explicitly authorized wallet connection. The existing `digResult` is not a
payment receipt.

### `claimed` — broadcast on `sats-game`
```json
{"v":"ss1","type":"claimed","islandId":"<hex>","by":"<hex>","sats":5}
```
The (previous) owner announces the treasure is gone, so every client stops
rendering trails/pings for that island.

## Map trading (private, onion-routed)

### `mapOffer` — unicast
```json
{"v":"ss1","type":"mapOffer","to":"<hex>",
 "map":{"id":"<mapId>","victim":"<hex>","victimName":"Anne",
        "ix":1234.5,"iy":2345.6},
 "price":0}
```
`price` is always 0: maps are gifted/swapped. No wallet is involved.

### `mapAccept` — unicast
```json
{"v":"ss1","type":"mapAccept","mapId":"<mapId>","ok":true}
```
On `ok:true` the sender deletes the map locally and the receiver adds it.
Both messages use onion-routed unicast.

## Chat

### `chat` — broadcast on `sats-game`
```json
{"v":"ss1","type":"chat","from":"<hex>","name":"Anne","text":"ahoy"}
```
Plain broadcast chat. Rate-limited client-side (1 / 2 s).

## Net-layer events (Java → page)

The `SatsSeasNet` JS API pushes these JSON strings to the page callback:

| Event | Meaning |
|---|---|
| `{"t":"peer","id":"<hex>","state":"online"\|"offline"\|"disconnected"}` | peer discovery |
| `{"t":"msg","from":"<hex>","channel":"sats-pos"\|"sats-game","body":"<json>"}` | game message |
| `{"t":"topo","self":"<nodeHex>","nodes":[{"id","short"}],"edges":[{"a","b","t":"rtc"\|"turn"\|"unknown"}]}` | dc4 mesh snapshot |
| `{"t":"ban","peer":"<hex>","reason":"..."}` | local ban applied |
| `{"t":"log","msg":"..."}` | diagnostics |
| `{"t":"ready","id":"<hex>","session":"..."}` | room joined, local identity |
