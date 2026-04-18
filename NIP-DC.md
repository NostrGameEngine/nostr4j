# NIP-DC: Direct Connect between peers

`draft` `optional` `author:riccardobl`

NIP-DC defines a resilient way for peers to discover each other over Nostr, establish a connection, and exchange binary payloads.

It supports two transports:

1. **WebRTC**, for direct peer-to-peer connectivity when possible.
2. **WebSocket-based TURN with signed headers**, for relay-based transport when direct connectivity is unavailable or unstable.

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

When a peer joins a room, other peers in that room **SHOULD** attempt to connect to it.

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

NIP-DC does not define any further automatic channel creation, other than the `default` channel. Additional channel setup **MAY** be coordinated at the application layer using the `default` channel.

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

Maximum payload size is **65523 bytes**.

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
    ["version", "dc3"],
    ["y", "<application id>"],
    ["expiration", "<unix timestamp seconds>"]
  ]
}
```

`version` **MUST** be `dc3`.

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

The payload is encrypted using a NIP-44-like scheme:

* generate a random 32-byte symmetric secret
* use it as the conversation key to encrypt the raw binary payload
* do not base64-encode the output

That secret is then hex-encoded, encrypted with regular NIP-44 using the sender/receiver conversation key, and included in the `enc` tag.

Example:

```javascript
let secret = bytesToHex(randomBytes(32));
const conversationKey = deriveConversationKey(senderPrivKey, receiverPubKey);
const enc = ["enc", "nip44-v2", nip44Encrypt(secret, conversationKey)];
```

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

One or more encrypted binary blobs.

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
