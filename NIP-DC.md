# NIP-DC: Direct Connect between peers
============================

`draft` `optional` `author:riccardobl`

NIP-DC defines a general-purpose way to coordinate and establish peer-to-peer connections using Nostr for discovery and signaling.

It supports two transports:

1. **WebRTC**: for direct peer-to-peer connectivity when possible
2. **Websocket-based TURN with signed headers**: for relay-based transport when direct connectivity fails

Both transports share the same concepts:

* **Rooms**: shared room keypair
* **Peers**: per-peer keypair + optional session id
* **roomproof**: room cosignature that proves the sender has the room key


## Rooms

A **room** is identified by a Nostr keypair shared among all parties who are allowed to connect within that room.

* `roomPubkey` is carried in the `P` tag.
* Possession of the room private key represents authorization to participate in that room.

When a peer joins a room, other peers in the room should attempt to connect to them (WebRTC directly, or via TURN fallback).


## Peer identity

A peer is represented by:

* a Nostr keypair (ideally random / ephemeral), and
* a session id `d` (to allow multiple simultaneous connections from the same peer key).


## Room Proof

`roomproof` proves that the sender had access to the room private key and that other peers can accept the event as authentic and authorized to represent a peer in that room.

It is attached to events that require it, using this tag:

```text
["roomproof", "<id>", "<sig>"]
```

Where:

* `<id>` is 32-byte lowercase hex sha256 of the preimage below
* `<sig>` is a 64-byte lowercase hex Schnorr signature of `<id>` using the room private key

### Preimage `<id>`

Both sender and verifier compute:

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

* `<room pubkey hex>` is the room public key in lowercase hex (32 bytes)
* `<created_at>` is the event’s `created_at` (unix seconds)
* `<kind>` is the event kind
* `<event pubkey hex>` is the event’s `pubkey` in lowercase hex (32 bytes)
* `<challenge>` is a custom challenge that depends on the event type (defined below).

Serialization MUST follow NIP-01 canonical JSON rules (UTF-8, no whitespace, proper escaping).

### CoSignature `<sig>`

```text
sig = schnorr_sign(id, roomPrivKey)
```

### Verification

Verifier recomputes `id` from the received event fields and validates `sig` against `roomPubkey`.


# Data Channel over WebRTC with Nostr Signaling (happy path)

Using Nostr relays for discovery and signaling, direct data channels can be established between peers. This is the preferred mode, and should reasonably work for most peers. The underlying protocol is WebRTC Data Channels; this NIP defines how signaling and discovery happens over Nostr. WebRTC connection and data channel management is up to the application.

## Encryption

In the `offer`, `answer` and `route` signaling events, `content` MUST be encrypted using NIP-44 with a conversation key derived from:

* sender private key, and
* receiver public key.

Broadcast events `connect` and `disconnect` have plaintext `content`.

## Presence event (`t=connect`)

Peers periodically broadcast presence to allow discovery.

```yaml
{
  "kind": 25050,
  "content": "<optional message>",
  "tags": [
    ["t", "connect"],
    ["P", "<room hex pubkey>"],
    ["d", "<session id>"],
    ["i", "<protocol identifier>"],
    ["y", "<application id>"],
    ["expiration", "<unix timestamp seconds>"]
  ]
}
```

A peer SHOULD refresh presence before `expiration`. If presence expires, other peers may consider the peer offline and may close connections.

## Disconnection event (`t=disconnect`)

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

## Offer event (`t=offer`)

As soon as a peer is discovered, other peers MAY attempt to connect by sending an offer. The offer contains the WebRTC SDP offer and connection metadata in encrypted form.

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

**roomproof challenge** for this event is the JSON stringified array:

`[<receiver pubkey>, <nip44 encrypted offer>]`

## Answer event (`t=answer`)

Responds to an offer with the WebRTC SDP answer to establish the connection.

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

**roomproof challenge** for this event is the JSON stringified array:

`[<receiver pubkey>, <nip44 encrypted answer>]`

## Route event (`t=route`)

Peers MAY exchange routes/candidates before and after an offer/answer exchange (trickle ICE).

```yaml
{
  "kind": 25050,
  "content": nip44encrypted(JSON.stringify(
    {
      "candidates": [
        { "candidate": "<ice candidate>", "sdpMid": "<sdpMid>" }
        // ... more candidates if applicable
      ],
      "turn": "<optional NIP-DC TURN server URL more on that below>"
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

**roomproof challenge** for this event is the JSON stringified array:

`[<receiver pubkey>, <nip44 encrypted route>]`

---

# Data Channels over TURN server with Nostr Signatures (fallback)

This section specifies a protocol to relay **encrypted binary payloads** over a secure WebSocket connection to a TURN relay server. Payloads remain end-to-end encrypted and opaque to the server.

This is generally the fallback path, as WebRTC direct connections should be preferred when possible. Some apps may choose to prefer this as the primary transport; in that case the connection can be established only if both peers advertise a TURN server URL in their `route` events. Both peers do not need to use the same TURN server, so a bidirectional connection may have one server as receiver and another as sender.

## Transport and multiplexing

All communications happen over a secure WebSocket connection between client and server. A client may multiplex multiple virtual sockets on one WebSocket.

A virtual socket is uniquely identified by:

`(roomPubkey, channelLabel, sessionId, protocolId, applicationId, clientPubkey, targetPubkey)`

After receiving the initial `challenge`, the client MAY create additional virtual sockets by sending additional `connect` events with different parameters, without needing to resolve a new `challenge` for each socket.

## Binary envelope

Every message is framed in a binary envelope with the following shape:

```text
VERSION            uint8                    // always 2
VSOCKET_ID         int64                    // big-endian
                                            // MUST be 0 for challenge
                                            // MUST be != 0 for all other events
HEADER_SIZE        uint16                   // MUST be > 0
HEADER_BYTES       uint8[HEADER_SIZE]       // signed nostr event JSON (UTF-8)
NUM_PAYLOADS       uint16
for (i=0; i<NUM_PAYLOADS; i++):
  PAYLOAD_SIZE_i   uint32
  PAYLOAD_i        uint8[PAYLOAD_SIZE_i]    // encrypted bytes
```

All integers are big-endian.

`vsocketId=0` is reserved and means no virtual socket exists yet. It is used only by `challenge`.
All `connect`, `ack`, `data`, and `disconnect` frames MUST carry `vsocketId != 0`.

## Header shape (kind 25051)

```yaml
{
  "kind": 25051,
  "content": <event specific payload>,
  "tags": [
    ["t", "<challenge|connect|ack|disconnect|data>"],
    ["r", "<optional redirect URL>"],
    ["P", "<roomPubkeyHex>"],
    ["d", "<sessionId>"],
    ["i", "<protocolId>"],
    ["y", "<applicationId>"],
    ["p", "<remote target peer pubkey>", "<channel label>"],
    ["enc", "nip44-v2", "<nip44 encrypted secret for symmetric encryption>"],
    ["nonce", "<nonce>", "<difficulty>"],
    ["roomproof", "<id>", "<sig>"],
    ["expiration", "<unix timestamp in seconds>"]
  ]
}
```
Not every tag is required in every message type; required tags depend on the message type (see below).



## Challenge (`t=challenge`)

`server -> client`

Sent immediately after the WebSocket connection opens. It communicates the PoW difficulty and provides a random challenge token.

Envelope rule: `VSOCKET_ID` MUST be `0`.

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "challenge"],
    # ["r", "<optional redirect URL>"],
    ["expiration", "<unix timestamp seconds>"]
  ],
  "content": JSON.stringify({
    "difficulty": 13,
    "challenge": "<random token>"
  })
}
```
if `r` is present and different from the current server URL, the client SHOULD disconnect and reconnect to the provided URL, restarting the handshake.

No payloads.

## Connect (`t=connect`)

`client -> server`

Requests creation of a virtual socket. The `connect` MUST include:

* PoW meeting the server’s difficulty,
* the `roomproof`,
* the copied challenge token in `content`,
* a client-generated `vsocketId` in `content` and in the envelope.

Envelope rule: `VSOCKET_ID` MUST be `!= 0`.
`content.vsocketId` MUST exactly match the envelope `VSOCKET_ID`.
The server MUST reject `connect` if `VSOCKET_ID == 0`, if `content.vsocketId` mismatches the envelope value, or if `VSOCKET_ID` collides with an already active socket on that websocket connection.

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "connect"],
    ["P", "<room pub key>"],
    ["d", "<sessionId>"],
    ["i", "<protocolId>"],
    ["y", "<applicationId>"],
    ["p", "<remote target peer pubkey>", "<channel label>"],
    ["nonce", "<nonce>", "<difficulty>"],
    ["roomproof", "<roomproof.id>", "<roomproof.sig>"]
  ],
  "content": JSON.stringify({
    "challenge": "<challenge token>",
    "vsocketId": "<int64 != 0>"
  })
}
```

No payloads.

The `roomproof` challenge for this event is the `challenge` string copied into `content`.

## Ack (`t=ack`)

`server -> client`

Confirms the `connect` was accepted.

Envelope rule: `VSOCKET_ID` MUST be the same non-zero value accepted from `connect`.

```yaml
{
  "kind": 25051,
  "tags": [
    ["t", "ack"]
  ],
  "content": ""
}
```

No payloads.

## Disconnect (`t=disconnect`)

`server <-> client`

Terminates a virtual socket.

Envelope rule: `VSOCKET_ID` MUST be `!= 0`.
The server MUST ignore `disconnect` messages carrying `VSOCKET_ID == 0`, unknown `VSOCKET_ID`, or stale `VSOCKET_ID`.

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

If the server closes due to offline queue limits/timeouts, it MUST use:

```json
{"reason":"peer unreachable","error":true}
```

## Data (`t=data`)

`client1 <-> server <-> client2`

Carries encrypted payload bytes. The server routes by envelope `VSOCKET_ID` and must not interpret payloads.

Envelope rule: `VSOCKET_ID` MUST be `!= 0`.
The server MUST ignore `data` messages carrying `VSOCKET_ID == 0`, unknown `VSOCKET_ID`, or stale `VSOCKET_ID`.

The payload is encrypted using a nip-44 like scheme, where:
- a 32-byte symmetric secret is used as conversation key
- the raw byte input is encrypted
- the output is not encoded in base 64

The 32-byte secret is randomly generated by the client (can be reused or generated per message). 
The secret is then converted to an hex string, encrypted with the regular NIP-44 and then included in the header with the `enc` tag:


```javascript
let secret = bytesToHex(randomBytes(32));
const conversationKey = deriveConversationKey(senderPrivKey, receiverPubKey);
const enc = ["enc", "nip44-v2", nip44Encrypt(secret, conversationKey)]; // < add this to header tag
```




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

Payload: one or more encrypted binary blobs.

Acceptance and offline queueing:

* The server processes `data` only for sockets that were previously accepted (sender received `ack`).
* When relaying to a reciprocal receiver socket, the server MUST rewrite the envelope `VSOCKET_ID` to the receiver socket id before forwarding the frame.
* If the target peer is offline, the server queues payloads.
* Queued payloads are delivered only after the target peer connects and completes a matching accepted `connect`.
* If the queue grows too large or the target does not connect within a server-defined timeframe, the server may `disconnect` with `reason="peer unreachable", error=true`.

Because payloads are opaque, replay protection must be implemented inside the encrypted payload by the application protocol (if needed).

The header may be cached by the sender for efficiency, as long as the sender reuses the same signed header event for subsequent `data` messages.


---

## Recommended connection strategy

A typical client behavior:

1. Use **WebRTC** (via relay signaling events) as the primary direct transport.
2. If direct connectivity fails or is interrupted at some point, transparently fall back to the TURN server using the TURN URL learned from routes.
