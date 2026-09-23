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

package org.ngengine.site.demos;

import java.net.URI;
import java.net.URISyntaxException;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Shared JS interop helpers for all site demos.
 *
 * <p>Contract: every demo bundle, when loaded via {@code <script>}, runs its
 * {@code main()}, which first calls {@code NGEPlatform.set(new
 * TeaVMPlatform())} and then installs a plain JS object as
 * {@code window.<GlobalName>} (e.g. {@code window.RelayDemo}). Page-side JS
 * drives the demo exclusively through that object; Java reaches back into the
 * page through the {@code @JSFunctor} callbacks below.
 *
 * <p>Keep payloads as hand-built JSON strings: they cross the boundary as
 * plain strings, no reflection needed (TeaVM has none).
 */
public final class JsBridge {

    private JsBridge() {}

    /**
     * Validate a browser-facing relay URL. Demo inputs are deliberately
     * restricted to encrypted WebSockets and cannot contain credentials or a
     * fragment. This is not a replacement for an application allowlist.
     */
    public static String requireWssUrl(String value) {
        if (value == null) throw new IllegalArgumentException("relay URL is required");
        String url = value.trim();
        if (url.isEmpty() || url.length() > 2048) throw new IllegalArgumentException("invalid relay URL");
        for (int i = 0; i < url.length(); i++) {
            if (Character.isISOControl(url.charAt(i))) throw new IllegalArgumentException("invalid relay URL");
        }
        try {
            URI parsed = new URI(url);
            if (
                !"wss".equalsIgnoreCase(parsed.getScheme()) ||
                parsed.getHost() == null ||
                parsed.getUserInfo() != null ||
                parsed.getFragment() != null
            ) {
                throw new IllegalArgumentException("relay URL must be a wss:// URL without credentials or a fragment");
            }
            int port = parsed.getPort();
            if (port == 0 || port > 65535) throw new IllegalArgumentException("invalid relay URL port");
            return parsed.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid relay URL");
        }
    }

    /** A JS callback receiving a generic JS value. */
    @JSFunctor
    public interface JsCallback extends JSObject {
        void call(JSObject arg);
    }

    /** A JS callback receiving a string (usually a JSON payload). */
    @JSFunctor
    public interface JsStringCallback extends JSObject {
        void call(String json);
    }

    /** A JS callback receiving a string, used for errors. */
    @JSFunctor
    public interface JsErrorCallback extends JSObject {
        void call(String message);
    }

    @JSBody(params = {}, script = "return {};")
    public static native JSObject newObject();

    /** Installs {@code window[name] = api}. */
    @JSBody(params = { "name", "api" }, script = "window[name] = api;")
    public static native void setGlobal(String name, JSObject api);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    public static native void setFn(JSObject obj, String key, JsCallback fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    public static native void setStringFn(JSObject obj, String key, JsStringCallback fn);

    @JSBody(params = { "obj", "key", "fn" }, script = "obj[key] = fn;")
    public static native void setErrorFn(JSObject obj, String key, JsErrorCallback fn);

    @JSBody(params = { "obj", "key", "value" }, script = "obj[key] = value;")
    public static native void setProp(JSObject obj, String key, String value);

    @JSBody(params = { "obj", "key", "value" }, script = "obj[key] = value;")
    public static native void setProp(JSObject obj, String key, double value);

    @JSBody(params = { "obj", "key", "value" }, script = "obj[key] = value;")
    public static native void setProp(JSObject obj, String key, boolean value);

    /** Parse a JSON string into a JS object. */
    @JSBody(params = { "s" }, script = "return JSON.parse(s);")
    public static native JSObject parseJson(String s);

    /** Serialize a JS object to a JSON string. */
    @JSBody(params = { "o" }, script = "return JSON.stringify(o);")
    public static native String stringifyJson(JSObject o);

    @JSBody(params = { "msg" }, script = "console.log(msg);")
    public static native void log(String msg);

    @JSBody(params = { "msg" }, script = "console.warn(msg);")
    public static native void warn(String msg);

    /** Minimal JSON string escaper for hand-built payloads. */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append("\\u");
                        String h = Integer.toHexString(c);
                        for (int k = h.length(); k < 4; k++) b.append('0');
                        b.append(h);
                    } else b.append(c);
            }
        }
        return b.toString();
    }
}
