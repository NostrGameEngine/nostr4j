# Physical RTC connection management

A room retains logical sockets for discovered peer sessions, and attempts to maintain
up to `RTCSettings.getMaxDirectPeers()` usable physical neighbors. A logical socket
can deliver through routing while it has no direct RTC or TURN link. Discovery alone
allocates no physical connection capacity.

The ordinary default is 16 direct neighbors, with a supported range of 2 through 64.
Pending handshakes and reservations consume this capacity. An unavailable candidate
enters its own backoff so that other candidates can fill the available slots.

## Local discovery priorities

```java
import org.ngengine.nostr4j.rtc.DiscoveryPriority;

room.setDiscoveryPriority(peer -> immutablePriorities.getOrDefault(peer.getPubkey(), 0f));
```

`DiscoveryPriority.priorityOf(NostrRTCPeer)` returns a `float`:

- `null` passed to `setDiscoveryPriority` disables application priority ordering and
  preference-driven swaps. The ordinary overlay ordering and structural repair remain active.
- Finite values greater than or equal to zero admit a candidate. Higher values are preferred.
- Zero is a valid neutral preference.
- `Float.MAX_VALUE` is valid; it grants no pinning, additional ordinary slots or remote privileges.
- Finite negative values exclude physical connections only. Existing physical resources
  are retired, while the logical socket, channels, membership and verified routing
  metadata remain. This is different from a room ban or disconnect.
- NaN, infinity and callback exceptions retain that peer's last valid evaluation.
  Without a previous valid value the peer is skipped for that pass. Diagnostics are rate limited.

The callback runs outside transport and manager monitors, once per peer per evaluation
pass. Sorting uses captured results. It must be short, nonblocking and must not mutate
the room. Supply immutable captured data and call the setter when that data changes.
The setter works before `start()` and during operation, and schedules a coalesced
reevaluation of existing peers. Replacing an equivalent callback preserves handshakes,
backoff and connection age. Plans from an outdated callback epoch cannot commit.

The callback selects physical neighbors. It does not select application message
recipients or change the semantics of `send` and `broadcast`. Each endpoint uses its
own callback, and the two endpoints' scores need not agree.

## Retry and timing configuration

The following immutable settings have getters and corresponding `with...` methods:

| Getter | Default | Validation |
| --- | --- | --- |
| `getConnectionRetryInitialDelay()` | 250 ms | 1 ms through 1 day, no greater than maximum |
| `getConnectionRetryMaxDelay()` | 2 s | 1 ms through 1 day, at least initial delay |
| `getConnectionRetryMultiplier()` | 2 | Finite, greater than 1 |
| `getConnectionRetryJitter()` | 0.1 | Finite, 0 through 0.5 |
| `getMaxConcurrentConnectionAttempts()` | 4 | 1 through 64, effectively bounded by local K |
| `getConnectionMinimumLifetime()` | 30 s | 1 ms through 1 day |

Retry delay starts at the terminal failure, using monotonic time. A repeated failure
callback counts once. Discovery refreshes, equivalent priority updates and switching
between filling and probing do not reset it. A successful physical link starts the
backoff utility's cooldown; sustained success resets delay after that cooldown.
Jitter scales a delay by a factor in `[1-jitter, 1+jitter]`, capped at the maximum.
Submillisecond remainders are rounded up when deciding eligibility.

Outbound scanning and total starts are bounded per maintenance batch, with a batch
interval of at least `max(250 ms, roomLoopInterval)`. Ordinary pending attempts are
bounded by `min(K, maxConcurrentConnectionAttempts)`; one supplementary probe is shared
by both inbound and outbound admission. Eligible candidates are considered in fair
rounds, with a stable ordering diversified by local identity. An unavailable high
priority peer cannot monopolize all retries. Public-key identity cooldowns and a
bounded candidate directory also constrain session churn.

These delays are distinct from RTC timeouts. Existing `getP2pAttemptTimeout()` and
`getP2pGiveupTimeout()` continue to control transport setup. If a bidirectional TURN
configuration is available, one additional `p2pAttemptTimeout` period lets fallback
finish after the RTC watchdog. A briefly degraded established link has that same
bounded recovery interval. A usable TURN link is kept; it is not repeatedly torn down
to retry RTC. A routed circuit does not count as a successful physical neighbor.

## Local admission and replacement

Either endpoint can initiate an ordinary offer. Public-key ordering only resolves
simultaneous offers: the smaller public key keeps its outgoing attempt, while the
other endpoint transfers its existing reservation to the incoming offer. The slot
remains charged during transport cleanup. A recipient checks its own capacity and
policy and reserves a slot before creating an answer; it need not have selected the
same peer for its outbound scan. A full or excluded recipient can ignore an offer.
The sender advances through timeout, shared backoff and other eligible candidates.

Pending attempts retransmit their existing offer, answer and route at a bounded rate.
Retransmission does not allocate another transport or reset the deadline. Equivalent
incoming offers reuse the reservation and cached answer. Local attempt and native
transport generations invalidate obsolete callbacks and asynchronous signing.

Replacement keeps the old physical neighbor until the new candidate is usable and
current local policy still permits the change. A routed circuit is not evidence of
a working physical link. An offer, answer or successful relay publication alone is
also insufficient. RTC requires the connection and a native data channel to be ready.
TURN requires both local directional registrations and an authenticated route from
the peer for ordinary establishment. Server registration alone does not prove peer
reachability. Replacing a healthy neighbor through TURN additionally requires a
successful reliable delivery acknowledged by the peer, using existing TURN receipts.
Ordinary application traffic can use a reserved provisional link to obtain this
evidence; no new probe payload or handshake is sent. If no evidence arrives, the probe
expires and the old healthy link remains.

Immediately before local promotion, capacity, priority, victim identity, session,
minimum age and structural protection are rechecked. If an ordinary slot became
available, the probe takes it without evicting another peer. Otherwise a strictly
better optional candidate can replace the captured victim. Equal scores do not cause
preference swaps. A captured optional victim that loses physical readiness can be
retired in favor of a usable replacement during its recovery grace. Current structural
protection still applies. Failed, refused or obsolete probes preserve the old neighbor.
Resources remain charged until transport cleanup completes. Provisional links do not
forward third-party traffic or enter the published ordinary topology.

Preference swaps respect minimum connection age. Structural repair can displace an
optional link without waiting for that optimization cooldown. The common minimum
ring, selected repair links and bridges in the current mutually attested graph are
protected. With application priorities, full-mesh BACKBONE labels are reduced to the
necessary ring protection so that optional links remain replaceable. Structural links
consume K; failed structural candidates can yield unused slots to reachable alternatives.

Admission and replacement are best effort decisions made independently at each
endpoint. There is no bilateral swap commit or promise that the remote policy will
keep a newly established link. Remote rejection or later closure is handled through
bounded retry and topology convergence. Packets already in flight may be lost; existing
reliable delivery and end-to-end deduplication retain responsibility for retries.
A physical replacement emits transport changes, not a room membership departure.

## Wire compatibility

NIP-DC versions and wire formats remain dc3/dc4. Presence, offer, answer, route,
room proofs, routing frames and TURN receipts keep their existing formats. No new
capability, signaling command, attempt binding or reserved channel is introduced.
The library changes local selection, reservations and lifecycle behavior only.

Without a wire attempt identifier, an incoming signaling event from a prior retry
cannot always be correlated perfectly within the same remote session. Existing
signature, scope, presence, expiration and native SDP/ICE validation still apply.
Local generations protect callbacks and sends, but do not create a remote transaction
identifier. Retry tests and mixed deployments must account for delayed signaling.

Older peers retain their own admission and collision policies; unchanged wire formats
do not make them support the new local selection behavior. Validate the specific
older binary used in a mixed deployment.

Production source targets Java 11 and uses existing platform executor and transport
APIs. This feature introduces no JVM-only transport dependency.

## Read-only connection diagnostics

`room.getConnectionDiagnostics()` returns a bounded snapshot with:

- Ordinary K, occupied resources (including reservations and closing resources), usable
  established links, pending attempts, closing resources and supplementary probe use.
- Per-session identity, last valid priority, structural role and protection, lifecycle state, physical
  transport, physical readiness and routed readiness as separate values.
- Failure count, remaining retry delay, last outcome and the captured swap victim.
- The mutually attested routing graph and its local evaluation time, plus valid
  topology snapshots with issue time, expiration and revision for freshness inspection.

Snapshot lists and connection-state values are immutable. Peer references identify the
existing logical endpoints; their metadata follows the existing `NostrRTCPeer` API.
No private keys, SDP, ICE candidates or application payloads are included. Peer
metadata remains accessible through the existing peer API.

Maintaining K useful links is an objective constrained by reachable candidates and
independent remote acceptance, not a guarantee of K established links or global reachability.
