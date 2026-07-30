/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.routing;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.rtc.NostrRTCChannel;
import org.ngengine.nostr4j.rtc.delivery.AcknowledgedDeliveryTracker;
import org.ngengine.nostr4j.rtc.delivery.DeliveryRouteUnavailableException;
import org.ngengine.nostr4j.rtc.delivery.DeliveryTransportReplacedException;
import org.ngengine.nostr4j.rtc.delivery.RetryableDeliveryFailure;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastAck;
import org.ngengine.nostr4j.rtc.routing.crypto.EndToEndControlCodec;
import org.ngengine.nostr4j.rtc.routing.crypto.OnionRouteSetup;
import org.ngengine.nostr4j.rtc.routing.crypto.OnionStatelessControl;
import org.ngengine.nostr4j.rtc.routing.crypto.RoutePayloadCrypto;
import org.ngengine.nostr4j.rtc.routing.packet.EndToEndControlType;
import org.ngengine.nostr4j.rtc.routing.packet.RouteSetupEnvelope;
import org.ngengine.nostr4j.rtc.routing.packet.RoutedDataFrame;
import org.ngengine.nostr4j.rtc.routing.packet.RoutedFrameType;
import org.ngengine.nostr4j.rtc.routing.packet.StatelessControlEnvelope;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

/**
 * Integrated dc4 routed transport core. It owns route setup/circuit state and
 * receives only direct-neighbor traffic from its room adapter.
 */
public final class RoutedTransportEngine implements InternalRoutedTransport, Closeable {

    private static final Logger logger = Logger.getLogger(RoutedTransportEngine.class.getName());
    private static final Duration CIRCUIT_LIFETIME = Duration.ofSeconds(60);
    private static final Duration SETUP_TIMEOUT = Duration.ofSeconds(5);
    private static final long DELIVERY_ACK_TIMEOUT_MS = 12_000L;
    private static final int SETUP_MAGIC = 0x44433453;
    private static final int STATELESS_CONTROL_MAGIC = 0x44433443;

    private final NodeId localNode;
    private final NostrKeyPair localRoutingKeys;
    private final RoutedTransportContext context;
    private final WeightedRoutePlanner planner = new WeightedRoutePlanner();
    private final RouteCostModel routeCosts = RouteCostModel.DEFAULT;
    private final CircuitTable forwardingCircuits = new CircuitTable();
    private final DestinationCircuitTable destinationCircuits = new DestinationCircuitTable();
    private final OnionRouteSetup setupOnion = new OnionRouteSetup();
    private final OnionStatelessControl controlOnion = new OnionStatelessControl();
    private final EndToEndControlCodec controlCodec = new EndToEndControlCodec();
    private final RoutePayloadCrypto payloadCrypto;
    private final NeighborTrafficLimiter trafficLimiter;
    private final AsyncExecutor executor;
    private final AcknowledgedDeliveryTracker deliveryTracker;
    private final Map<CircuitKey, SourceCircuit> sourceCircuits = new HashMap<CircuitKey, SourceCircuit>();
    private final Map<CircuitKey, PendingSetup> pendingByDestination = new HashMap<CircuitKey, PendingSetup>();
    private final Map<CircuitId, PendingSetup> pendingBySetup = new HashMap<CircuitId, PendingSetup>();
    private final Map<CircuitId, RoutedAttempt> attemptsById = new HashMap<CircuitId, RoutedAttempt>();
    private final Map<Long, RoutedAttempt> attemptsByTrackerId = new HashMap<Long, RoutedAttempt>();
    private final Map<PayloadKey, CachedPayload> ciphertextCache = new java.util.LinkedHashMap<PayloadKey, CachedPayload>(
        16,
        0.75f,
        true
    );
    private final AtomicLong generatedDeliveryAcks = new AtomicLong();
    private final AtomicLong completedDeliveryAcks = new AtomicLong();
    private volatile boolean closed;
    private volatile Consumer<BroadcastAck> broadcastAckHandler;
    private volatile Function<ByteBuffer, AsyncTask<Boolean>> broadcastRepairHandler;

    public RoutedTransportEngine(NodeId localNode, NostrKeyPair localRoutingKeys, RoutedTransportContext context) {
        this(localNode, localRoutingKeys, context, DELIVERY_ACK_TIMEOUT_MS, new NeighborTrafficLimiter());
    }

    public RoutedTransportEngine(
        NodeId localNode,
        NostrKeyPair localRoutingKeys,
        RoutedTransportContext context,
        NeighborTrafficLimiter trafficLimiter
    ) {
        this(localNode, localRoutingKeys, context, DELIVERY_ACK_TIMEOUT_MS, trafficLimiter);
    }

    RoutedTransportEngine(
        NodeId localNode,
        NostrKeyPair localRoutingKeys,
        RoutedTransportContext context,
        long deliveryAckTimeoutMs
    ) {
        this(localNode, localRoutingKeys, context, deliveryAckTimeoutMs, new NeighborTrafficLimiter());
    }

    private RoutedTransportEngine(
        NodeId localNode,
        NostrKeyPair localRoutingKeys,
        RoutedTransportContext context,
        long deliveryAckTimeoutMs,
        NeighborTrafficLimiter trafficLimiter
    ) {
        this.localNode = Objects.requireNonNull(localNode);
        this.localRoutingKeys = Objects.requireNonNull(localRoutingKeys);
        this.context = Objects.requireNonNull(context);
        this.trafficLimiter = Objects.requireNonNull(trafficLimiter);
        this.payloadCrypto = new RoutePayloadCrypto(localRoutingKeys);
        this.executor = NGEUtils.getPlatform().newAsyncExecutor(RoutedTransportEngine.class);
        this.deliveryTracker =
            new AcknowledgedDeliveryTracker(
                "NIP-DC routed",
                deliveryAckTimeoutMs,
                AcknowledgedDeliveryTracker.DEFAULT_MAX_PENDING_DELIVERIES,
                executor,
                pending -> onDeliveryTimeout(pending.getAttemptId())
            );
    }

    @Override
    public boolean isRouteReady(NostrRTCChannel channel) {
        if (closed || InternalRoutingChannels.isReserved(channel.getName())) return false;
        NodeId destination = context.destinationFor(channel);
        return bestMultiHopRoute(destination, Instant.now()) != null;
    }

    @Override
    public boolean shouldUseRoute(NostrRTCChannel channel) {
        if (closed || InternalRoutingChannels.isReserved(channel.getName())) return false;
        RoutePath routed = bestMultiHopRoute(context.destinationFor(channel), Instant.now());
        if (routed == null) return false;
        if (!context.hasUsableDirectTurn(channel)) return true;
        return routed.getTotalCost() < routeCosts.edgeCost(TopologyTransport.TURN);
    }

    @Override
    public int maximumNormalFrameBytes(NostrRTCChannel channel) {
        return payloadCrypto.maximumNormalFrameBytes(channel.getName());
    }

    @Override
    public AsyncTask<Boolean> writeRouted(NostrRTCChannel channel, ByteBuffer normalFrame) {
        if (closed) return AsyncTask.failed(new IllegalStateException("Routed transport is closed"));
        Instant now = Instant.now();
        NodeId destination = context.destinationFor(channel);
        return sendRouted(destination, channel.getName(), profile(channel), normalFrame, now);
    }

    AsyncTask<Boolean> sendRouted(
        NodeId destination,
        String logicalChannel,
        RouteTransportProfile profile,
        ByteBuffer normalFrame,
        Instant now
    ) {
        RoutePath route = bestMultiHopRoute(destination, now);
        if (route == null) return AsyncTask.completed(Boolean.FALSE);
        NostrPublicKey destinationKey = routingPublicKey(destination, now);
        if (destinationKey == null) return AsyncTask.completed(Boolean.FALSE);
        RoutePayloadCrypto.EncryptedPayload encrypted = encryptedPayload(
            destination,
            destinationKey,
            logicalChannel,
            profile,
            normalFrame,
            now
        );
        return ensureCircuit(destination, profile, route, now)
            .compose(circuit -> {
                CircuitId attempt = CircuitId.random();
                RoutedDataFrame frame = new RoutedDataFrame(
                    RoutedFrameType.DATA,
                    encrypted.isAcknowledgementRequired() ? 1 : 0,
                    circuit.circuitId,
                    attempt,
                    expiryFor(profile, Instant.now()),
                    encrypted.getCiphertext()
                );
                if (!encrypted.isAcknowledgementRequired()) {
                    return context.sendToDirectNeighbor(
                        circuit.firstHop,
                        InternalRoutingChannels.data(profile),
                        profile,
                        frame.encode()
                    );
                }
                RoutedAttempt pending = newRoutedAttempt(attempt, encrypted, circuit, destination, profile);
                AsyncTask<Boolean> completion = pending.completion;
                context
                    .sendToDirectNeighbor(circuit.firstHop, InternalRoutingChannels.data(profile), profile, frame.encode())
                    .then(sent -> {
                        if (!Boolean.TRUE.equals(sent)) {
                            failAttempt(pending, new DeliveryTransportReplacedException("NIP-DC routed"));
                        }
                        return null;
                    })
                    .catchException(error -> failAttempt(pending, error));
                return completion;
            });
    }

    public AsyncTask<Boolean> onDirectControl(NodeId previousDirectPeer, ByteBuffer payload) {
        if (closed) return AsyncTask.completed(Boolean.FALSE);
        long receivedAtMs = System.currentTimeMillis();
        if (previousDirectPeer == null || payload == null) return AsyncTask.completed(Boolean.FALSE);
        NeighborTrafficLimiter.Admission admission = trafficLimiter.tryAcquire(
            previousDirectPeer,
            payload.remaining(),
            true,
            receivedAtMs
        );
        if (admission == null) return AsyncTask.completed(Boolean.FALSE);
        try (NeighborTrafficLimiter.Admission ignored = admission) {
            ByteBuffer type = payload.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            if (type.remaining() < Integer.BYTES) {
                trafficLimiter.recordMalformed(previousDirectPeer, receivedAtMs);
                return AsyncTask.completed(Boolean.FALSE);
            }
            int magic = type.getInt();
            if (magic == SETUP_MAGIC) {
                return receiveSetup(previousDirectPeer, RouteSetupEnvelope.decode(payload, Instant.now()), Instant.now());
            }
            if (magic == STATELESS_CONTROL_MAGIC) {
                return receiveStatelessControl(
                    previousDirectPeer,
                    StatelessControlEnvelope.decode(payload, Instant.now()),
                    Instant.now()
                );
            }
            trafficLimiter.recordMalformed(previousDirectPeer, receivedAtMs);
        } catch (Throwable error) {
            trafficLimiter.recordMalformed(previousDirectPeer, receivedAtMs);
            logger.log(Level.FINE, "Rejected routed control packet", error);
        }
        return AsyncTask.completed(Boolean.FALSE);
    }

    public AsyncTask<Boolean> onDirectData(NodeId previousDirectPeer, ByteBuffer payload) {
        if (closed) return AsyncTask.completed(Boolean.FALSE);
        long receivedAtMs = System.currentTimeMillis();
        if (previousDirectPeer == null || payload == null) return AsyncTask.completed(Boolean.FALSE);
        NeighborTrafficLimiter.Admission admission = trafficLimiter.tryAcquire(
            previousDirectPeer,
            payload.remaining(),
            false,
            receivedAtMs
        );
        if (admission == null) return AsyncTask.completed(Boolean.FALSE);
        Instant now = Instant.now();
        final RoutedDataFrame frame;
        try (NeighborTrafficLimiter.Admission ignored = admission) {
            frame = RoutedDataFrame.decode(payload, now);
        } catch (Throwable error) {
            trafficLimiter.recordMalformed(previousDirectPeer, receivedAtMs);
            logger.log(Level.FINE, "Rejected routed data frame", error);
            return AsyncTask.completed(Boolean.FALSE);
        }
        CircuitForwardingEntry forwarding = forwardingCircuits.find(previousDirectPeer, frame.getCircuitId(), now);
        if (forwarding != null) {
            return context.sendToDirectNeighbor(
                forwarding.getNextDirectPeer(),
                InternalRoutingChannels.data(forwarding.getProfile()),
                forwarding.getProfile(),
                frame.forwardingView()
            );
        }
        DestinationCircuitTable.Entry destination = destinationCircuits.find(previousDirectPeer, frame.getCircuitId(), now);
        if (destination == null || frame.getType() != RoutedFrameType.DATA) {
            if (destination != null && frame.getType() == RoutedFrameType.BROADCAST && broadcastRepairHandler != null) {
                return broadcastRepairHandler.apply(frame.getCiphertext());
            }
            return AsyncTask.completed(Boolean.FALSE);
        }
        NostrPublicKey sourceKey = routingPublicKey(destination.getSource(), now);
        if (sourceKey == null) return AsyncTask.completed(Boolean.FALSE);
        try {
            RoutePayloadCrypto.DecryptedPayload decrypted = payloadCrypto.decrypt(
                destination.getSource(),
                localNode,
                sourceKey,
                frame.getCiphertext()
            );
            RouteTransportProfile installedProfile = destination.getProfile();
            if (
                decrypted.isOrdered() != installedProfile.isOrdered() ||
                decrypted.isReliable() != installedProfile.isReliable() ||
                decrypted.isAcknowledgementRequired() != ((frame.getFlags() & 1) != 0) ||
                InternalRoutingChannels.isReserved(decrypted.getLogicalChannel())
            ) {
                trafficLimiter.recordMalformed(previousDirectPeer, receivedAtMs);
                return AsyncTask.completed(Boolean.FALSE);
            }
            boolean accepted = context.deliverNormalFrame(
                decrypted.getSource(),
                decrypted.getLogicalChannel(),
                decrypted.getNormalFrame()
            );
            if (accepted && decrypted.isAcknowledgementRequired()) {
                return sendDeliveryAcknowledgement(decrypted, frame.getAttemptId(), frame.getCircuitId(), now);
            }
            return AsyncTask.completed(Boolean.valueOf(accepted));
        } catch (Throwable error) {
            trafficLimiter.recordMalformed(previousDirectPeer, receivedAtMs);
            logger.log(Level.FINE, "Rejected routed end-to-end payload", error);
            return AsyncTask.completed(Boolean.FALSE);
        }
    }

    private AsyncTask<Boolean> receiveSetup(NodeId previousDirectPeer, RouteSetupEnvelope envelope, Instant now) {
        OnionRouteSetup.PeeledSetup peeled = setupOnion.peel(envelope, localRoutingKeys.getPrivateKey(), now);
        if (!peeled.isDestination()) {
            forwardingCircuits.install(
                previousDirectPeer,
                peeled.getCircuitId(),
                peeled.getNextHop(),
                peeled.getProfile(),
                peeled.getExpiresAt(),
                now
            );
            return context.sendToDirectNeighbor(
                peeled.getNextHop(),
                InternalRoutingChannels.CONTROL,
                RouteTransportProfile.RELIABLE_ORDERED,
                peeled.getForwardedEnvelope().encode()
            );
        }
        destinationCircuits.install(
            previousDirectPeer,
            peeled.getCircuitId(),
            peeled.getSource(),
            peeled.getProfile(),
            peeled.getExpiresAt(),
            now
        );
        return sendSetupConfirmation(peeled.getSource(), peeled.getSetupId(), peeled.getCircuitId(), now);
    }

    private AsyncTask<Boolean> sendSetupConfirmation(NodeId source, CircuitId setupId, CircuitId circuitId, Instant now) {
        RoutePath route = bestAnyRoute(source, now);
        NostrPublicKey sourceKey = routingPublicKey(source, now);
        if (route == null || sourceKey == null) return AsyncTask.completed(Boolean.FALSE);
        EndToEndControlCodec.Message confirmation = new EndToEndControlCodec.Message(
            EndToEndControlType.SETUP_CONFIRMED,
            localNode,
            source,
            setupId,
            circuitId,
            0L,
            0,
            "",
            new byte[16]
        );
        ByteBuffer protectedMessage = controlCodec.encrypt(confirmation, localRoutingKeys.getPrivateKey(), sourceKey);
        StatelessControlEnvelope onion = controlOnion.build(
            route.getNodes(),
            routingPublicKeys(now),
            protectedMessage,
            now.plusSeconds(10),
            now
        );
        return context.sendToDirectNeighbor(
            route.getNodes().get(1),
            InternalRoutingChannels.CONTROL,
            RouteTransportProfile.RELIABLE_ORDERED,
            onion.encode()
        );
    }

    private AsyncTask<Boolean> receiveStatelessControl(
        NodeId previousDirectPeer,
        StatelessControlEnvelope envelope,
        Instant now
    ) {
        OnionStatelessControl.PeeledControl peeled = controlOnion.peel(envelope, localRoutingKeys.getPrivateKey(), now);
        if (!peeled.isDestination()) {
            return context.sendToDirectNeighbor(
                peeled.getNextHop(),
                InternalRoutingChannels.CONTROL,
                RouteTransportProfile.RELIABLE_ORDERED,
                peeled.getForwardedEnvelope().encode()
            );
        }
        NostrPublicKey originKey = routingPublicKey(peeled.getOrigin(), now);
        if (originKey == null) return AsyncTask.completed(Boolean.FALSE);
        EndToEndControlCodec.Message message = controlCodec.decrypt(
            peeled.getFinalPayload(),
            peeled.getOrigin(),
            localNode,
            localRoutingKeys.getPrivateKey(),
            originKey
        );
        if (message.getType() == EndToEndControlType.SETUP_CONFIRMED) {
            return AsyncTask.completed(Boolean.valueOf(confirmSetup(message)));
        }
        if (message.getType() == EndToEndControlType.DELIVERY_ACK) {
            return AsyncTask.completed(Boolean.valueOf(completeDelivery(message)));
        }
        if (message.getType() == EndToEndControlType.BROADCAST_ACK) {
            Consumer<BroadcastAck> handler = broadcastAckHandler;
            if (handler == null) return AsyncTask.completed(Boolean.FALSE);
            handler.accept(
                new BroadcastAck(localNode, message.getSender(), message.getCorrelationId(), message.getLogicalChannel())
            );
            return AsyncTask.completed(Boolean.TRUE);
        }
        return AsyncTask.completed(Boolean.FALSE);
    }

    public void setBroadcastHandlers(
        Consumer<BroadcastAck> ackHandler,
        Function<ByteBuffer, AsyncTask<Boolean>> repairHandler
    ) {
        this.broadcastAckHandler = ackHandler;
        this.broadcastRepairHandler = repairHandler;
    }

    public AsyncTask<Boolean> sendBroadcastAck(BroadcastAck ack, Instant now) {
        if (!localNode.equals(ack.getResponder())) {
            return AsyncTask.failed(new IllegalArgumentException("Broadcast ACK responder mismatch"));
        }
        RoutePath route = bestAnyRoute(ack.getOrigin(), now);
        NostrPublicKey originKey = routingPublicKey(ack.getOrigin(), now);
        if (route == null || originKey == null) return AsyncTask.completed(Boolean.FALSE);
        EndToEndControlCodec.Message message = new EndToEndControlCodec.Message(
            EndToEndControlType.BROADCAST_ACK,
            localNode,
            ack.getOrigin(),
            ack.getBroadcastId(),
            CircuitId.fromBytes(new byte[CircuitId.SIZE]),
            0L,
            0,
            ack.getLogicalChannel(),
            new byte[16]
        );
        ByteBuffer protectedAck = controlCodec.encrypt(message, localRoutingKeys.getPrivateKey(), originKey);
        StatelessControlEnvelope onion = controlOnion.build(
            route.getNodes(),
            routingPublicKeys(now),
            protectedAck,
            now.plusSeconds(10),
            now
        );
        return context.sendToDirectNeighbor(
            route.getNodes().get(1),
            InternalRoutingChannels.CONTROL,
            RouteTransportProfile.RELIABLE_ORDERED,
            onion.encode()
        );
    }

    public AsyncTask<Boolean> sendBroadcastRepair(NodeId target, ByteBuffer encodedBroadcast, Instant now) {
        RoutePath route = bestAnyRoute(target, now);
        if (route == null) return AsyncTask.completed(Boolean.FALSE);
        RouteTransportProfile profile = RouteTransportProfile.RELIABLE_ORDERED;
        return ensureCircuit(target, profile, route, now)
            .compose(circuit -> {
                RoutedDataFrame frame = new RoutedDataFrame(
                    RoutedFrameType.BROADCAST,
                    0,
                    circuit.circuitId,
                    CircuitId.random(),
                    now.plusSeconds(30),
                    encodedBroadcast.asReadOnlyBuffer()
                );
                return context.sendToDirectNeighbor(
                    circuit.firstHop,
                    InternalRoutingChannels.data(profile),
                    profile,
                    frame.encode()
                );
            });
    }

    private AsyncTask<Boolean> sendDeliveryAcknowledgement(
        RoutePayloadCrypto.DecryptedPayload delivered,
        CircuitId attemptId,
        CircuitId circuitId,
        Instant now
    ) {
        generatedDeliveryAcks.incrementAndGet();
        RoutePath route = bestAnyRoute(delivered.getSource(), now);
        NostrPublicKey sourceKey = routingPublicKey(delivered.getSource(), now);
        if (route == null || sourceKey == null) return AsyncTask.completed(Boolean.FALSE);
        EndToEndControlCodec.Message ack = new EndToEndControlCodec.Message(
            EndToEndControlType.DELIVERY_ACK,
            localNode,
            delivered.getSource(),
            attemptId,
            circuitId,
            delivered.getPacketId(),
            delivered.getFragmentId(),
            delivered.getLogicalChannel(),
            delivered.getAcknowledgementToken()
        );
        ByteBuffer protectedAck = controlCodec.encrypt(ack, localRoutingKeys.getPrivateKey(), sourceKey);
        StatelessControlEnvelope onion = controlOnion.build(
            route.getNodes(),
            routingPublicKeys(now),
            protectedAck,
            now.plusSeconds(10),
            now
        );
        return context.sendToDirectNeighbor(
            route.getNodes().get(1),
            InternalRoutingChannels.CONTROL,
            RouteTransportProfile.RELIABLE_ORDERED,
            onion.encode()
        );
    }

    private boolean completeDelivery(EndToEndControlCodec.Message ack) {
        RoutedAttempt attempt;
        synchronized (this) {
            attempt = attemptsById.get(ack.getCorrelationId());
            if (
                attempt == null ||
                !attempt.circuit.circuitId.equals(ack.getCircuitId()) ||
                attempt.encrypted.getPacketId() != ack.getPacketId() ||
                attempt.encrypted.getFragmentId() != ack.getFragmentId() ||
                !attempt.encrypted.getLogicalChannel().equals(ack.getLogicalChannel()) ||
                !constantTimeEquals(attempt.encrypted.getAcknowledgementToken(), ack.getToken())
            ) {
                return false;
            }
            attemptsById.remove(ack.getCorrelationId());
            attemptsByTrackerId.remove(Long.valueOf(attempt.trackerId));
        }
        boolean completed = deliveryTracker.complete(attempt.trackerId) != null;
        if (completed) completedDeliveryAcks.incrementAndGet();
        return completed;
    }

    private RoutedAttempt newRoutedAttempt(
        CircuitId attemptId,
        RoutePayloadCrypto.EncryptedPayload encrypted,
        SourceCircuit circuit,
        NodeId destination,
        RouteTransportProfile profile
    ) {
        final long trackerId;
        synchronized (this) {
            trackerId = uniqueTrackerId(attemptId);
        }
        RoutedAttempt attempt = new RoutedAttempt(
            attemptId,
            trackerId,
            encrypted,
            circuit,
            new CircuitKey(destination, profile)
        );
        synchronized (this) {
            attemptsById.put(attemptId, attempt);
            attemptsByTrackerId.put(Long.valueOf(trackerId), attempt);
        }
        try {
            deliveryTracker.register(trackerId, Long.valueOf(encrypted.getPacketId()), attempt::resolve, attempt::reject);
        } catch (RuntimeException error) {
            synchronized (this) {
                attemptsById.remove(attemptId);
                attemptsByTrackerId.remove(Long.valueOf(trackerId));
            }
            throw error;
        }
        return attempt;
    }

    private void onDeliveryTimeout(long trackerId) {
        RoutedAttempt attempt;
        synchronized (this) {
            attempt = attemptsByTrackerId.remove(Long.valueOf(trackerId));
            if (attempt == null) return;
            attemptsById.remove(attempt.attemptId);
            SourceCircuit current = sourceCircuits.get(attempt.circuitKey);
            if (current != null && current.circuitId.equals(attempt.circuit.circuitId)) {
                sourceCircuits.remove(attempt.circuitKey);
            }
        }
        planner.recordFailure(attempt.circuit.route, Instant.now());
        context.routingStateChanged();
    }

    private void failAttempt(RoutedAttempt attempt, Throwable error) {
        synchronized (this) {
            attemptsById.remove(attempt.attemptId);
            attemptsByTrackerId.remove(Long.valueOf(attempt.trackerId));
        }
        deliveryTracker.fail(
            attempt.trackerId,
            error instanceof org.ngengine.nostr4j.rtc.delivery.RetryableDeliveryFailure
                ? error
                : new DeliveryTransportReplacedException("NIP-DC routed")
        );
    }

    private RoutePayloadCrypto.EncryptedPayload encryptedPayload(
        NodeId destination,
        NostrPublicKey destinationKey,
        String logicalChannel,
        RouteTransportProfile profile,
        ByteBuffer normalFrame,
        Instant now
    ) {
        FragmentKey fragment = fragmentKey(normalFrame);
        PayloadKey key = new PayloadKey(
            destination,
            destinationKey,
            logicalChannel,
            fragment.packetId,
            fragment.fragmentId,
            fragment.fragmentCount,
            profile
        );
        synchronized (this) {
            pruneCiphertextCache(now);
            CachedPayload cached = ciphertextCache.get(key);
            if (cached != null) {
                cached.attempts++;
                enforcePartialReliability(cached, profile, now);
                return cached.encrypted;
            }
        }
        RoutePayloadCrypto.EncryptedPayload encrypted = payloadCrypto.encrypt(
            localNode,
            destination,
            destinationKey,
            logicalChannel,
            profile,
            normalFrame,
            null
        );
        synchronized (this) {
            CachedPayload cached = new CachedPayload(encrypted, now, expiryFor(profile, now));
            ciphertextCache.put(key, cached);
            while (ciphertextCache.size() > RoutingLimits.MAX_DEDUP_ENTRIES) {
                PayloadKey eldest = ciphertextCache.keySet().iterator().next();
                ciphertextCache.remove(eldest);
            }
        }
        return encrypted;
    }

    private static void enforcePartialReliability(CachedPayload cached, RouteTransportProfile profile, Instant now) {
        if (!cached.expiresAt.isAfter(now)) {
            throw new IllegalStateException("Routed packet lifetime exhausted");
        }
        Integer maxRetransmits = profile.getMaxRetransmits();
        if (!profile.isReliable() && maxRetransmits != null && cached.attempts > maxRetransmits.intValue() + 1) {
            throw new IllegalStateException("Routed packet retransmit limit exhausted");
        }
    }

    private void pruneCiphertextCache(Instant now) {
        ciphertextCache.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    private long uniqueTrackerId(CircuitId attemptId) {
        byte[] bytes = attemptId.toByteArray();
        long candidate = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong();
        if (candidate == 0L) candidate = 1L;
        while (attemptsByTrackerId.containsKey(Long.valueOf(candidate))) {
            candidate = candidate == Long.MAX_VALUE ? 1L : candidate + 1L;
        }
        return candidate;
    }

    private static FragmentKey fragmentKey(ByteBuffer normalFrame) {
        ByteBuffer data = normalFrame.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
        if (data.remaining() < 12) throw new IllegalArgumentException("Truncated normal NIP-DC frame");
        long packetId = data.getLong();
        int fragmentId = data.getShort();
        int fragmentCount = data.getShort();
        if (packetId <= 0 || fragmentCount <= 0 || fragmentId < 0 || fragmentId >= fragmentCount) {
            throw new IllegalArgumentException("Invalid normal NIP-DC fragment");
        }
        return new FragmentKey(packetId, fragmentId, fragmentCount);
    }

    private static boolean constantTimeEquals(byte[] first, byte[] second) {
        if (first.length != second.length) return false;
        int difference = 0;
        for (int index = 0; index < first.length; index++) difference |= first[index] ^ second[index];
        return difference == 0;
    }

    long getPayloadEncryptionCount() {
        return payloadCrypto.getEncryptionCount();
    }

    long getGeneratedDeliveryAckCount() {
        return generatedDeliveryAcks.get();
    }

    long getCompletedDeliveryAckCount() {
        return completedDeliveryAcks.get();
    }

    int getPendingAcknowledgedDeliveryCount() {
        return deliveryTracker.size();
    }

    private boolean confirmSetup(EndToEndControlCodec.Message confirmation) {
        PendingSetup pending;
        synchronized (this) {
            pending = pendingBySetup.get(confirmation.getCorrelationId());
            if (pending == null || !pending.circuit.circuitId.equals(confirmation.getCircuitId())) {
                return false;
            }
            pendingBySetup.remove(confirmation.getCorrelationId());
            pendingByDestination.remove(pending.key);
            sourceCircuits.put(pending.key, pending.circuit);
        }
        pending.resolve(pending.circuit);
        context.routingStateChanged();
        return true;
    }

    private AsyncTask<SourceCircuit> ensureCircuit(
        NodeId destination,
        RouteTransportProfile profile,
        RoutePath route,
        Instant now
    ) {
        CircuitKey key = new CircuitKey(destination, profile);
        synchronized (this) {
            pruneSourceCircuits(now);
            SourceCircuit existing = sourceCircuits.get(key);
            if (existing != null && existing.expiresAt.isAfter(now.plusSeconds(2))) {
                return AsyncTask.completed(existing);
            }
            sourceCircuits.remove(key);
            PendingSetup existingPending = pendingByDestination.get(key);
            if (existingPending != null) return existingPending.task;
        }
        Map<NodeId, NostrPublicKey> publicKeys = routingPublicKeys(now);
        OnionRouteSetup.BuiltSetup setup = setupOnion.build(
            route.getNodes(),
            publicKeys,
            profile,
            now.plus(CIRCUIT_LIFETIME),
            now
        );
        SourceCircuit circuit = new SourceCircuit(
            setup.getCircuitId(),
            route.getNodes().get(1),
            now.plus(CIRCUIT_LIFETIME),
            route
        );
        PendingSetup pending = new PendingSetup(key, setup.getSetupId(), circuit);
        synchronized (this) {
            pruneSourceCircuits(now);
            SourceCircuit racedCircuit = sourceCircuits.get(key);
            if (racedCircuit != null && racedCircuit.expiresAt.isAfter(now.plusSeconds(2))) {
                return AsyncTask.completed(racedCircuit);
            }
            PendingSetup racedPending = pendingByDestination.get(key);
            if (racedPending != null) return racedPending.task;
            if (sourceCircuits.size() + pendingBySetup.size() >= RoutingLimits.MAX_ACTIVE_CIRCUITS) {
                return AsyncTask.failed(new DeliveryRouteUnavailableException("Pending routed circuit setup limit exceeded"));
            }
            pendingByDestination.put(key, pending);
            pendingBySetup.put(setup.getSetupId(), pending);
        }
        pending.timeout =
            executor.runLater(
                () -> {
                    failPendingSetup(pending, new DeliveryRouteUnavailableException("Routed circuit setup timed out"));
                    return null;
                },
                SETUP_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
            );
        context
            .sendToDirectNeighbor(
                circuit.firstHop,
                InternalRoutingChannels.CONTROL,
                RouteTransportProfile.RELIABLE_ORDERED,
                setup.getEnvelope().encode()
            )
            .then(ok -> {
                if (!Boolean.TRUE.equals(ok)) {
                    failPendingSetup(pending, new DeliveryRouteUnavailableException("Failed to send routed circuit setup"));
                }
                return null;
            })
            .catchException(error -> failPendingSetup(pending, error));
        return pending.task;
    }

    private void pruneSourceCircuits(Instant now) {
        sourceCircuits.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    private void failPendingSetup(PendingSetup pending, Throwable error) {
        synchronized (this) {
            if (pendingBySetup.remove(pending.setupId) == null) return;
            pendingByDestination.remove(pending.key);
        }
        pending.reject(
            error instanceof RetryableDeliveryFailure
                ? error
                : new DeliveryRouteUnavailableException("Routed circuit setup failed", error)
        );
    }

    private RoutePath bestMultiHopRoute(NodeId destination, Instant now) {
        for (RoutePath route : planner.plan(context.currentGraph(), localNode, destination, now)) {
            if (route.getHopCount() >= 2 && hasRoutingKeys(route, now)) return route;
        }
        return null;
    }

    private RoutePath bestAnyRoute(NodeId destination, Instant now) {
        for (RoutePath route : planner.plan(context.currentGraph(), localNode, destination, now)) {
            if (hasRoutingKeys(route, now)) return route;
        }
        return null;
    }

    private boolean hasRoutingKeys(RoutePath route, Instant now) {
        Map<NodeId, NostrPublicKey> keys = routingPublicKeys(now);
        for (int index = 1; index < route.getNodes().size(); index++) {
            if (!keys.containsKey(route.getNodes().get(index))) return false;
        }
        return true;
    }

    private NostrPublicKey routingPublicKey(NodeId node, Instant now) {
        return routingPublicKeys(now).get(node);
    }

    private Map<NodeId, NostrPublicKey> routingPublicKeys(Instant now) {
        Map<NodeId, NostrPublicKey> keys = new HashMap<NodeId, NostrPublicKey>();
        keys.put(localNode, localRoutingKeys.getPublicKey());
        for (TopologySnapshot snapshot : context.topologySnapshots(now)) {
            if (!snapshot.isExpired(now)) keys.put(snapshot.getNodeId(), snapshot.getRoutingPublicKey());
        }
        return keys;
    }

    private static RouteTransportProfile profile(NostrRTCChannel channel) {
        return RouteTransportProfile.fromChannel(
            channel.isOrdered(),
            channel.isReliable(),
            channel.getMaxRetransmits(),
            channel.getMaxPacketLifeTime()
        );
    }

    private static Instant expiryFor(RouteTransportProfile profile, Instant now) {
        Duration lifetime = profile.getMaxPacketLifeTime();
        if (lifetime == null || lifetime.compareTo(CIRCUIT_LIFETIME) > 0) lifetime = CIRCUIT_LIFETIME;
        return now.plus(lifetime);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<PendingSetup> pending;
        synchronized (this) {
            pending = new ArrayList<PendingSetup>(pendingBySetup.values());
            pendingBySetup.clear();
            pendingByDestination.clear();
            sourceCircuits.clear();
        }
        for (PendingSetup setup : pending) setup.reject(new IllegalStateException("Routed transport closed"));
        deliveryTracker.close();
        synchronized (this) {
            attemptsById.clear();
            attemptsByTrackerId.clear();
            ciphertextCache.clear();
        }
        forwardingCircuits.close();
        destinationCircuits.close();
        payloadCrypto.close();
        trafficLimiter.close();
        broadcastAckHandler = null;
        broadcastRepairHandler = null;
        executor.close();
    }

    private static final class CircuitKey {

        private final NodeId destination;
        private final RouteTransportProfile profile;

        private CircuitKey(NodeId destination, RouteTransportProfile profile) {
            this.destination = destination;
            this.profile = profile;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CircuitKey)) return false;
            CircuitKey that = (CircuitKey) other;
            return destination.equals(that.destination) && profile.equals(that.profile);
        }

        @Override
        public int hashCode() {
            return Objects.hash(destination, profile);
        }
    }

    private static final class SourceCircuit {

        private final CircuitId circuitId;
        private final NodeId firstHop;
        private final Instant expiresAt;
        private final RoutePath route;

        private SourceCircuit(CircuitId circuitId, NodeId firstHop, Instant expiresAt, RoutePath route) {
            this.circuitId = circuitId;
            this.firstHop = firstHop;
            this.expiresAt = expiresAt;
            this.route = route;
        }
    }

    private static final class RoutedAttempt {

        private final CircuitId attemptId;
        private final long trackerId;
        private final RoutePayloadCrypto.EncryptedPayload encrypted;
        private final SourceCircuit circuit;
        private final CircuitKey circuitKey;
        private final AsyncTask<Boolean> completion;
        private Consumer<Boolean> resolver;
        private Consumer<Throwable> rejecter;
        private Boolean earlyResolution;
        private Throwable earlyRejection;

        private RoutedAttempt(
            CircuitId attemptId,
            long trackerId,
            RoutePayloadCrypto.EncryptedPayload encrypted,
            SourceCircuit circuit,
            CircuitKey circuitKey
        ) {
            this.attemptId = attemptId;
            this.trackerId = trackerId;
            this.encrypted = encrypted;
            this.circuit = circuit;
            this.circuitKey = circuitKey;
            this.completion =
                NGEPlatform
                    .get()
                    .wrapPromise((resolve, reject) -> {
                        synchronized (this) {
                            resolver = resolve;
                            rejecter = reject;
                            if (earlyResolution != null) resolve.accept(earlyResolution);
                            if (earlyRejection != null) reject.accept(earlyRejection);
                        }
                    });
        }

        private synchronized void resolve(Boolean value) {
            if (resolver != null) resolver.accept(value); else earlyResolution = value;
        }

        private synchronized void reject(Throwable error) {
            if (rejecter != null) rejecter.accept(error); else earlyRejection = error;
        }
    }

    private static final class PayloadKey {

        private final NodeId destination;
        private final NostrPublicKey destinationKey;
        private final String channel;
        private final long packetId;
        private final int fragmentId;
        private final int fragmentCount;
        private final RouteTransportProfile profile;

        private PayloadKey(
            NodeId destination,
            NostrPublicKey destinationKey,
            String channel,
            long packetId,
            int fragmentId,
            int fragmentCount,
            RouteTransportProfile profile
        ) {
            this.destination = destination;
            this.destinationKey = destinationKey;
            this.channel = channel;
            this.packetId = packetId;
            this.fragmentId = fragmentId;
            this.fragmentCount = fragmentCount;
            this.profile = profile;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PayloadKey)) return false;
            PayloadKey that = (PayloadKey) other;
            return (
                packetId == that.packetId &&
                fragmentId == that.fragmentId &&
                fragmentCount == that.fragmentCount &&
                destination.equals(that.destination) &&
                destinationKey.equals(that.destinationKey) &&
                channel.equals(that.channel) &&
                profile.equals(that.profile)
            );
        }

        @Override
        public int hashCode() {
            return Objects.hash(destination, destinationKey, channel, packetId, fragmentId, fragmentCount, profile);
        }
    }

    private static final class CachedPayload {

        private final RoutePayloadCrypto.EncryptedPayload encrypted;
        private final Instant createdAt;
        private final Instant expiresAt;
        private int attempts = 1;

        private CachedPayload(RoutePayloadCrypto.EncryptedPayload encrypted, Instant createdAt, Instant expiresAt) {
            this.encrypted = encrypted;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    private static final class FragmentKey {

        private final long packetId;
        private final int fragmentId;
        private final int fragmentCount;

        private FragmentKey(long packetId, int fragmentId, int fragmentCount) {
            this.packetId = packetId;
            this.fragmentId = fragmentId;
            this.fragmentCount = fragmentCount;
        }
    }

    private static final class PendingSetup {

        private final CircuitKey key;
        private final CircuitId setupId;
        private final SourceCircuit circuit;
        private final AsyncTask<SourceCircuit> task;
        private Consumer<SourceCircuit> resolver;
        private Consumer<Throwable> rejecter;
        private AsyncTask<Void> timeout;
        private SourceCircuit earlyResolution;
        private Throwable earlyRejection;
        private boolean settled;

        private PendingSetup(CircuitKey key, CircuitId setupId, SourceCircuit circuit) {
            this.key = key;
            this.setupId = setupId;
            this.circuit = circuit;
            this.task =
                NGEPlatform
                    .get()
                    .wrapPromise((resolve, reject) -> {
                        synchronized (this) {
                            resolver = resolve;
                            rejecter = reject;
                            if (earlyResolution != null) resolve.accept(earlyResolution);
                            if (earlyRejection != null) reject.accept(earlyRejection);
                        }
                    });
        }

        private synchronized void resolve(SourceCircuit value) {
            if (settled) return;
            settled = true;
            if (timeout != null) timeout.cancel();
            if (resolver != null) resolver.accept(value); else earlyResolution = value;
        }

        private synchronized void reject(Throwable error) {
            if (settled) return;
            settled = true;
            if (timeout != null) timeout.cancel();
            if (rejecter != null) rejecter.accept(error); else earlyRejection = error;
        }
    }
}
