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
package org.ngengine.nostr4j.cliclient;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.nip46.BunkerUrl;
import org.ngengine.nostr4j.nip46.Nip46AppMetadata;
import org.ngengine.nostr4j.signer.NostrNIP46Signer;

public class Nip46SignerTest {

    private static final Logger logger = TestLogger.getRoot(Level.INFO);
    private static final Scanner scanner = new Scanner(System.in);
    private static final List<String> DEFAULT_RELAYS = Arrays.asList("wss://relay.nsec.app");

    private NostrNIP46Signer signer;
    private NostrKeyPair keyPair;

    public Nip46SignerTest() {
        try {
            System.out.println("Initializing Nostr NIP-46 Signer Client...");
            keyPair = new NostrKeyPair();
            System.out.println("Generated client key: " + keyPair.getPublicKey().asHex());

            Nip46AppMetadata metadata = new Nip46AppMetadata()
                .setName("nostr4j")
                .setUrl("https://github.com/NostrGameEngine/nostr4")
                .setPerms(Arrays.asList("sign_event", "nip44_encrypt", "nip44_decrypt", "get_public_key"));

            signer = new NostrNIP46Signer(metadata, keyPair);

            // Handle challenge requests
            signer.setChallengeHandler(
                (type, content) -> {
                    System.out.println("Challenge received: " + type + " - " + content);
                    System.out.print("Accept challenge? (y/n): ");
                    String response = scanner.nextLine().trim().toLowerCase();
                    boolean accepted = response.equals("y");

                    if (accepted) {
                        System.out.println("Challenge accepted");
                    } else {
                        System.out.println("Challenge rejected");
                    }

                    return error -> {
                        if (error != null) {
                            System.out.println("Challenge failed: " + error.getMessage());
                        } else {
                            System.out.println("Challenge completed successfully");
                        }
                    };
                },
                Duration.ofMinutes(2)
            );
        } catch (Exception e) {
            System.err.println("Error initializing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void start() throws MalformedURLException, UnsupportedEncodingException, URISyntaxException {
        signer
            .listen(
                DEFAULT_RELAYS,
                url -> {
                    String urlString = url.toString();
                    System.out.println("Connect to: " + urlString);
                },
                Duration.ofMinutes(50)
            )
            .catchException(ex -> {
                System.out.println("Error while listening for NostrConnect: " + ex.getMessage());
            })
            .then(s -> {
                System.out.print("Connected!");
                testSigning();
                return null;
            });
        System.out.println("Or");
        System.out.println("Input the bunker url");
        String bunker = scanner.nextLine();
        signer
            .connect(BunkerUrl.parse(bunker))
            .catchException(ex -> {
                System.out.println("Connection failed: " + ex.getMessage());
            })
            .then(s -> {
                System.out.println("Connected!");
                testSigning();
                return null;
            });
    }

    private void testSigning() {
        System.out.println("Testing connection by requesting public key...");

        signer
            .getPublicKey()
            .catchException(ex -> {
                System.out.println("Error retrieving public key: " + ex.getMessage());
            })
            .then(pubKey -> {
                System.out.println("Retrieved public key: " + pubKey.asHex());

                return null;
            });
    }

    public static void main(String[] args) throws MalformedURLException, UnsupportedEncodingException, URISyntaxException {
        Nip46SignerTest client = new Nip46SignerTest();
        client.start();
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
