# NIP-DC: Direct Connect between peers

`draft` `optional` `author:riccardobl`

NIP-DC defines a resilient way for peers to discover each other over Nostr, establish a connection, and exchange binary payloads.

It supports three transport forms:

1. **WebRTC**, for direct peer-to-peer connectivity when possible.
2. **WebSocket-based TURN with signed headers**, for relay-based transport when direct connectivity is unavailable or unstable.
3. **dc4 room routing**, which carries a logical peer connection over a bounded graph of direct WebRTC or TURN links.

Both transports use the same room, peer, and authorization model, so applications see one logical connection rather than two unrelated transports.

The logical connection is transport-resilient:

* implementations **SHOULD** prefer direct WebRTC when possible,
* **MAY** degrade to one or more TURN relays when direct connectivity fails,
* **MAY** later switch back to WebRTC when conditions improve,
* and, if no path is currently usable, **MAY** temporarily pause delivery and transparently resume when connectivity returns.

In short, NIP-DC is not only a connection setup protocol. It defines a resilient peer connection that can survive transport failure, transport switching, temporary disconnection, and later recovery.

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **NOT RECOMMENDED**, **MAY**, and **OPTIONAL** in this document are to be interpreted as described in BCP 14, RFC 2119 and RFC 8174, when, and only when, they appear in all capitals.

---

## 1. Core concepts

### 1.1 Rooms

A **room** is identified by a Nostr keypair shared by all parties allowed to participate.

* `roomPubkey` is carried in the `P` tag.
* Possession of the room private key represents authorization to participate in that room.

When a peer joins a room, other peers **SHOULD** establish its logical room
presence. Physical connection attempts follow the direct transport behavior or
the bounded neighbor selection described in Section 15.

### 1.2 Peer identity

A peer is represented by:

* a Nostr keypair, ideally random or ephemeral,
* an optional session id `d`, allowing multiple simultaneous sessions for the same peer key.

In practice, a peer instance is identified by `(pubkey, d)`.

### 1.3 Channels

A channel is a persistent logical communication path between two peers that has its own packet ordering, queuing, and retransmission state.

* For WebRTC, a channel is identified by the RTC data channel used for the payloads, including its label.
* For TURN, a channel is identified by the virtual socket `channelLabel`.

Peers **MUST** automatically open a single RTC data channel labeled `default`. That channel **MUST** be ordered and reliable.

Applications **MAY** open additional custom channels on demand. Any custom channel **SHOULD** be opened on both sides before it is used.

NIP-DC does not define any further automatic application-visible channel
creation, other than the `default` channel. Additional application channel
setup **MAY** be coordinated using `default`. The reserved dc4 direct-neighbor
channels in Section 15.2 are internal transport machinery, not application
channels.

In this document, **channel-local** means scoped to one logical application channel.



---

## 2. Room Proof

`roomproof` proves that the sender had access to the room private key.

It is attached to events that require room authorization using:

```text
["roomproof", "<id>", "<sig>"]
```

Where:

* `<id>` is a 32-byte lowercase hex SHA-256 digest of the preimage below.
* `<sig>` is a 64-byte lowercase hex Schnorr signature of `<id>` using the room private key.

### 2.1 Preimage

Sender and verifier compute:

```text
id = sha256( JSON.stringify([
  0,
  <room pubkey hex>,
  <created_at>,
  <kind>,
  <event pubkey hex>,
  <challenge>,
  ""
]) )
```

Where:

* `<room pubkey hex>` is the room public key in lowercase hex, 32 bytes
* `<created_at>` is the event `created_at` in unix seconds
* `<kind>` is the event kind
* `<event pubkey hex>` is the event `pubkey` in lowercase hex, 32 bytes
* `<challenge>` is event-specific and defined by the message type

Serialization **MUST** follow NIP-01 canonical JSON rules:

* UTF-8
* no extra whitespace
* proper escaping

### 2.2 Signature

```text
sig = schnorr_sign(id, roomPrivKey)
```

### 2.3 Verification

The verifier **MUST** recompute `id` from the received event and **MUST** verify `sig` against `roomPubkey`.

---

## 3. Payload Envelope

Every application payload sent through NIP-DC is wrapped in a small binary envelope used for fragmentation and deduplication.

All integers are **big-endian**.

```text
PACKET_ID          uint64
FRAGMENT_ID        int16
FRAGMENT_COUNT     int16
PAYLOAD            uint8[*]
```

* `PACKET_ID` is a sender-generated **channel-local** unique packet id. A simple incrementing counter per channel is sufficient.

### 3.1 Fragmentation

Maximum application payload per fragment is **65491 bytes**. With the 12-byte Payload Envelope header, a complete framed fragment is at most **65503 bytes**. This universal limit applies to both WebRTC and TURN so that the same fragment can be retried on either transport.

If a payload is larger, the sender **MUST** fragment it and the receiver **MUST** reassemble it using `FRAGMENT_ID` and `FRAGMENT_COUNT`.

Rules:

* `FRAGMENT_ID` **MUST** start at `0`
* `FRAGMENT_ID` **MUST** increment by `1`
* the last fragment id **MUST** be `FRAGMENT_COUNT - 1`
* all fragments of the same logical payload **MUST** use the same `PACKET_ID`

### 3.2 Deduplication

Receivers **SHOULD** keep a reasonable window of recently fully assembled `PACKET_ID` values **per logical channel** in order to suppress duplicates.

*A receiver **MUST NOT** assume that `PACKET_ID` values are unique across different logical channels.*

This matters because the same logical packet may be retried after reconnection, resent through a different TURN relay, or delivered again after switching back from TURN to WebRTC.

### 3.3 Timeout

Receivers **MAY** discard incomplete payload state after a reasonable timeout from the first fragment received.

### 3.4 Retransmission

NIP-DC allows retransmission of the same logical packet when delivery is uncertain or known to have failed.

Examples include:

* a direct WebRTC send operation fails,
* a TURN `delivery_ack` is not received before a sender-defined timeout,
* a transport becomes unavailable while delivery is in progress,
* a peer switches from WebRTC to TURN, from one TURN relay to another, or back to WebRTC.

Retransmission policy is implementation-specific.

* A sender **MAY** retry a packet zero or more times.
* A sender **MAY** retry over the same transport or over a different currently available transport.
* A sender **MAY** stop retrying after any implementation-defined limit, timeout, or failure policy.

To remain the same logical packet, a retransmission **SHOULD** preserve:

* the same `PACKET_ID`,
* the same `FRAGMENT_COUNT`,
* and, for each fragment, the same `FRAGMENT_ID`.

A retransmission using a different `PACKET_ID` is a different logical packet and is therefore not deduplicated as a retry of the earlier one.

Receivers are expected to tolerate different retransmission strategies automatically through the deduplication rules in Section 3.2.

* If multiple copies of the same logical packet arrive within the receiver deduplication window for that channel, the receiver **SHOULD** suppress duplicates using `PACKET_ID` after full reassembly.
* Retries **MAY** arrive over different transports or after a transport switch.
* Different implementations **MAY** use different retry timing, retry counts, or transport-selection logic, and receivers **SHOULD NOT** depend on any specific retransmission strategy beyond the packet identity rules above.

If a retransmission arrives after the receiver’s deduplication window for that channel has expired, the receiver **MAY** treat it as a new packet, because the earlier packet identity is no longer guaranteed to be remembered.

---

## 4. Signaling

Signaling is performed through Nostr events of kind `25050`.

Different signaling message types are identified by the `t` tag.

### 4.1 Encryption

For:

* `offer`
* `answer`
* `route`

the `content` **MUST** be encrypted using NIP-44 with a conversation key derived from:

* sender private key
* receiver public key

For broadcast events:

* `connect`
* `disconnect`

the `content` is plaintext.

---

## 5. Presence

### 5.1 Presence event (`t=connect`)

Peers periodically broadcast presence so other peers can discover them.

```yaml
{
  "kind": 25050,
  "content": "<optional message>",
  "tags": [
    ["t", "connect"],
    ["P", "<room hex pubkey>"],
    ["d", "<session id>"],
    ["i", "<protocol identifier>"],
    ["version", "dc4"],
    ["y", "<application id>"],
    ["expiration", "<unix timestamp seconds>"]
  ]
}
```

`version` **MUST** be `dc4`.

A peer **SHOULD** refresh presence before `expiration`.

After expiration, other peers **MAY** consider that peer offline and **MAY** close connections.

### 5.2 Disconnection event (`t=disconnect`)

When a peer leaves the room, it broadcasts:

```yaml
{
  "kind": 25050,
  "content": "<optional message>",
  "tags": [
    ["t", "disconnect"],
    ["P", "<room hex pubkey>"],
    ["d", "<session id>"],
    ["i", "<protocol identifier>"],
    ["y", "<app id>"]
  ]
}
```

---

## 6. WebRTC signaling

### 6.1 Offer (`t=offer`)

When a peer discovers another peer, it **MAY** attempt to connect by sending an offer.

The offer contains the WebRTC SDP offer and related connection metadata in encrypted form.

```yaml
{
  "kind": 25050,
  "content": "<nip44 encrypted offer>",
  "tags": [
    ["t", "offer"],
    ["P", "<room hex pubkey>"],
    ["d", "<session id>"],
    ["i", "<protocol identifier>"],
    ["y", "<app id>"],
    ["p", "<receiver pubkey>"],
    ["roomproof", "<id>", "<sig>"]
  ]
}
```

`roomproof` for this event **MUST** use this challenge:

```text
JSON.stringify([<receiver pubkey>, <nip44 encrypted offer>])
```

### 6.2 Answer (`t=answer`)

An `answer` responds to an offer with the WebRTC SDP answer.

```yaml
{
  "kind": 25050,
  "content": "<nip44 encrypted answer>",
  "tags": [
    ["t", "answer"],
    ["P", "<room hex pubkey>"],
    ["d", "<session id>"],
    ["i", "<protocol identifier>"],
    ["y", "<app id>"],
    ["p", "<receiver pubkey>"],
    ["roomproof", "<id>", "<sig>"]
  ]
}
```

`roomproof` for this event **MUST** use this challenge:

```text
JSON.stringify([<receiver pubkey>, <nip44 encrypted answer>])
```

### 6.3 Route (`t=route`)

Peers **MAY** exchange ICE candidates before and after the offer/answer exchange.

This supports trickle ICE and **MAY** also carry a TURN URL.

```yaml
{
  "kind": 25050,
  "content": nip44encrypted(JSON.stringify(
    {
      "candidates": [
        { "candidate": "<ice candidate>", "sdpMid": "<sdpMid>" }
      ],
      "turn": "<optional NIP-DC TURN server URL>"
    }
  )),
  "tags": [
    ["t", "route"],
    ["P", "<room hex pubkey>"],
    ["d", "<session id>"],
    ["i", "<protocol identifier>"],
    ["y", "<app id>"],
    ["p", "<receiver pubkey>"],
    ["roomproof", "<id>", "<sig>"]
  ]
}
```

`roomproof` for this event **MUST** use this challenge:

```text
JSON.stringify([<receiver pubkey>, <nip44 encrypted route>])
```

---

## 7. Transport behavior

The signaling above allows peers to discover each other and exchange everything needed to establish a connection.

Implementations **SHOULD** behave as follows:

1. discover peers through `connect` presence
2. exchange `offer`, `answer`, and `route`
3. prefer WebRTC if it succeeds
4. if WebRTC fails or becomes unstable, continue through one or more TURN relays if available
5. if direct connectivity becomes available again, switch back to WebRTC
6. if no transport is currently usable, delivery **MAY** pause and **MAY** later resume transparently when connectivity is restored

In all cases, the application continues to use the same logical channel and the same Payload Envelope.

The same logical channel is identified across transports by its channel label: the RTC data channel label when using WebRTC, and the virtual socket `channelLabel` when using TURN.

---

## 8. WebRTC

Once a WebRTC Data Channel is established, peers exchange binary payloads directly over that channel.

Those bytes **MUST** carry the same Payload Envelope described in Section 3.

The RTC data channel label identifies the logical channel used by those payloads. When the same logical channel is carried over TURN, the TURN `channelLabel` **MUST** match the RTC data channel label for that channel.

When WebRTC is used as the transport, transport-level encryption is handled automatically by the WebRTC stack. NIP-DC therefore does **not** define any additional transport encryption layer for payloads sent over WebRTC.

Packet ordering over WebRTC depends on the properties of the underlying RTC data channel.

* If the selected RTC data channel is ordered, packets **MUST** be delivered in channel order.
* If the selected RTC data channel is unordered, packets **MAY** arrive out of order.
* If the selected RTC data channel is unreliable or partially reliable, packet loss **MAY** occur and send failure or missing delivery **MAY** trigger retransmission according to Section 3.4.

If a WebRTC send fails, the sender **MAY** retransmit the same logical packet according to Section 3.4, including over a different transport if available.

---

## 9. TURN (optional)

When direct WebRTC connectivity fails, becomes unstable, or is temporarily unavailable, peers **MAY** relay binary payloads through one or more TURN servers.

TURN is not only a one-way fallback. A connection **MAY**:

* move from WebRTC to TURN,
* move across different TURN relays if needed,
* later return to WebRTC when conditions improve,
* or pause and transparently resume when no route is temporarily usable.

The TURN URL **MAY** be learned from `route` events.

If the `turn` field is missing or empty, no TURN attempt is possible for that route and the connection attempt **MUST** fail unless another usable route is available.

TURN is end-to-end encrypted:

* the relay sees routing metadata in signed headers
* the relay sees opaque encrypted payloads
* the relay does not understand the application protocol

TURN support is **OPTIONAL**. Libraries **MAY** implement only the WebRTC portion, but full resilience requires transport switching and recovery support.

---

## 10. TURN transport and multiplexing

TURN runs over a secure WebSocket between client and server.

One WebSocket **MAY** carry multiple virtual sockets.

A virtual socket represents one logical channel. Its `channelLabel` names that channel and, when the same logical channel also exists over WebRTC, the TURN `channelLabel` **MUST** match the RTC data channel label used for that channel.

A virtual socket is uniquely identified by:

```text
(roomPubkey, channelLabel, clientSessionId, targetSessionId, protocolId, applicationId, clientPubkey, targetPubkey)
```

After receiving the initial `challenge`, the client **MAY** create additional virtual sockets by sending additional `connect` messages with different parameters, without solving a new challenge for each socket.

---

## 11. TURN envelope

Every TURN message is framed in this binary envelope.

All integers are **big-endian**.

```text
VERSION            uint8
VSOCKET_ID         int64
MESSAGE_ID         int32
HEADER_SIZE        uint16
HEADER_BYTES       uint8[HEADER_SIZE]
NUM_PAYLOADS       uint16
for (i=0; i<NUM_PAYLOADS; i++):
  PAYLOAD_SIZE_i   uint32
  PAYLOAD_i        uint8[PAYLOAD_SIZE_i]
```

This envelope is for TURN routing and is distinct from the end-to-end Payload Envelope.

* the TURN envelope is visible to the relay
* the Payload Envelope is end-to-end and meaningful only to the peers

Field meaning:

* `VERSION` is always `2`
* `VSOCKET_ID` is a client-generated unique identifier for the virtual socket, scoped to the WebSocket connection
* `0` is reserved and used only for the initial `challenge`
* `MESSAGE_ID` is a client-generated identifier used for deduplication and acknowledgements
* `HEADER_SIZE` is the UTF-8 byte size of the header event
* `HEADER_BYTES` is the UTF-8 JSON stringified header event
* `NUM_PAYLOADS` is the number of encrypted payload blobs in the frame

Rules for `MESSAGE_ID`:

* for `challenge`, `connect`, `ack`, and `disconnect`, `MESSAGE_ID` **MUST** be `0`
* for `data` and `delivery_ack`, `MESSAGE_ID` **MUST** be non-zero and unique per `(sender socket, direction)`

---

## 12. TURN header shape (kind 25051)

The TURN header **MUST** be a valid Nostr event of kind `25051`.

```yaml
{
  "kind": 25051,
  "content": <event specific payload>,
  "tags": [
    ["t", "<challenge|connect|ack|disconnect|data|delivery_ack>"],
    ["r", "<optional redirect URL>"],
    ["P", "<roomPubkeyHex>"],
    ["d", "<source sessionId>"],
    ["i", "<protocolId>"],
    ["y", "<applicationId>"],
    ["p", "<remote target peer pubkey>", "<channel label>", "<remote target sessionId>"],
    ["enc", "nip44-v2", "<nip44 encrypted secret for symmetric encryption>"],
    ["nonce", "<nonce>", "<difficulty>"],
    ["roomproof", "<id>", "<sig>"],
    ["expiration", "<unix timestamp in seconds>"]
  ]
}
```

Not every tag is required for every message type.

---

## 13. TURN message types

### 13.1 Challenge (`t=challenge`)

`server -> client`

Sent immediately after the WebSocket opens.

It communicates:

* the PoW difficulty
* a random challenge token
* optionally a redirect URL

#### TURN envelope

* `VSOCKET_ID` **MUST** be `0`
* `MESSAGE_ID` **MUST** be `0`

#### Header

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "challenge"]
    # ["r", "<optional redirect URL>"]
  ],
  "content": JSON.stringify({
    "difficulty": 13,
    "challenge": "<random token>"
  })
}
```

Meaning:

* `content.difficulty` is the required PoW difficulty in leading zero bits
* `content.challenge` is the token the client must copy into the next `connect`
* `r`, if present, is an optional redirect URL for another TURN server

Behavior:

* if the client supports redirects and `r` is present, it **SHOULD** switch to the provided URL
* otherwise it **MUST** continue on the current connection
* challenge validity is scoped to the lifetime of the WebSocket that received it
* once that WebSocket closes, the challenge is no longer valid
* servers **SHOULD** enforce a timeout for idle unauthenticated WebSockets that never send a valid `connect`

#### Payload

No payloads.

---

### 13.2 Connect (`t=connect`)

`client -> server`

Requests creation of a virtual socket.

It **MUST** include:

* PoW satisfying the announced difficulty
* valid `roomproof`
* the copied challenge token in `content`
* a client-generated `vsocketId` both in `content` and in the envelope
* routing identifiers for source and destination sessions

#### TURN envelope

* `VSOCKET_ID` **MUST** be non-zero
* `MESSAGE_ID` **MUST** be `0`

#### Header

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "connect"],
    ["P", "<room pub key>"],
    ["d", "<source sessionId>"],
    ["i", "<protocolId>"],
    ["y", "<applicationId>"],
    ["p", "<remote target peer pubkey>", "<channel label>", "<remote target sessionId>"],
    ["nonce", "<nonce>", "<difficulty>"],
    ["roomproof", "<roomproof.id>", "<roomproof.sig>"]
  ],
  "content": JSON.stringify({
    "challenge": "<challenge token>",
    "vsocketId": "<int64 != 0>"
  })
}
```

Rules:

* `content.vsocketId` **MUST** exactly match envelope `VSOCKET_ID`
* `roomproof` challenge for this event **MUST** be the raw `challenge` string copied into `content`

The server **MUST** reject `connect` if:

* `VSOCKET_ID == 0`
* `content.vsocketId` does not match the envelope
* `VSOCKET_ID` collides with an already active socket on that WebSocket
* `d` is missing or blank
* `p` does not include a non-empty destination session id as its third value

#### Payload

No payloads.

---

### 13.3 Ack (`t=ack`)

`server -> client`

Confirms that the virtual socket was accepted.

This is only for virtual-socket establishment, not data delivery.

#### TURN envelope

* `VSOCKET_ID` **MUST** be the accepted non-zero value from `connect`
* `MESSAGE_ID` **MUST** be `0`

#### Header

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "ack"]
  ],
  "content": ""
}
```

#### Payload

No payloads.

---

### 13.4 Disconnect (`t=disconnect`)

`server <-> client`

Terminates a virtual socket.

#### TURN envelope

* `VSOCKET_ID` **MUST** be non-zero
* `MESSAGE_ID` **MUST** be `0`

The server **MUST** ignore `disconnect` messages carrying:

* `VSOCKET_ID == 0`
* unknown `VSOCKET_ID`
* stale `VSOCKET_ID`

#### Header

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "disconnect"]
  ],
  "content": JSON.stringify({
    "reason": "<human readable reason>",
    "error": <true|false>
  })
}
```

* `reason` is a human-readable explanation such as `"peer unreachable"`, `"protocol error"`, or `"normal closure"`
* `error` indicates whether the disconnection is due to an error or normal shutdown

If the server closes because offline queue limits or timeouts were hit before the other side connected, it **MUST** use:

```json
{"reason":"peer unreachable","error":true}
```

#### Payload

No payloads.

---

### 13.5 Data (`t=data`)

`client1 <-> server <-> client2`

Carries encrypted payload bytes.

The relay routes by reciprocal socket matching and **MUST NOT** interpret the payload.

#### TURN envelope

* `VSOCKET_ID` **MUST** be non-zero
* the server **MUST** ignore `data` messages carrying `VSOCKET_ID == 0`, unknown `VSOCKET_ID`, or stale `VSOCKET_ID`
* `MESSAGE_ID` **MUST** be non-zero and unique per `(sender socket, direction)` at least within the retransmission or timeout window

#### Payload encryption

Each payload blob is encrypted using NIP-44 binary encryption:

* generate a random 32-byte symmetric secret
* calculate the directional routing hash defined below
* prefix the plaintext Payload Envelope with the 32-byte routing hash
* use the symmetric secret as the conversation key to encrypt that prefixed plaintext
* do not base64-encode the output

That secret is then hex-encoded, encrypted with regular NIP-44 using the sender/receiver conversation key, and included in the `enc` tag.

Example:

```javascript
let secret = bytesToHex(randomBytes(32));
const conversationKey = deriveConversationKey(senderPrivKey, receiverPubKey);
const enc = ["enc", "nip44-v2", nip44Encrypt(secret, conversationKey)];
```

#### Routing context

The routing hash cryptographically binds a payload to its intended NIP-DC route:

```text
ROUTING_HASH = SHA-256(CANONICAL_ROUTING_CONTEXT)
```

`CANONICAL_ROUTING_CONTEXT` is the concatenation of the following fields in this exact order. Every field is encoded as a 4-byte unsigned big-endian byte length followed by the field bytes:

1. raw 32-byte `roomPubkey`
2. UTF-8 `channelLabel`
3. UTF-8 source `sessionId`
4. UTF-8 target `sessionId`
5. UTF-8 `protocolId`
6. UTF-8 `applicationId`
7. raw 32-byte source pubkey
8. raw 32-byte target pubkey

#### Header

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "data"],
    ["enc", "nip44-v2", "<nip44 encrypted secret for symmetric encryption>"]
  ],
  "content": ""
}
```

#### Payload

One or more encrypted binary blobs. After authenticating and decrypting every blob, the receiver **MUST** require and verify the first 32 plaintext bytes against its locally calculated incoming routing hash. It **MUST** reject the complete TURN frame if any blob is too short or has a different hash, without delivering any blob or sending a successful `delivery_ack`. Once all blobs validate, the receiver strips the hashes and exposes only the original Payload Envelopes.

#### Acceptance, ordering, and offline queueing

* the server **MUST** process `data` only for sockets previously accepted with `ack`
* a receiver socket is reciprocal only if room, protocol, application, and channel match, and the identities are inverted:

  * `(clientPubkey, sourceSessionId)` on one side matches `(targetPubkey, targetSessionId)` on the other side
  * and vice versa
* when forwarding to the reciprocal socket, the server **MUST** rewrite only envelope `VSOCKET_ID` to the receiver socket id
* the server **MUST** preserve `MESSAGE_ID`
* TURN delivery **MUST** preserve packet order within each virtual socket, and therefore within each logical channel carried by that virtual socket
* packets forwarded over TURN on the same virtual socket **MUST NOT** be delivered out of order
* if the target peer is offline, the server **MAY** queue payloads
* queued payloads **MUST** be delivered only after the target peer connects and completes a matching accepted `connect`
* if the queue grows too large or the target does not connect in time, the server **MAY** `disconnect` with `reason="peer unreachable", error=true`
* if delivery is not confirmed or the selected transport fails, the sender **MAY** retransmit the same logical packet according to Section 3.4

Because payloads are opaque, replay protection, if needed, **MUST** be handled by the application protocol inside the encrypted payload.

#### Header reuse

The sender **MAY** reuse the same header for multiple `data` messages as long as the required fields remain valid.

A relay **MAY** optimize for this by caching and byte-comparing the header before doing full parse and validation.

Header reuse is allowed only for `data` and `delivery_ack`. All other TURN message types **MUST** use a unique header per message.

---

### 13.6 Delivery Ack (`t=delivery_ack`)

`client2 -> server -> client1`

Acknowledges delivery of a specific `data` message identified by `MESSAGE_ID`.

#### TURN envelope

* `VSOCKET_ID` **MUST** be non-zero
* `MESSAGE_ID` **MUST** be non-zero
* `MESSAGE_ID` **MUST** match a previously received `data` message on the reciprocal socket

#### Header

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "delivery_ack"]
  ],
  "content": ""
}
```

#### Payload

No payloads.

#### Delivery semantics

* the receiver **MUST** emit `delivery_ack` only after the corresponding `data` payload has been fully delivered to the receiver-side application or channel API
* the sender **MAY** treat receipt of `delivery_ack` as write completion for that `MESSAGE_ID`
* if `delivery_ack` is not received before a sender-defined timeout, the sender **SHOULD** fail the pending write for that `MESSAGE_ID`
* the server **MUST** route `delivery_ack` using reciprocal socket matching
* the server **MUST** rewrite only `VSOCKET_ID`
* the server **MUST** preserve `MESSAGE_ID`

#### Header reuse

The sender **MAY** reuse the same header for multiple `delivery_ack` messages as long as the required fields remain valid.

Header reuse is allowed only for `data` and `delivery_ack`. All other TURN message types **MUST** use a unique header per message.

---

## 14. Minimal implementation order

A straightforward implementation order is:

1. implement room identity, peer identity, and `roomproof`
2. implement presence via `connect` and `disconnect`
3. implement encrypted `offer`, `answer`, and `route`
4. establish WebRTC and send the Payload Envelope over the data channel
5. add fragmentation, deduplication, timeout, and retransmission handling
6. optionally add TURN fallback
7. add offline queueing and `delivery_ack`
8. finally add transport migration and resume behavior so the logical connection can move between WebRTC and TURN transparently

---

## 15. dc4 routed rooms

Protocol version `dc4` extends the logical connection model to rooms where a
full mesh is undesirable. Every valid room presence still creates a stable
logical peer and socket. Only a deterministic bounded subset of peers receives
a physical WebRTC/TURN link.

The default direct-degree target is `16`. Implementations **MUST** support a
configured value from `2` through `64`, inclusive, and **MUST** count both
initiated and accepted links against that value. When the room has no more than
`maxDirectPeers + 1` members, the direct graph is a full mesh. Larger rooms use:

1. a ring over sorted NodeIds;
2. deterministic symmetric chord rounds;
3. deterministic repair edges after a graph partition persists.

With a degree of at least four, the ring uses the two predecessors and two
successors. A chord score is:

```text
sha256(
  UTF8("nip-dc-routing-chord-v1") ||
  ROUTING_SCOPE ||
  sha256(sorted NodeIds concatenated) ||
  uint32_be(round) ||
  NodeId
)
```

Nodes are sorted by that score and adjacent entries are paired. Backbone and
repair edges take precedence over optional chords. Implementations **SHOULD**
retain a healthy optional chord briefly across membership churn.

Routing cannot manufacture physical reachability. Partition repair converges
only when honest peers receive compatible room events and at least one viable
direct RTC or TURN link can be established between every disconnected
component.

### 15.1 Routing scope, NodeId, and EdgeId

`ROUTING_SCOPE` is the following concatenation. Lengths are unsigned 32-bit
big-endian values:

```text
uint32(room_pubkey_length)    room_pubkey_bytes
uint32(protocol_utf8_length) protocol_utf8
uint32(application_length)   application_utf8
```

A session NodeId is:

```text
sha256(
  UTF8("nip-dc-routing-node-v1") ||
  ROUTING_SCOPE ||
  uint32(peer_pubkey_length) ||
  peer_pubkey_bytes ||
  uint32(session_utf8_length) ||
  session_utf8
)
```

The result is exactly 32 bytes. An undirected edge id is:

```text
sha256(
  UTF8("nip-dc-routing-edge-v1") ||
  ROUTING_SCOPE ||
  min(NodeIdA, NodeIdB) ||
  max(NodeIdA, NodeIdB)
)
```

Node ordering is unsigned lexicographic byte ordering.

### 15.2 Reserved direct-neighbor channels

The prefix `__nipdc_dc4_route/` is reserved. Applications **MUST NOT** create,
send directly on, or broadcast on a label with this prefix.

The reliable ordered control channel is:

```text
__nipdc_dc4_route/control
```

Data channels encode their transport profile:

```text
__nipdc_dc4_route/data/o<0|1>r<0|1>x<int32>l<int64>
__nipdc_dc4_route/broadcast/o<0|1>r<0|1>x<int32>l<int64>
```

`o` is ordered, `r` is reliable, `x` is `maxRetransmits`, and `l` is
`maxPacketLifeTime` in milliseconds. `-1` means absent. At most one of `x` and
`l` may be non-negative. Reserved channels exist only between selected physical
neighbors and are not application-visible.

### 15.3 Transport selection

For each normal application fragment, a dc4 implementation selects transport
in this order:

1. use connected direct WebRTC immediately;
2. otherwise compare a usable direct TURN edge with the best routed path;
3. use routing when no direct TURN path is usable or when the routed cost is
   lower;
4. otherwise use direct TURN;
5. if no path is ready, keep the same prepared packet pending until the queue
   timeout or a non-retryable failure.

The reference deterministic edge costs are RTC `10 + 5 per hop`, TURN `35 + 5
per hop`, and UNKNOWN `50 + 5 per hop`. A failed complete route receives a
temporary penalty of `1000` for 30 seconds. Planners consider at most four
simple candidates and sixteen hops, preferring lower cost, fewer TURN edges,
fewer hops, and then alternatives disjoint from a recently failed path.

Direct WebRTC carries the normal Payload Envelope. Direct TURN additionally
applies the dc4 per-blob routing hash from Section 13.5. Overlay framing and
its end-to-end encryption apply only to multi-hop routed traffic and are
carried inside those direct transport rules on every physical hop.

## 16. Private topology control plane

Each dc4 peer publishes a replaceable topology snapshot as Nostr kind `30350`.
The public envelope is:

```yaml
{
  "kind": 30350,
  "content": "<NIP-44 encrypted JSON>",
  "tags": [
    ["d", "dc-topology:<room>:<protocol>:<application>:<session>"],
    ["t", "dc-topology"],
    ["version", "dc4"],
    ["P", "<room pubkey>"],
    ["i", "<protocol id>"],
    ["y", "<application id>"],
    ["revision", "<positive monotonic int64>"],
    ["expiration", "<positive unix seconds>"],
    ["roomproof", "<id>", "<sig>"]
  ]
}
```

Neighbor identities, edge ids, transport classifications, and the routing
public key **MUST NOT** appear in public tags. The author encrypts content with
its peer signer/private key and the room public key using NIP-44. A room member
decrypts with the room private key and event-author public key.

The topology roomproof challenge is canonical JSON of:

```text
[
  room_pubkey_hex,
  protocol_id,
  application_id,
  session_id,
  unsigned_decimal_revision,
  decimal_expiration_seconds,
  sha256(encrypted_content_utf8)_hex
]
```

The decrypted JSON is:

```json
{
  "formatVersion": "dc4",
  "revision": 1,
  "nodeId": "<32-byte hex>",
  "routingPublicKey": "<32-byte hex>",
  "issuedAt": 1700000000,
  "expiresAt": 1700000060,
  "neighbors": [
    {
      "nodeId": "<32-byte hex>",
      "pubkey": "<32-byte hex>",
      "sessionId": "<session>",
      "edgeId": "<32-byte hex>",
      "transport": "<RTC|TURN|UNKNOWN>"
    }
  ]
}
```

`issuedAt` **MUST** equal event `created_at`; decrypted expiry and revision
**MUST** equal their public tags. Revision must strictly increase for a given
NodeId. A snapshot may list at most 64 direct neighbors.

An edge enters the usable graph only when both newest unexpired endpoint
snapshots list one another, both compute the same EdgeId, both peer presences
are current, and both endpoints advertise dc4. Conflicting directional
transport claims are resolved pessimistically: any TURN claim yields TURN;
both must claim RTC for RTC; otherwise the edge is UNKNOWN. A unilateral claim
never creates an edge.

The graph snapshot id is:

```text
sha256(
  UTF8("nip-dc-routing-graph-v1") ||
  sorted_node_ids ||
  for each edge sorted by EdgeId: EdgeId || uint8(effective_transport_ordinal)
)
```

The transport ordinals are RTC `0`, TURN `1`, UNKNOWN `2`.

## 17. Circuit setup and stateless control

All binary integers in Sections 17–20 are big-endian. Widths, signedness, and
validation are normative. `uint8`, `uint16`, and `uint32` are interpreted
unsigned; sentinel-bearing `int32` and `int64` fields are signed two's
complement. Timestamps are positive Unix seconds.

Circuit and setup/message identifiers are random 128-bit values. A v1 circuit
id remains unchanged at every hop. Forwarding state is keyed by:

```text
(previous_direct_peer_NodeId, circuit_id)
```

### 17.1 Route setup envelope

```text
MAGIC              uint32 = 0x44433453 ("DC4S")
VERSION            uint8  = 1
TYPE               uint8  = 1
SETUP_ID           uint8[16]
CIRCUIT_ID         uint8[16]
EXPIRES_AT         int64
REMAINING_HOPS     uint8
EPHEMERAL_PUBKEY   uint8[32]
CIPHERTEXT_LENGTH  uint32
CIPHERTEXT         uint8[CIPHERTEXT_LENGTH]
```

The fixed header is 83 bytes. `REMAINING_HOPS` is 1–16, ciphertext is non-empty,
and total size is at most 65,536 bytes. Expiry must be after receipt time and no
more than 120 seconds in the future.

The source creates one ephemeral secp256k1 keypair for this setup, encrypts
small nested layers from destination back toward the first hop using NIP-44
binary encryption, and destroys the private key after construction.

### 17.2 Route setup layer plaintext

```text
MAGIC              uint32 = 0x4443344c ("DC4L")
VERSION            uint8  = 1
FLAGS              uint8
CIRCUIT_ID         uint8[16]
EXPIRES_AT         int64
REMAINING_HOPS     uint8
PROFILE_FLAGS      uint8
MAX_RETRANSMITS    int32
MAX_LIFETIME_MS    int64
NEXT_NODE          uint8[32]
SOURCE_NODE        uint8[32]
INNER_LENGTH       uint32
INNER              uint8[INNER_LENGTH]
```

The fixed portion is 112 bytes. `FLAGS bit 0` means final; all other bits are
zero. `PROFILE_FLAGS bit 0` is ordered and bit 1 is reliable; all other bits
are zero. `-1` means an absent partial-reliability field and the two partial
fields may not both be present.

For an intermediate layer: final is zero, remaining hops is greater than one,
`NEXT_NODE` is non-zero, `SOURCE_NODE` is zero, and `INNER` is non-empty. For
the destination layer: final is one, remaining hops is one, `NEXT_NODE` is
zero, `SOURCE_NODE` is the source, and inner length is zero. Envelope and layer
circuit id, expiry, and remaining-hop count must match.

An intermediate stores only previous peer, circuit id, next peer, transport
profile, and expiry. It does not store the full route or application payload.
Circuit state expires automatically and is bounded as specified in Section 20.

### 17.3 Stateless control envelope

Setup confirmations and delivery/broadcast acknowledgements select an
independent return route and do not require reverse circuit state.

```text
MAGIC              uint32 = 0x44433443 ("DC4C")
VERSION            uint8  = 1
MESSAGE_ID         uint8[16]
EXPIRES_AT         int64
REMAINING_HOPS     uint8
EPHEMERAL_PUBKEY   uint8[32]
CIPHERTEXT_LENGTH  uint32
CIPHERTEXT         uint8[CIPHERTEXT_LENGTH]
```

The fixed header is 66 bytes. Hop, size, and expiry limits are the same as
route setup.

Each decrypted stateless onion layer is:

```text
MAGIC              uint32 = 0x4443344f ("DC4O")
VERSION            uint8  = 1
FLAGS              uint8
EXPIRES_AT         int64
REMAINING_HOPS     uint8
NEXT_NODE          uint8[32]
ORIGIN_NODE        uint8[32]
INNER_LENGTH       uint32
INNER              uint8[INNER_LENGTH]
```

The fixed portion is 83 bytes. `FLAGS bit 0` means final. Intermediate layers
have a non-zero next node, zero origin, remaining hops greater than one, and a
non-empty inner payload. The final layer has zero next node, non-zero origin,
remaining hops one, and carries the end-to-end protected control payload.

### 17.4 End-to-end control plaintext

The final control payload is NIP-44 binary encrypted between the origin and
destination routing keys:

```text
MAGIC              uint32 = 0x44433445 ("DC4E")
VERSION            uint8  = 1
TYPE               uint8
SENDER_NODE        uint8[32]
DESTINATION_NODE   uint8[32]
CORRELATION_ID     uint8[16]
CIRCUIT_ID         uint8[16]
PACKET_ID          int64
FRAGMENT_ID        int16
CHANNEL_LENGTH     uint16
ACK_TOKEN          uint8[16]
CHANNEL_UTF8       uint8[CHANNEL_LENGTH]
```

The fixed portion is 130 bytes and channel length is at most 1,024 bytes. Types
are setup-confirmed `1`, delivery-ack `2`, circuit-error `3`, and broadcast-ack
`4`. Endpoint identities are authenticated by NIP-44 and must match the
expected origin and local destination.

## 18. Routed application data

### 18.1 Routed data frame

```text
MAGIC              uint32 = 0x44433444 ("DC4D")
VERSION            uint8  = 1
TYPE               uint8
FLAGS              uint16
CIRCUIT_ID         uint8[16]
ATTEMPT_ID         uint8[16]
EXPIRES_AT         int64
CIPHERTEXT_LENGTH  uint32
CIPHERTEXT         uint8[CIPHERTEXT_LENGTH]
```

The fixed header is 52 bytes. Types are application data `1`, control `2`, ACK
`3`, and broadcast repair `4`. For application data, `FLAGS bit 0` means a
destination acknowledgement is required; all other bits are zero. Ciphertext
is non-empty, total frame size is at most 1,048,576 bytes, and expiry must be
within the next 120 seconds.

An intermediate validates the frame and circuit mapping, then forwards the
same immutable wire bytes. It does not decrypt or copy the application
ciphertext.

### 18.2 Routed payload plaintext

Application data is encrypted once, end-to-end, with the route-independent
NIP-44 conversation key derived from the source and destination per-session
routing keys:

```text
MAGIC              uint32 = 0x44433450 ("DC4P")
VERSION            uint8  = 1
FLAGS              uint8
SOURCE_NODE        uint8[32]
DESTINATION_NODE   uint8[32]
CHANNEL_LENGTH     uint16
CHANNEL_UTF8       uint8[CHANNEL_LENGTH]
PACKET_ID          int64
FRAGMENT_ID        int16
FRAGMENT_COUNT     int16
ACK_TOKEN          uint8[16]
NORMAL_FRAME_LEN   uint32
NORMAL_FRAME       uint8[NORMAL_FRAME_LEN]
```

The fixed portion excluding channel and normal-frame bytes is 104 bytes.
`FLAGS bit 0` requires an ACK, bit 1 is ordered, and bit 2 is reliable; all
other bits are zero. Channel length is 1–1,024 bytes and may not use the
reserved routing prefix. The normal frame is the unchanged Section 3 envelope;
its packet and fragment identity must exactly match the duplicated authenticated
fields.

NIP-44 plaintext is limited to 65,535 bytes. Therefore:

```text
maximum_normal_frame_bytes = 65535 - 104 - channel_utf8_length
```

Routing fragmentation must respect that limit without changing direct
WebRTC/TURN fragmentation.

For an ACK-requiring packet, `ACK_TOKEN` is 16 random non-zero bytes. For a
non-ACK packet it is all zero. Route retries preserve normal packet identity,
ACK token, and ciphertext, but use a fresh `ATTEMPT_ID` and may use another
circuit.

The destination authenticates and decrypts the payload, validates destination,
channel, profile, token, and fragment identity, and injects `NORMAL_FRAME` into
the same normal channel reassembly and PACKET_ID deduplication path. Only after
that path accepts the frame does it send a delivery ACK.

### 18.3 Delivery and retry rules

A reliable routed send is complete only after the final destination ACK
arrives. Immediate-hop WebRTC success is insufficient. The destination ACK
chooses its own current best route back to the source.

If the ACK is lost after delivery, the source times out, penalizes the complete
failed path, discards the failed source circuit, and retries the same prepared
packet on an alternative candidate. The destination's normal PACKET_ID cache
suppresses a second application delivery but emits another ACK. A setup failure
is retryable and leaves the same prepared packet at the send-queue head.

Unreliable packets create no destination-ACK tracker. Partially reliable
packets obey their retransmit or lifetime bound. All pending acknowledged
deliveries fail on transport shutdown.

## 19. Tree broadcast

dc4 broadcast is not one routed unicast per peer and is not flooding. The
origin builds a deterministic weighted shortest-path tree over one mutually
attested graph snapshot. Tie-breaking is total cost, TURN-edge count, hop
count, NodeId, and parent NodeId. The tree must be connected and no path may
exceed sixteen hops.

The stable payload transmission count is exactly `N - 1`: the origin sends
only to its children and each receiver forwards only to its children.
Receivers derive their expected parent from origin, graph snapshot id, and
local NodeId; a frame arriving from any other neighbor is dropped. Current and
one recent graph snapshot are retained so in-flight frames can finish. An
unknown tree id is dropped and never triggers flooding.

The broadcast frame is:

```text
MAGIC              uint32 = 0x44433442 ("DC4B")
VERSION            uint8  = 1
FLAGS              uint8
HOP_LIMIT          uint8
RESERVED           uint8 = 0
MAX_RETRANSMITS    int32
MAX_LIFETIME_MS    int64
EXPIRES_AT         int64
ORIGIN_NODE        uint8[32]
BROADCAST_ID       uint8[16]
GRAPH_SNAPSHOT_ID  uint8[32]
CHANNEL_LENGTH     uint16
PAYLOAD_LENGTH     uint32
CHANNEL_UTF8       uint8[CHANNEL_LENGTH]
PAYLOAD            uint8[PAYLOAD_LENGTH]
```

The fixed header is 114 bytes. `FLAGS bit 0` is reliable and bit 1 is ordered.
Hop limit is 1–16, channel length is 1–1,024 bytes, both channel and payload
are non-empty, reserved routing labels are forbidden, and total size is at
most 1,048,576 bytes. Expiry is at most 120 seconds in the future.

Broadcast recipients are all authorized room members, so broadcast does not
add per-destination payload encryption. It relies on the encrypted direct
links and room authorization and therefore does not hide broadcast content
from intermediating room members.

An unreliable broadcast forwards/delivers once and has no ACK. For a reliable
broadcast, the origin freezes target membership at send time. Each recipient
sends a small end-to-end protected broadcast ACK using normal stateless routed
control. After the ACK timeout, the origin performs reliable routed unicast
repair only to missing peers. A repaired recipient deduplicates by
`(origin, broadcast_id, logical_channel)` and does not deliver twice.

## 20. Validation, caches, and resource limits

Implementations **MUST** validate the fixed header before reading variable
fields, validate lengths before allocating, require declared variable lengths
to equal exactly the remaining bytes, reject trailing data, unknown versions,
unknown types, unknown flag bits, expired frames, excessive future expiry,
wrong previous-hop circuit mappings, invalid profiles, and reserved logical
channels.

The dc4 reference limits are:

| Resource | Limit |
| --- | ---: |
| configured direct degree | 2–64; default 16 |
| neighbors in one topology snapshot | 64 |
| encrypted topology event | 65,536 bytes |
| retained topology snapshots | 2,048 |
| topology snapshot lifetime | 300 seconds |
| route hops | 16 |
| route candidates | 4 |
| route setup/stateless control packet | 65,536 bytes |
| routed or broadcast frame | 1,048,576 bytes |
| active/pending circuits globally | 4,096 |
| circuits per direct neighbor | 256 |
| circuit/frame maximum lifetime | 120 seconds |
| pending acknowledged deliveries | 4,096 |
| route/application dedup or ciphertext cache entries | 8,192 |
| broadcast trackers | 1,024 |
| tracked direct-neighbor rate states | 128 |
| control decryptions in flight | 32 |
| packets per second per direct neighbor | 2,048 |
| bytes per second per direct neighbor | 8,388,608 |
| malformed packets per minute per direct neighbor | 128 |
| cached routing conversation keys | 1,024 |
| retained broadcast graph snapshots | 2 |

Rate admission occurs before parsing and cryptographic work. State keyed by
attacker-controlled identifiers must be capacity- and time-bounded. Shutdown
clears circuits, delivery trackers, ciphertext/dedup caches, topology state,
broadcast trackers, conversation keys, and rate state. Per-session routing and
setup private keys are best-effort overwritten when their lifetime ends.

Implementations **MUST NOT** log plaintext payloads, routing private keys,
conversation keys, ACK tokens, complete encrypted route packets, or full paths
at normal log levels.

## 21. Security properties and limitations

dc4 provides:

* room-authorized, private topology publication against observers lacking the
  room private key;
* mutual edge attestation rather than unilateral reachability claims;
* per-hop authenticated onion control and once-per-payload end-to-end
  authentication for routed unicast;
* final-destination delivery confirmation and route-level failure recovery;
* bounded forwarding, deduplication, and acknowledgement state.

It does not provide:

* Sybil resistance;
* proof that a mutually claimed edge really works;
* identification of a guilty hop after a failed ACK;
* topology secrecy from room members;
* protection against a room member leaking the decrypted topology;
* protection against traffic correlation by colluding hops that observe the
  unchanged v1 circuit id;
* protection against an intermediary dropping or delaying traffic;
* privacy mode, IP hiding, cover traffic, or Tor-grade anonymity.

Routing private keys are session-ephemeral and are distinct from room and peer
identity keys. A routing-key change invalidates cached conversations and old
routed ciphertext. Current topology publication distributes the replacement
public key.

## 22. Application-visible routing semantics

dc4 peers preserve the public send, receive, channel, and callback shape. A
routed message resolves the original logical source socket and channel; the
last physical hop is never exposed as the application sender.
