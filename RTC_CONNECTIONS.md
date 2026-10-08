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

## Admission and replacement

Negotiated peers can initiate regardless of public-key ordering. Simultaneous
intentions use the public-key tie-break before ICE starts. Intentions are retransmitted
with the same attempt ID at a bounded rate, until accepted, refused or timed out.
While a recipient is in its short per-identity backoff, it defers the intent without
starting ICE. The existing request retransmission resumes admission when eligible,
avoiding reciprocal BUSY/backoff loops. Capacity and policy refusals still return BUSY.

With full established ordinary capacity K, a replacement can reserve one additional
physical resource. Both endpoints enforce their own ordinary capacity, concurrent
attempt budget, callback, backoff and unique supplementary slot. An incoming request
can be accepted without already appearing in the recipient's outbound selection.
Capacity refusal is a normal retryable result, and does not ban or remove membership.

The previous neighbor remains until the new link has bidirectional physical readiness
and the remote endpoint confirms its admission decision. Direct readiness and commit
frames use an internal reliable ordered channel, which cannot route through another
peer. The provisional link carries no third-party forwarding and is not advertised
as a topology edge. Only committed usable ordinary neighbors are published, never
65 neighbors when K is 64.

At commit, the room checks the current policy, sessions, captured victim reservation,
structural protection and actual capacity. If an ordinary slot became available, the
probe takes it without evicting another peer. Otherwise a strictly better optional
candidate can replace the captured victim. Equal scores do not cause preference swaps.
If the captured optional victim loses physical readiness during the probe, a proved
replacement can retire that unusable resource at commit without waiting for its
recovery grace or choosing another victim. Current structural protection still applies.
Failed, refused or obsolete probes retire themselves and preserve the old neighbor.
Resources remain charged until local transport cleanup completes.

Preference swaps respect minimum connection age. Structural repair can displace an
optional link without waiting for that optimization cooldown. The common minimum
ring, selected repair links and bridges in the current mutually attested graph are
protected. With application priorities, the selector's full-mesh BACKBONE labels are
reduced to the necessary ring protection so that optional links remain replaceable.
Structural links consume K, and failed structural candidates can yield unused slots
to reachable alternatives while repair continues.

Admission is a bounded bilateral handshake, not an atomic global topology transaction.
A later failure, security revocation, membership change or local policy update can
still invalidate a connection. Attestations converge through the existing control
plane. Packets already in flight may be lost; existing reliable delivery and
end-to-end deduplication retain responsibility for retries. A physical replacement
emits transport changes and does not emit a room membership departure.

## Wire compatibility

NIP-DC versions remain dc3/dc4. An authenticated dc4 presence advertises the optional
`["link-admission", "1"]` capability. Only mutually capable endpoints use the new
`link` signals, encrypted commands `REQUEST`, `TURN_REQUEST`, `ACCEPT`, `TURN_ACCEPT`,
`BUSY` and `ABORT`, and the reserved `__nipdc_dc4_route/link-admission-v1` channel.
Direct channel frames are `READY`, `COMMIT`, `COMMITTED` and `FINAL`, bounded to
96 bytes and correlated by attempt ID. Fresh direct-only nonces are echoed during
commit to prove that both physical directions work, including when an earlier READY
frame is lost during channel startup. All new signaling binds source identity/scope, a 128-bit random attempt ID
and the recipient's session. Room proofs bind this context as well as encrypted content.

Peers without the capability keep the legacy offer/answer/route format and the
smaller-public-key offer initiator. They can fill ordinary slots subject to local
admission, but cannot be targets of supplementary probes. Asymmetric legacy selection
can still wait for the old initiator; there is no claim that an old implementation
supports negotiated independent admission. The library supports the legacy wire
fallback; use mixed deployment testing for the specific older binary being deployed.

Production source targets Java 11 and uses the existing platform executor and transport
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
remote admission, not a guarantee of K established links or global reachability.
