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

package org.ngengine.nostr4j.turn.ref;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Logger;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.NGEUtils;

public final class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());
    private static final String HOST_PROPERTY = "nostr.turn.ref.host";
    private static final String HOST_ENV = "NOSTR_TURN_REF_HOST";
    private static final String PORT_PROPERTY = "nostr.turn.ref.port";
    private static final String PORT_ENV = "NOSTR_TURN_REF_PORT";
    private static final String DIFFICULTY_PROPERTY = "nostr.turn.ref.difficulty";
    private static final String DIFFICULTY_ENV = "NOSTR_TURN_REF_DIFFICULTY";
    private static final String CHALLENGE_TTL_PROPERTY = "nostr.turn.ref.challenge_ttl_seconds";
    private static final String CHALLENGE_TTL_ENV = "NOSTR_TURN_REF_CHALLENGE_TTL_SECONDS";
    private static final String PRIVATE_KEY_PROPERTY = "nostr.turn.ref.privkey";
    private static final String PRIVATE_KEY_ENV = "NOSTR_TURN_REF_PRIVKEY_HEX";
    private static final String NCRYPTSEC_PASSPHRASE_PROPERTY = "nostr.turn.ref.ncryptsec.passphrase";
    private static final String NCRYPTSEC_PASSPHRASE_ENV = "NOSTR_TURN_REF_NCRYPTSEC_PASSPHRASE";

    private Main() {}

    public static void main(String[] args) throws Exception {
        // Runtime config source precedence:
        // 1) System property
        // 2) Environment variable
        // 3) Default
        int port = resolveInt(PORT_PROPERTY, PORT_ENV, 8081);
        int difficulty = resolveInt(DIFFICULTY_PROPERTY, DIFFICULTY_ENV, 13);
        int challengeTtlSeconds = resolveInt(CHALLENGE_TTL_PROPERTY, CHALLENGE_TTL_ENV, 30);
        String host = resolveHost();

        // Server signer can be deterministic via properties/env or generated for local/dev use.
        NostrKeyPairSigner signer = loadSignerFromConfig();
        TurnServer server = new TurnServer(host, port, signer, difficulty, challengeTtlSeconds);

        logger.info("Starting TURN server on " + host + ":" + port);
        logger.info("Server pubkey: " + server.getServerPubkey().asHex());

        server.start();
        server.join();
    }

    private static NostrKeyPairSigner loadSignerFromConfig() throws Exception {
        // If provided, this private key is used as stable server identity for clients.
        String privateKeyValue = resolveString(PRIVATE_KEY_PROPERTY, PRIVATE_KEY_ENV);
        if (privateKeyValue == null || privateKeyValue.trim().isEmpty()) {
            // Fallback: random ephemeral signer (convenient for local runs/tests).
            return NostrKeyPairSigner.generate();
        }

        String value = privateKeyValue.trim();
        NostrPrivateKey privateKey;
        if (value.startsWith("nsec")) {
            privateKey = NostrPrivateKey.fromBech32(value);
        } else if (value.startsWith("ncryptsec")) {
            String passphrase = resolveString(NCRYPTSEC_PASSPHRASE_PROPERTY, NCRYPTSEC_PASSPHRASE_ENV);
            if (passphrase == null || passphrase.trim().isEmpty()) {
                passphrase = promptForPassphrase();
            }
            privateKey = NGEUtils.awaitNoThrow(NostrPrivateKey.fromNcryptsec(value, passphrase));
        } else {
            privateKey = NostrPrivateKey.fromHex(value);
        }
        return new NostrKeyPairSigner(new NostrKeyPair(privateKey));
    }

    private static String resolveHost() {
        String prop = System.getProperty(HOST_PROPERTY);
        if (prop != null && !prop.trim().isEmpty()) {
            return prop.trim();
        }
        String env = System.getenv(HOST_ENV);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return "127.0.0.1";
    }

    private static int resolveInt(String propertyName, String envName, int defaultValue) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.trim().isEmpty()) {
            return Integer.parseInt(property.trim());
        }
        String env = System.getenv(envName);
        if (env != null && !env.trim().isEmpty()) {
            return Integer.parseInt(env.trim());
        }
        return defaultValue;
    }

    private static String resolveString(String propertyName, String envName) {
        String property = System.getProperty(propertyName);
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String env = System.getenv(envName);
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        return null;
    }

    private static String promptForPassphrase() throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword("Enter ncryptsec passphrase: ");
            return password == null ? "" : new String(password).trim();
        }
        System.out.print("Enter ncryptsec passphrase: ");
        System.out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line = reader.readLine();
        return line == null ? "" : line.trim();
    }
}
