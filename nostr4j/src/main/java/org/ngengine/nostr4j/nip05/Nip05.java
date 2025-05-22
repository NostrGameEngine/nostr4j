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
package org.ngengine.nostr4j.nip05;

import java.time.Duration;
import java.util.Map;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class Nip05 {

    public static AsyncTask<Nip05Identity> fetch(String identifier, Duration timeout) {
        return fetch(identifier, timeout, true);
    }

    public static AsyncTask<Nip05Identity> fetch(String identifier, Duration timeout, boolean useHttps) {
        String[] id = parseIdentifier(identifier);
        String name = id[0];
        String domain = id[1];
        NGEPlatform platform = NGEUtils.getPlatform();
        // https://<domain>/.well-known/nostr.json?name=<local-part>
        String fullUrl = "http" + (useHttps ? "s" : "") + "://" + domain + "/.well-known/nostr.json?name=" + name;
        AsyncTask<String> json = platform.httpGet(fullUrl, timeout, null);
        AsyncTask<Map> jsonData = json.then(r -> {
            try {
                return platform.fromJSON(r, Map.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse nip05 data", e);
            }
        });
        return jsonData.then(r -> {
            return new Nip05Identity(name, domain, r);
        });
    }

    public static String[] parseIdentifier(String identifier) {
        String name = identifier.substring(0, identifier.lastIndexOf('@'));
        String domain = identifier.substring(identifier.lastIndexOf('@') + 1);
        return new String[] { name, domain };
    }
}
