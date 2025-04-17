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
package org.ngengine.nostr4j.platform.jvm;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.rtc.RTCTransport;
import org.ngengine.nostr4j.transport.rtc.RTCTransportListener;
import org.ngengine.nostr4j.utils.NostrUtils;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback.Message;
import tel.schich.libdatachannel.IceState;
import tel.schich.libdatachannel.LibDataChannelArchDetect;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.PeerState;
import tel.schich.libdatachannel.SessionDescriptionType;

public class JVMRTCTransport implements RTCTransport {
    static {
        LibDataChannelArchDetect.initialize();
    }

    private List<RTCTransportListener> listeners = new CopyOnWriteArrayList<>();
    private PeerConnectionConfiguration config;
    private String connId;
    private PeerConnection conn;
    private DataChannel channel;
    private boolean isInitiator;
    private List<String> trackedRemoteCandidates = new CopyOnWriteArrayList<>();

    public JVMRTCTransport() {}

    @Override
    public AsyncTask<Void> start(String connId, Collection<String> stunServers) {
        Platform platform = NostrUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            try {
                Collection<URI> stunUris = new ArrayList<>();
                for (String server : stunServers) {
                    try {
                        URI uri = new URI("stun:" + server);
                        stunUris.add(uri);
                    } catch (URISyntaxException e) {
                        throw new IllegalArgumentException("Invalid STUN server URI: " + server, e);
                    }
                }

                this.config = PeerConnectionConfiguration.DEFAULT.withIceServers(stunUris).withDisableAutoNegotiation(true);
                this.connId = connId;
                this.conn = PeerConnection.createPeer(this.config);
                this.conn.onIceStateChange.register((PeerConnection peer, IceState state) -> {
                        if (state == IceState.RTC_ICE_FAILED) {
                            for (RTCTransportListener listener : listeners) {
                                listener.onLinkLost();
                            }
                        } else if (state == IceState.RTC_ICE_CONNECTED) {
                            for (RTCTransportListener listener : listeners) {
                                listener.onLinkEstablished();
                            }
                        }
                    });
                this.conn.onLocalCandidate.register((PeerConnection peer, String candidate, String mediaId) -> {
                        for (RTCTransportListener listener : listeners) {
                            listener.onLocalRTCIceCandidate(candidate);
                        }
                    });
                res.accept(null);
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<Void> send(ByteBuffer message) {
        Platform platform = NostrUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            try {
                this.channel.sendMessage(message);
                res.accept(null);
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    void confChannel(DataChannel channel) {
        channel.onClosed.register((DataChannel c) -> {
            for (RTCTransportListener listener : listeners) {
                listener.onRTCChannelClosed();
            }
        });
        channel.onError.register((c, error) -> {
            for (RTCTransportListener listener : listeners) {
                listener.onRTCChannelError(new Exception(error));
            }
        });
        channel.onMessage.register(
            Message.handleBinary((c, buffer) -> {
                for (RTCTransportListener listener : listeners) {
                    listener.onRTCBinaryMessage(buffer);
                }
            })
        );
    }

    @Override
    public AsyncTask<String> initiateChannel() {
        this.isInitiator = true;
        return NostrUtils
            .getPlatform()
            .wrapPromise((res, rej) -> {
                try {
                    this.channel = this.conn.createDataChannel("nostr4j-" + connId);
                    confChannel(this.channel);
                    this.conn.onLocalDescription.register((PeerConnection peer, String sdp, SessionDescriptionType type) -> {
                            if (type == SessionDescriptionType.OFFER) {
                                res.accept(sdp);
                            }
                        });
                    this.conn.setLocalDescription("offer");
                } catch (Exception e) {
                    rej.accept(e);
                }
            });
    }

    @Override
    public void addRemoteIceCandidates(Collection<String> candidates) {
        for (String candidate : candidates) {
            if (!trackedRemoteCandidates.contains(candidate)) {
                this.conn.addRemoteCandidate(candidate);
                trackedRemoteCandidates.add(candidate);
            }
        }
    }

    @Override
    public AsyncTask<String> connectToChannel(String offerOrAnswer) {
        Platform platform = NostrUtils.getPlatform();
        if (this.isInitiator) {
            String answer = offerOrAnswer;
            this.conn.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
            return platform.wrapPromise((res1, rej1) -> {
                if (this.channel.isOpen()) res1.accept(null); else {
                    this.conn.onStateChange.register((PeerConnection p, PeerState state) -> {
                            if (state == PeerState.RTC_CLOSED) {
                                rej1.accept(new Exception("Peer connection closed"));
                            } else if (state == PeerState.RTC_FAILED) {
                                rej1.accept(new Exception("Peer connection failed"));
                            }
                        });
                    this.channel.onError.register((DataChannel cc, String error) -> {
                            rej1.accept(new Exception(error));
                        });
                    this.channel.onOpen.register((DataChannel cc) -> {
                            res1.accept(null);
                        });
                }
            });
        } else {
            String offer = offerOrAnswer;
            AsyncTask<String> p1 = platform.wrapPromise((res1, rej1) -> {
                this.conn.onDataChannel.register((PeerConnection peer, DataChannel channel) -> {
                        this.channel = channel;
                        confChannel(this.channel);
                        if (this.channel.isOpen()) res1.accept(null); else {
                            this.conn.onStateChange.register((PeerConnection p, PeerState state) -> {
                                    if (state == PeerState.RTC_CLOSED) {
                                        rej1.accept(new Exception("Peer connection closed"));
                                    } else if (state == PeerState.RTC_FAILED) {
                                        rej1.accept(new Exception("Peer connection failed"));
                                    }
                                });
                            this.channel.onError.register((DataChannel cc, String error) -> {
                                    rej1.accept(new Exception(error));
                                });
                            this.channel.onOpen.register((DataChannel cc) -> {
                                    res1.accept(null);
                                });
                        }
                    });
            });
            AsyncTask<String> p2 = platform.wrapPromise((res2, rej2) -> {
                this.conn.onStateChange.register((PeerConnection peer, PeerState state) -> {
                        if (state == PeerState.RTC_CLOSED) {
                            rej2.accept(new Exception("Peer connection closed"));
                        } else if (state == PeerState.RTC_FAILED) {
                            rej2.accept(new Exception("Peer connection failed"));
                        }
                    });
                this.conn.onLocalDescription.register((PeerConnection peer, String sdp, SessionDescriptionType type) -> {
                        if (type == SessionDescriptionType.ANSWER) {
                            res2.accept(sdp);
                        }
                    });
            });
            this.conn.setRemoteDescription(offer, SessionDescriptionType.OFFER);
            this.conn.setLocalDescription("answer");
            AsyncTask<List<String>> ps = platform.awaitAll(List.of(p1, p2));
            return ps.then(r -> {
                return r.get(1);
            });
        }
    }

    @Override
    public void addListener(RTCTransportListener listener) {
        assert !listeners.contains(listener) : "Listener already added";
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(RTCTransportListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void close() {
        this.channel.close();
        this.conn.close();
    }
}
