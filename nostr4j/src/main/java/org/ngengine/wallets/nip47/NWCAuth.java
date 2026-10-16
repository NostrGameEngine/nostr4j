package org.ngengine.wallets.nip47;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/**
 * One-use, client-initiated NWC-08 pairing. The private key never enters the QR.
 */
public final class NWCAuth implements AutoCloseable {

    private final NostrKeyPair keys = new NostrKeyPair();
    private final String state = NGEUtils.bytesToHex(NGEUtils.getPlatform().randomBytes(32));
    private final List<String> relays, required, optional;
    private final String name;
    private final Instant created = Instant.now();
    private NostrPool pool;
    private NostrSubscription subscription;
    private AsyncExecutor timer;
    private Consumer<Throwable> reject;
    private boolean closed, started;

    public NWCAuth(List<String> relays, String name, List<String> required, List<String> optional) {
        if (relays.isEmpty() || relays.size() > 8 || required.isEmpty()) {
            keys.close();
            throw new IllegalArgumentException("Pairing needs relays and required methods");
        }
        relays.forEach(NWCAuth::validateRelay);
        this.relays = List.copyOf(relays);
        this.name = name;
        this.required = List.copyOf(required);
        this.optional = List.copyOf(optional);
    }

    private static void validateRelay(String address) {
        URI relay = URI.create(address);
        if (
            (!"wss".equals(relay.getScheme()) && !"ws".equals(relay.getScheme())) ||
            relay.getHost() == null ||
            relay.getUserInfo() != null ||
            relay.getFragment() != null
        ) {
            throw new IllegalArgumentException("Invalid wallet pairing relay");
        }
    }

    public synchronized String getConnectionUri() {
        if (closed) throw new IllegalStateException("Wallet pairing is closed");
        StringBuilder value = new StringBuilder("nostr+walletauth://")
            .append(keys.getPublicKey().asHex())
            .append("?state=")
            .append(state)
            .append("&name=")
            .append(encode(name))
            .append("&request_methods=")
            .append(encode(String.join(" ", required)));
        if (!optional.isEmpty()) value.append("&optional_request_methods=").append(encode(String.join(" ", optional)));
        for (String relay : relays) value.append("&relay=").append(encode(relay));
        return value.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Verifies the request correlation before trusting a wallet or its relay hints.
     */
    synchronized NWCUri connectionFrom(SignedNostrEvent event) {
        if (
            closed ||
            event.getKind() != NWCWallet.INFO_KIND ||
            !keys.getPublicKey().asHex().equals(event.getFirstTagFirstValue("p")) ||
            !state.equals(event.getFirstTagFirstValue("state")) ||
            event.getCreatedAt().isBefore(created.minusSeconds(60)) ||
            event.getCreatedAt().isAfter(Instant.now().plusSeconds(60))
        ) return null;
        List<String> granted = List.of(event.getContent().trim().split("\\s+"));
        if (!granted.containsAll(required)) return null;
        List<String> hints = event
            .getTagRows()
            .stream()
            .filter(tag -> tag.size() > 1 && "relay".equals(tag.get(0)))
            .map(tag -> tag.get(1))
            .distinct()
            .collect(Collectors.toList());
        if (hints.size() > 8) return null;
        hints.forEach(NWCAuth::validateRelay);
        return new NWCUri(event.getPubkey(), hints.isEmpty() ? relays : hints, keys.getPrivateKey().asHex(), null);
    }

    /**
     * Starts listening only when the user reveals/opens the QR, not during page load.
     */
    public synchronized AsyncTask<NWCUri> awaitConnection(Duration timeout) {
        if (closed || started) return AsyncTask.failed(new IllegalStateException("Pairing is no longer available"));
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid pairing timeout");
        started = true;
        return AsyncTask.create((resolve, failure) -> {
            synchronized (this) {
                if (closed) {
                    failure.accept(new IllegalStateException("Wallet pairing cancelled"));
                    return;
                }
                reject = failure;
                pool = new NostrPool();
                timer = NGEUtils.getPlatform().newAsyncExecutor(NWCAuth.class);
                subscription =
                    pool.subscribe(
                        new NostrFilter()
                            .withKind(NWCWallet.INFO_KIND)
                            .withTag("p", keys.getPublicKey().asHex())
                            .since(created.minusSeconds(60)),
                        relays
                    );
                subscription.addListener(
                    (NostrSubEventListener) (sub, event, stored) -> {
                        NWCUri connection;
                        synchronized (this) {
                            if (closed) return;
                            try {
                                connection = connectionFrom(event);
                            } catch (IllegalArgumentException invalid) {
                                return;
                            }
                            if (connection == null) return;
                            reject = null;
                            close();
                        }
                        resolve.accept(connection);
                    }
                );
                timer.runLater(
                    () -> {
                        close();
                        return null;
                    },
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
                );
                subscription.open();
            }
        });
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (subscription != null) subscription.close();
        if (pool != null) pool.close();
        if (timer != null) timer.close();
        keys.close();
        if (reject != null) {
            Consumer<Throwable> failure = reject;
            reject = null;
            failure.accept(new IllegalStateException("Wallet pairing expired or cancelled"));
        }
    }
}
