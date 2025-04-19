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
package org.ngengine.nostr4j.rtc;

import static tel.schich.libdatachannel.DataChannelCallback.Message.handleBinary;
import static tel.schich.libdatachannel.DataChannelCallback.Message.handleText;
import static tel.schich.libdatachannel.GatheringState.RTC_GATHERING_COMPLETE;
import static tel.schich.libdatachannel.PeerConnectionConfiguration.uris;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.LibDataChannelArchDetect;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;

public class Main {

    public static void main2(String[] args) {
        final var cfg = PeerConnectionConfiguration.DEFAULT.withIceServers(uris("stun:stun.l.google.com:19302"));
        // try with resources to cleanup peer when done
        try (var peer = PeerConnection.createPeer(cfg)) {
            System.out.println("Creating Peer");
            // when complete send sdp to remote peer
            peer.onGatheringStateChange.register((pc, state) -> {
                if (RTC_GATHERING_COMPLETE == state) {
                    final var sdp = pc.localDescription();
                    System.out.println(sdp);
                }
            });
            // create data channel
            final var channel = peer.createDataChannel("test");
            // wait for local sdp...
            // then set answer from remote peer
            peer.setAnswer(readInput());
            // register message callback (negative size indicates a null-terminated string
            // otherwise binary data)
            channel.onMessage.register(
                handleText((DataChannel c, String message) -> System.out.println("Incoming message: " + message))
            );
            // block until channel is closed
            CompletableFuture<Void> future = new CompletableFuture<>();
            channel.onClosed.register(c -> future.completeAsync(() -> null));
            future.join();
        }
    }

    public static class Offer implements AutoCloseable {

        private DataChannel channel;
        private String sdp;

        public Offer(DataChannel channel, String sdp) {
            this.channel = channel;
            this.sdp = sdp;
        }

        public void answer(String remoteSdp) {
            channel.peer().setAnswer(remoteSdp);
        }

        public CompletableFuture<Void> closeFuture() {
            CompletableFuture<Void> future = new CompletableFuture<>();
            this.channel.onClosed.register(c -> {
                    future.completeAsync(() -> null);
                });
            this.channel.peer()
                .onStateChange.register((peer, state) -> {
                    if (state == PeerState.RTC_CLOSED) {
                        peer.close();
                        future.completeAsync(() -> null);
                    }
                });
            return future;
        }

        @Override
        public void close() {
            this.channel.peer().close();
        }

        public static CompletableFuture<Offer> create(String label, PeerConnectionConfiguration cfg) {
            var peer = PeerConnection.createPeer(cfg);
            System.out.println("Creating Peer");
            final var futureOffer = new CompletableFuture<String>();
            peer.onGatheringStateChange.register((pc, state) -> {
                System.out.println("State Change: " + state);
                if (state == RTC_GATHERING_COMPLETE) {
                    futureOffer.complete(peer.localDescription());
                }
            });
            final var channel = peer.createDataChannel(label);
            return futureOffer.thenApply(sdp -> new Offer(channel, sdp));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Offer)) return false;
            Offer offer = (Offer) o;
            return Objects.equals(channel, offer.channel) && Objects.equals(sdp, offer.sdp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(channel, sdp);
        }
    }

    private static final String WEB_EXAMPLE_URL;

    static {
        String urlFromEnv = System.getenv("WEB_EXAMPLE_URL");
        if (urlFromEnv != null && !urlFromEnv.isEmpty()) {
            WEB_EXAMPLE_URL = urlFromEnv;
        } else {
            WEB_EXAMPLE_URL = "https://pschichtel.github.io/libdatachannel-java/index.html";
        }
    }

    public static void main(String[] args) {
        LibDataChannelArchDetect.initialize();
        final var cfg = PeerConnectionConfiguration.DEFAULT.withIceServers(uris("stun:stun.l.google.com:19302"));
        while (true) {
            try (var offer = Offer.create("test", cfg).join()) {
                offer.channel.onOpen.register(Main::handleOpen);
                offer.channel.onMessage.register(handleText(Main::handleTextMessage));
                offer.channel.onMessage.register(handleBinary(Main::handleByteBuffer));
                offer.channel.onError.register(Main::handleError);
                offer.channel.peer().onStateChange.register(Main::handleStateChange);
                final var encoded = Base64.getEncoder().encodeToString(offer.sdp.getBytes());
                System.out.println("SDP:\n\n" + offer.sdp);
                System.out.println("Awaiting Answer...\n" + WEB_EXAMPLE_URL + "?sdp=" + encoded);

                String remoteSdp = Main.readCompressedSdp();
                while (remoteSdp == null) {
                    System.out.println("Invalid! Try again");
                    remoteSdp = Main.readCompressedSdp();
                }
                System.out.println("Processing Answer...");
                System.out.println("\n\n");
                System.out.println(remoteSdp);
                System.out.println("\n\n");
                offer.answer(remoteSdp);

                offer.closeFuture().join();
            }
            System.out.println("closed!");
        }
    }

    private static void handleStateChange(final PeerConnection pc, final PeerState state) {
        System.out.println(state);
        if (state == PeerState.RTC_CONNECTED) {
            final var uri = pc.remoteAddress();
            System.out.println("Connected to " + uri.getHostString() + ":" + uri.getPort());
        }
    }

    private static void handleError(final DataChannel c, final String error) {
        System.out.println("Error: " + error);
    }

    private static void handleTextMessage(DataChannel channel, String text) {
        System.out.println("In: " + text);
        if (text.equals("exit")) {
            channel.sendMessage("Bye!");
            System.out.println("Ok we are done");
            channel.close();
        } else {
            channel.sendMessage("You said things...");
        }
    }

    private static void handleByteBuffer(final DataChannel c, ByteBuffer data) {
        c.sendMessage("What is this file?");
        System.out.println("Got a file if size: " + data.remaining());
    }

    private static void handleOpen(final DataChannel c) {
        System.out.println("Connection Open!");
        c.sendMessage("Hello There!");
        try {
            final byte[] imageBytes;
            try (InputStream in = Main.class.getResourceAsStream("/img.png")) {
                imageBytes = in.readAllBytes();
            }
            final ByteBuffer buffer = ByteBuffer.allocateDirect(imageBytes.length);
            buffer.put(imageBytes);
            buffer.flip();
            c.sendMessage(buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static String readCompressedSdp() {
        var remoteDescription = readInput();
        if (remoteDescription.startsWith("v=")) {
            return remoteDescription;
        }
        remoteDescription = remoteDescription.replaceAll("\n", "");
        String cValue = null;
        String appPort = null;
        String fingerprint = null;
        String iceLine = null;
        String iceFrag = null;
        List<String> candidates = new ArrayList<>();
        int nCandidate = 0;
        for (String line : remoteDescription.split("\\.")) {
            if (fingerprint == null) {
                final var hex = base64toHex(line);
                fingerprint =
                    IntStream
                        .range(0, hex.length() / 2)
                        .mapToObj(i -> hex.substring(i * 2, i * 2 + 2))
                        .collect(Collectors.joining(":"))
                        .toUpperCase();
            } else if (iceLine == null) {
                // iceLine = base64toHex(line);
                iceLine = line;
            } else if (iceFrag == null) {
                // iceFrag = base64toHex(line);
                iceFrag = line;
            } else {
                var ipv = line.substring(0, 1);
                var type = "s".equals(line.substring(1, 2)) ? "srflx" : "host";
                final var idIdx = line.indexOf('#');
                var rawIp = line.substring(2, idIdx);
                var id = line.substring(idIdx + 1);

                IpWithPort ipWithPort = null;
                switch (ipv) {
                    case "4":
                        // shortSdp += '.4' + type + btoa(ip + ":" + port).replaceAll("=+$", "") + "#" +
                        // id
                        final var ipv4 = base64toString(rawIp);

                        final var ipv4Parts = ipv4.split(":");
                        ipWithPort = new IpWithPort(ipv4Parts[0], ipv4Parts[1]);
                        break;
                    case "6":
                        // shortSdp += '.6' + type + ipv6ToBase64(ip) + ":" + port + "#" + id;
                        final var splitV6 = rawIp.split(":");
                        final var hexIpv6 = base64toHex(splitV6[0]);
                        var ipv6 = IntStream
                            .range(0, hexIpv6.length() / 4)
                            .mapToObj(i -> hexIpv6.substring(i * 4, i * 4 + 4))
                            .collect(Collectors.joining(":"));

                        ipWithPort = new IpWithPort(ipv6, splitV6[1]);
                        break;
                }
                if (ipWithPort != null) {
                    if (cValue == null) {
                        cValue = (ipWithPort.ip.contains(":") ? "IP6 " : "IP4 ") + ipWithPort.ip;
                        appPort = ipWithPort.port;
                    }
                    final var s = ipWithPort.ip.contains(":") ? "::" : "0.0.0.0";
                    candidates.add(
                        String.format(
                            "a=candidate:%s 1 UDP %d %s %s typ %s raddr %s rport 0",
                            id,
                            nCandidate++,
                            ipWithPort.ip,
                            ipWithPort.port,
                            type,
                            s
                        )
                    );
                }
            }
        }
        // System.out.println("FP: " + fingerprint);
        // System.out.println("ICE: " + iceLine + ":" + iceFrag);
        String cans = String.join("\n", candidates);
        System.out.println("CAN:\n" + cans);
        if (cans.isEmpty()) {
            return null;
        }

        remoteDescription =
            String.format(
                "v=0" +
                "\no=- 1337 0 IN IP4 0.0.0.0" +
                "\ns=-" +
                "\nt=0 0" +
                "\na=group:BUNDLE 0" +
                "\na=msid-semantic: WMS" +
                "\nm=application %s UDP webrtc-datachannel" +
                "\na=sendrecv" +
                "\nc=IN %s" +
                "\n%s" +
                "\na=end-of-candidates" +
                "\na=ice-ufrag:%s" +
                "\na=ice-pwd:%s" +
                "\na=ice-options:trickle" +
                "\na=fingerprint:sha-256 %s" +
                "\na=setup:active" +
                "\na=mid:0" +
                "\na=sctp-port:5000" +
                "\na=max-message-size:262144",
                appPort,
                cValue,
                cans,
                iceFrag,
                iceLine,
                fingerprint
            );
        return remoteDescription;
    }

    private static String base64toHex(final String line) {
        byte[] decodedLine = Base64.getDecoder().decode(line);
        return bytesToHex(decodedLine);
    }

    private static String base64toString(final String line) {
        var decodedLine = Base64.getDecoder().decode(line);
        return new String(decodedLine);
    }

    public static String readInput() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder out = new StringBuilder();
        while (true) {
            try {
                String line = in.readLine();
                if (line == null || line.isEmpty()) {
                    break;
                }
                out.append(line).append("\n");
            } catch (IOException e) {
                System.err.println("Error reading input");
            }
        }
        return out.toString();
    }

    private static class IpWithPort {

        private final String ip;
        private final String port;

        public IpWithPort(String ip, String port) {
            this.ip = ip;
            this.port = port;
        }

        public String ip() {
            return ip;
        }

        public String port() {
            return port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IpWithPort)) return false;
            IpWithPort that = (IpWithPort) o;
            return Objects.equals(ip, that.ip) && Objects.equals(port, that.port);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ip, port);
        }
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
