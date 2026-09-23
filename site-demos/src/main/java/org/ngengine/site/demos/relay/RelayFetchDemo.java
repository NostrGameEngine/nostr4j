/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.site.demos.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.ngengine.lnurl.LnAddress;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.lnurl.LnUrlPaymentResponse;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;
import org.ngengine.platform.teavm.TeaVMPlatform;
import org.ngengine.site.demos.JsBridge;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Demo 1: fetch public notes (kind 1) from a public relay, restricted to the
 * {@link WellKnownAuthors} allowlist, plus kind-0 profile lookup and
 * LNURL-pay invoice generation for zaps.
 *
 * <p>Installs {@code window.RelayDemo} with:
 * <ul>
 *   <li>{@code listAuthors(onJson)} — {@code [{"name","hex"}, ...]} of the allowlist
 *   <li>{@code fetchNotes(relayUrl, authorsJson, limit, onNote, onDone, onError)}
 *   <li>{@code fetchProfile(pubkeyHex, onProfile, onError)}
 *   <li>{@code zap(lud16, amountSats, onInvoice, onError)}
 * </ul>
 * All payloads are JSON strings; all content is HTML-escaped page-side.
 *
 * <p>Interop note (TeaVM 0.15.0): Java lambdas can only be exposed to JS when
 * the {@code @JSFunctor} interface extends {@code JSObject}; callbacks coming
 * from the page are taken as plain {@code JSObject} values and invoked through
 * {@code @JSBody} trampolines, because TeaVM's automatic JS-function-to-functor
 * wrapping mis-names the entry point.
 */
public class RelayFetchDemo {

    public static final String DEFAULT_RELAY = "wss://relay.damus.io";

    private static NostrPool pool;
    private static String poolRelayUrl;

    @JSBody(params = {}, script = "return (typeof window !== 'undefined');")
    static native boolean hasWindow();

    /** Fire a page callback with a string payload. */
    @JSBody(params = { "cb", "s" }, script = "cb(s);")
    static native void fireStr(JSObject cb, String s);

    /** Fire a page callback with no arguments. */
    @JSBody(params = { "cb" }, script = "cb();")
    static native void fireVoid(JSObject cb);

    @JSFunctor
    public interface ListAuthorsFn extends JSObject {
        void list(JSObject onJson);
    }

    @JSFunctor
    public interface FetchNotesFn extends JSObject {
        void fetch(String relayUrl, String authorsJson, int limit, JSObject onNote, JSObject onDone, JSObject onError);
    }

    @JSFunctor
    public interface FetchProfileFn extends JSObject {
        void fetch(String pubkeyHex, JSObject onProfile, JSObject onError);
    }

    @JSFunctor
    public interface ZapFn extends JSObject {
        void zap(String lud16, int amountSats, JSObject onInvoice, JSObject onError);
    }

    @JSBody(params = { "api", "fn" }, script = "api.listAuthors = fn;")
    static native void installListAuthors(JSObject api, ListAuthorsFn fn);

    @JSBody(params = { "api", "fn" }, script = "api.fetchNotes = fn;")
    static native void installFetchNotes(JSObject api, FetchNotesFn fn);

    @JSBody(params = { "api", "fn" }, script = "api.fetchProfile = fn;")
    static native void installFetchProfile(JSObject api, FetchProfileFn fn);

    @JSBody(params = { "api", "fn" }, script = "api.zap = fn;")
    static native void installZap(JSObject api, ZapFn fn);

    public static void main(String[] args) {
        NGEPlatform.set(new TeaVMPlatform());
        if (!hasWindow()) {
            return; // node smoke test: no DOM, nothing to install
        }
        JSObject api = JsBridge.newObject();
        installListAuthors(api, onJson -> listAuthors(onJson));
        installFetchNotes(
            api,
            (relayUrl, authorsJson, limit, onNote, onDone, onError) ->
                fetchNotes(relayUrl, authorsJson, limit, onNote, onDone, onError)
        );
        installFetchProfile(api, (pubkeyHex, onProfile, onError) -> fetchProfile(pubkeyHex, onProfile, onError));
        installZap(api, (lud16, amountSats, onInvoice, onError) -> zap(lud16, amountSats, onInvoice, onError));
        JsBridge.setGlobal("RelayDemo", api);
    }

    private static String safeMsg(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static synchronized NostrPool poolFor(String relayUrl) {
        if (pool == null || !relayUrl.equals(poolRelayUrl)) {
            if (pool != null) {
                try {
                    pool.close();
                } catch (Exception ignored) {}
            }
            pool = new NostrPool();
            poolRelayUrl = relayUrl;
        }
        return pool;
    }

    // -- listAuthors --------------------------------------------------------

    public static void listAuthors(JSObject onJson) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < WellKnownAuthors.ALL.length; i++) {
            WellKnownAuthors.Author a = WellKnownAuthors.ALL[i];
            if (i > 0) b.append(",");
            b
                .append("{\"name\":\"")
                .append(JsBridge.esc(a.name))
                .append("\",\"hex\":\"")
                .append(JsBridge.esc(a.hex))
                .append("\"}");
        }
        b.append("]");
        fireStr(onJson, b.toString());
    }

    // -- fetchNotes ----------------------------------------------------------

    public static void fetchNotes(
        String relayUrl,
        String authorsJson,
        int limit,
        JSObject onNote,
        JSObject onDone,
        JSObject onError
    ) {
        try {
            String relay = JsBridge.requireWssUrl((relayUrl == null || relayUrl.trim().isEmpty()) ? DEFAULT_RELAY : relayUrl);
            List<String> authors = NGEUtils.safeStringList(NGEPlatform.get().fromJSON(authorsJson, List.class));
            if (authors == null || authors.isEmpty()) {
                throw new IllegalArgumentException("no authors selected");
            }
            // Only allowlisted authors may be fetched: intersect with the allowlist.
            List<String> allowed = new java.util.ArrayList<>();
            for (String a : authors) {
                for (WellKnownAuthors.Author known : WellKnownAuthors.ALL) {
                    if (known.hex.equalsIgnoreCase(a.trim())) {
                        allowed.add(known.hex);
                        break;
                    }
                }
            }
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException("no allowlisted authors selected");
            }
            final int n = Math.max(1, Math.min(limit, 25));
            NostrPool p = poolFor(relay);
            NostrFilter f = new NostrFilter().withKind(1).limit(n).since(Instant.now().minus(Duration.ofDays(7)));
            for (String a : allowed) f.withAuthor(a);

            p
                .ensureRelay(relay)
                .compose(r -> p.fetch(f, n, Duration.ofSeconds(25)))
                .then(events -> {
                    for (SignedNostrEvent ev : events) {
                        fireStr(onNote, noteJson(ev));
                    }
                    fireStr(onDone, "{\"count\":" + events.size() + "}");
                    return null;
                })
                .catchException(e -> fireStr(onError, safeMsg(e)));
        } catch (Exception e) {
            fireStr(onError, safeMsg(e));
        }
    }

    private static String noteJson(SignedNostrEvent ev) {
        return (
            "{\"id\":\"" +
            JsBridge.esc(ev.getId()) +
            "\",\"pubkey\":\"" +
            JsBridge.esc(ev.getPubkey().asHex()) +
            "\",\"content\":\"" +
            JsBridge.esc(ev.getContent()) +
            "\",\"createdAt\":" +
            ev.getCreatedAt().getEpochSecond() +
            "}"
        );
    }

    // -- fetchProfile --------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static void fetchProfile(String pubkeyHex, JSObject onProfile, JSObject onError) {
        try {
            final String hex = pubkeyHex == null ? "" : pubkeyHex.trim();
            if (!hex.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("invalid pubkey hex");
            }
            final String relay = DEFAULT_RELAY;
            NostrPool p = poolFor(relay);
            NostrFilter f = new NostrFilter().withKind(0).withAuthor(hex).limit(1);
            p
                .ensureRelay(relay)
                .compose(r -> p.fetch(f, 1, Duration.ofSeconds(20)))
                .then(events -> {
                    if (events.isEmpty()) {
                        fireStr(onError, "no profile found for this author on " + relay);
                        return null;
                    }
                    String content = events.get(0).getContent();
                    Map<String, Object> meta = (Map<String, Object>) NGEPlatform.get().fromJSON(content, Map.class);
                    String name = firstNonEmpty(str(meta.get("display_name")), str(meta.get("name")), hex.substring(0, 8));
                    String json =
                        "{\"name\":\"" +
                        JsBridge.esc(name) +
                        "\",\"picture\":\"" +
                        JsBridge.esc(str(meta.get("picture"))) +
                        "\",\"about\":\"" +
                        JsBridge.esc(str(meta.get("about"))) +
                        "\",\"lud16\":\"" +
                        JsBridge.esc(str(meta.get("lud16"))) +
                        "\"}";
                    fireStr(onProfile, json);
                    return null;
                })
                .catchException(e -> fireStr(onError, safeMsg(e)));
        } catch (Exception e) {
            fireStr(onError, safeMsg(e));
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    // -- zap -----------------------------------------------------------------

    public static void zap(String lud16, int amountSats, JSObject onInvoice, JSObject onError) {
        try {
            final String addr = lud16 == null ? "" : lud16.trim();
            if (addr.length() > 320 || !addr.matches("^[^@\\s]+@[^@\\s]+$")) {
                throw new IllegalArgumentException("not a lightning address");
            }
            // Sanity clamp: demo tips between 1 sat and 100k sats.
            final long sats = Math.max(1, Math.min(amountSats <= 0 ? 21 : amountSats, 100_000));
            final long msats = sats * 1000L;

            final LnAddress lnAddress = new LnAddress(addr);
            AsyncTask<LnUrlPay> payService;
            try {
                payService = lnAddress.getService();
            } catch (Exception e) {
                throw new RuntimeException("lnurl lookup failed: " + safeMsg(e));
            }
            payService
                .compose(pay -> {
                    try {
                        return pay.fetchInvoice(msats, "Nostr4J site demo tip", null, Duration.ofSeconds(30), null);
                    } catch (Exception e) {
                        throw new RuntimeException("invoice request failed: " + safeMsg(e));
                    }
                })
                .then((LnUrlPaymentResponse resp) -> {
                    fireStr(onInvoice, "{\"bolt11\":\"" + JsBridge.esc(resp.getPr()) + "\",\"amountSats\":" + sats + "}");
                    return null;
                })
                .catchException(e -> fireStr(onError, safeMsg(e)));
        } catch (Exception e) {
            fireStr(onError, safeMsg(e));
        }
    }
}
