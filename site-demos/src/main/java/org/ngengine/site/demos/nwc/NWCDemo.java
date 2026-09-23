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

package org.ngengine.site.demos.nwc;

import java.net.URISyntaxException;
import java.util.List;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.teavm.TeaVMPlatform;
import org.ngengine.site.demos.JsBridge;
import org.ngengine.wallets.TransactionInfo;
import org.ngengine.wallets.WalletInfo;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Demo 2: Nostr Wallet Connect (NIP-47) wallet inspection.
 *
 * <p>Installs {@code window.NWCDemo} with:
 * <ul>
 *   <li>{@code connect(uri, onStatus, onInfo, onError)} — connect a
 *       {@code nostr+walletconnect://} URI, then report
 *       {@code {"alias","balance","supportsPay}} (balance in msats,
 *       plus balanceMsats/balanceSats conveniences)
 *   <li>{@code transactions(onJson, onError)} — last 5 transactions as a JSON array
 *   <li>{@code disconnect()} — close the wallet and drop all secrets
 * </ul>
 *
 * <p>Secret hygiene: the NWC URI/secret is never logged, never appears in
 * error messages, never touches localStorage, lives only in private static
 * wallet object, and is closed on failure and on {@link #disconnect()}. The
 * NWCWallet's own fine-level logging (which includes the URI) is suppressed to
 * WARNING.
 *
 * <p>Interop note (TeaVM 0.15.0): Java lambdas can only be exposed to JS when
 * the {@code @JSFunctor} interface extends {@code JSObject}; callbacks coming
 * from the page are taken as plain {@code JSObject} values and invoked through
 * {@code @JSBody} trampolines, because TeaVM's automatic JS-function-to-functor
 * wrapping mis-names the entry point.
 */
public class NWCDemo {

    private static NWCWallet wallet;

    // NOTE: NWCWallet interpolates the full NWC URI (secret included) into a
    // few FINEST log strings, and TeaVM's Logger prints every level to the
    // console with no setLevel(). installConsoleScrubber() (called from main)
    // redacts the URI pattern from console output to close that leak. The URI
    // still lives in memory while connected — unavoidable — and is nulled on
    // failure/disconnect.

    @JSBody(params = {}, script = "return (typeof window !== 'undefined');")
    static native boolean hasWindow();

    /**
     * NWCWallet logs the full NWC URI (secret included) at FINEST, and TeaVM's
     * Logger has no setLevel() — every level is printed to the console. Since
     * the wallet class cannot be changed from this demo, scrub any
     * {@code nostr+walletconnect://} URI out of console output instead. Only
     * the URI pattern is redacted; everything else passes through untouched.
     */
    @JSBody(
        params = {},
        script = "if (window.__nwcConsoleScrubbed) return;\n" +
        "window.__nwcConsoleScrubbed = true;\n" +
        "var redact = function(args) {\n" +
        "  return Array.prototype.map.call(args, function(x) {\n" +
        "    return (typeof x === 'string')\n" +
        "      ? x.replace(/nostr\\+walletconnect:\\/\\/[^\\s\"']*/g, 'nostr+walletconnect://[redacted]')\n" +
        "      : x;\n" +
        "  });\n" +
        "};\n" +
        "['log', 'info', 'debug', 'warn'].forEach(function(m) {\n" +
        "  if (typeof console[m] !== 'function') return;\n" +
        "  var orig = console[m].bind(console);\n" +
        "  console[m] = function() { orig.apply(null, redact(arguments)); };\n" +
        "});"
    )
    static native void installConsoleScrubber();

    /** Fire a page callback with a string payload. */
    @JSBody(params = { "cb", "s" }, script = "cb(s);")
    static native void fireStr(JSObject cb, String s);

    @JSFunctor
    public interface ConnectFn extends JSObject {
        void connect(String uri, JSObject onStatus, JSObject onInfo, JSObject onError);
    }

    @JSFunctor
    public interface TransactionsFn extends JSObject {
        void list(JSObject onJson, JSObject onError);
    }

    @JSFunctor
    public interface DisconnectFn extends JSObject {
        void disconnect();
    }

    @JSBody(params = { "api", "fn" }, script = "api.connect = fn;")
    static native void installConnect(JSObject api, ConnectFn fn);

    @JSBody(params = { "api", "fn" }, script = "api.transactions = fn;")
    static native void installTransactions(JSObject api, TransactionsFn fn);

    @JSBody(params = { "api", "fn" }, script = "api.disconnect = fn;")
    static native void installDisconnect(JSObject api, DisconnectFn fn);

    public static void main(String[] args) {
        NGEPlatform.set(new TeaVMPlatform());
        if (!hasWindow()) {
            return; // node smoke test: no DOM, nothing to install
        }
        installConsoleScrubber(); // keep the NWC secret out of the devtools console
        JSObject api = JsBridge.newObject();
        installConnect(api, (uri, onStatus, onInfo, onError) -> connect(uri, onStatus, onInfo, onError));
        installTransactions(api, (onJson, onError) -> transactions(onJson, onError));
        installDisconnect(api, () -> disconnect());
        JsBridge.setGlobal("NWCDemo", api);
    }

    /**
     * Error text guaranteed to never contain the URI or its secret: messages
     * are categorized by exception type only, never echoed from the throwable.
     */
    private static String safeError(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof URISyntaxException || t instanceof IllegalArgumentException) {
                return "invalid NWC connection string";
            }
            String cn = t.getClass().getSimpleName();
            if (cn.contains("Timeout")) {
                return "request timed out";
            }
            if (t instanceof java.io.IOException) {
                return "connection failed";
            }
            t = t.getCause();
        }
        return "NWC request failed";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // -- connect -------------------------------------------------------------

    public static void connect(String uriString, JSObject onStatus, JSObject onInfo, JSObject onError) {
        NWCWallet w = null;
        try {
            if (uriString == null || uriString.length() > 8192 || !uriString.trim().startsWith("nostr+walletconnect://")) {
                throw new IllegalArgumentException("not an NWC URI");
            }
            // Drop any previous connection before opening a new one.
            closeQuietly();

            final NWCUri uri = new NWCUri(uriString.trim());
            for (String relay : uri.getRelays()) JsBridge.requireWssUrl(relay);
            w = new NWCWallet(uri);
            final NWCWallet fw = w;

            fireStr(onStatus, "{\"stage\":\"connecting\",\"relay\":\"" + JsBridge.esc(nz(firstRelay(uri))) + "\"}");

            fw
                .waitForReady()
                .compose(ready -> fw.getBalance(null))
                .compose(balanceMsats -> fw.getInfo(null).then(info -> new Object[] { balanceMsats, info }))
                .compose(arr ->
                    fw
                        .getSupportedMethods()
                        .then(methods -> {
                            long balanceMsats = (Long) arr[0];
                            WalletInfo info = (WalletInfo) arr[1];
                            boolean supportsPay = methods != null && methods.contains("pay_invoice");
                            String json =
                                "{\"alias\":\"" +
                                JsBridge.esc(nz(info.alias())) +
                                "\",\"balance\":" +
                                balanceMsats +
                                ",\"balanceMsats\":" +
                                balanceMsats +
                                ",\"balanceSats\":" +
                                (balanceMsats / 1000L) +
                                ",\"supportsPay\":" +
                                supportsPay +
                                "}";
                            // Only publish on full success.
                            wallet = fw;
                            fireStr(onStatus, "{\"stage\":\"ready\"}");
                            fireStr(onInfo, json);
                            return null;
                        })
                )
                .catchException(e -> {
                    closeQuietly();
                    fireStr(onError, safeError(e));
                });
        } catch (Exception e) {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {}
            }
            fireStr(onError, safeError(e));
        }
    }

    private static String firstRelay(NWCUri uri) {
        try {
            List<String> relays = uri.getRelays();
            return relays.isEmpty() ? "" : relays.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    private static void closeQuietly() {
        final NWCWallet w = wallet;
        wallet = null;
        if (w != null) {
            try {
                w.close();
            } catch (Exception ignored) {}
        }
    }

    // -- transactions --------------------------------------------------------

    public static void transactions(JSObject onJson, JSObject onError) {
        final NWCWallet w = wallet;
        if (w == null) {
            fireStr(onError, "not connected");
            return;
        }
        w
            .listTransactions(null, null, 5, 0, false, null, null)
            .then(txs -> {
                StringBuilder b = new StringBuilder("[");
                for (int i = 0; i < txs.size(); i++) {
                    TransactionInfo t = txs.get(i);
                    if (i > 0) b.append(",");
                    b
                        .append("{\"type\":\"")
                        .append(JsBridge.esc(t.type().name()))
                        .append("\",\"amountMsats\":")
                        .append(t.amountMsats())
                        .append(",\"description\":\"")
                        .append(JsBridge.esc(nz(t.description())))
                        .append("\",\"createdAt\":")
                        .append(t.createdAt().getEpochSecond())
                        .append(",\"settledAt\":")
                        .append(t.settledAt() != null ? String.valueOf(t.settledAt().getEpochSecond()) : "null")
                        .append("}");
                }
                b.append("]");
                fireStr(onJson, b.toString());
                return null;
            })
            .catchException(e -> fireStr(onError, safeError(e)));
    }

    // -- disconnect ----------------------------------------------------------

    public static void disconnect() {
        closeQuietly();
    }
}
