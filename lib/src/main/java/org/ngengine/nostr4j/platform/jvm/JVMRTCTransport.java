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

import static org.ngengine.nostr4j.utils.NostrUtils.dbg;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.RTCSettings;
import org.ngengine.nostr4j.platform.transport.RTCTransport;
import org.ngengine.nostr4j.platform.transport.RTCTransportListener;
import org.ngengine.nostr4j.platform.AsyncExecutor;
import org.ngengine.nostr4j.platform.Platform;
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

    private static final Logger logger = Logger.getLogger(JVMRTCTransport.class.getName());

    static {
        LibDataChannelArchDetect.initialize();
    }

    private List<RTCTransportListener> listeners = new CopyOnWriteArrayList<>();
    private PeerConnectionConfiguration config;
    private String connId;
    private PeerConnection conn;
    private AsyncTask<DataChannel> openChannel;
    private DataChannel channel;
    private boolean isInitiator;
    private List<String> trackedRemoteCandidates = new CopyOnWriteArrayList<>();
    private AsyncExecutor executor;
    private volatile boolean connected = false;

    public JVMRTCTransport() {}

    @Override
    public AsyncTask<Void> start(
        RTCSettings settings,
        AsyncExecutor executor,
        String connId,
        Collection<String> stunServers
    ) {
        this.executor = executor;
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

                this.config = PeerConnectionConfiguration.DEFAULT.withIceServers(stunUris).withDisableAutoNegotiation(false);
                this.connId = connId;
                this.conn = PeerConnection.createPeer(this.config);
                this.conn.onIceStateChange.register((PeerConnection peer, IceState state) -> {
                        if (state == IceState.RTC_ICE_FAILED) {
                            // for (RTCTransportListener listener : listeners) {
                            //     listener.onRTCIceFailed();
                            // }
                            this.close();
                            // for (RTCTransportListener listener : listeners) {
                            //     listener.onRTCChannelClosed();
                            // }
                        } else if (state == IceState.RTC_ICE_CONNECTED) {
                            // for (RTCTransportListener listener : listeners) {
                            //     listener.onRTCIceConnected();
                            // }
                        }
                    });

                // this.conn.onStateChange.register((PeerConnection p, PeerState state) -> {
                //     if (state == PeerState.RTC_CLOSED) {
                //         connected = false;
                //     } else if (state == PeerState.RTC_CONNECTED) {
                //         connected = true;
                //     }
                // });

                this.conn.onLocalCandidate.register((PeerConnection peer, String candidate, String mediaId) -> {
                        for (RTCTransportListener listener : listeners) {
                            try {
                                listener.onLocalRTCIceCandidate(candidate);
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error sending local candidate", e);
                            }
                        }
                    });

                this.executor.runLater(
                        () -> {
                            if (connected) return null;
                            for (RTCTransportListener listener : listeners) {
                                try {
                                    listener.onRTCDisconnected("timeout");
                                } catch (Exception e) {
                                    logger.log(Level.WARNING, "Error sending local candidate", e);
                                }
                            }
                            this.close();
                            return null;
                        },
                        settings.getP2pAttemptTimeout().toMillis(),
                        TimeUnit.MILLISECONDS
                    );

                res.accept(null);
            } catch (Exception e) {
                rej.accept(e);
            }
        });
    }

    @Override
    public AsyncTask<Void> write(ByteBuffer message) {
        Platform platform = NostrUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            try {
                this.openChannel.then(channel -> {
                        try {
                            boolean isDirectBuffer = message.isDirect();
                            if (!isDirectBuffer) {
                                ByteBuffer directBuffer = ByteBuffer.allocateDirect(message.remaining());
                                directBuffer.put(message);
                                directBuffer.flip();
                                channel.sendMessage(directBuffer);
                            } else {
                                channel.sendMessage(message);
                            }
                            res.accept(null);
                            return null;
                        } catch (Exception e) {
                            // e.printStackTrace();
                            logger.log(Level.WARNING, "Error sending message", e);
                            rej.accept(e);
                            return null;
                        }
                    });
            } catch (Exception e) {
                // e.printStackTrace();
                logger.log(Level.WARNING, "Error sending message", e);
                rej.accept(e);
            }
        });
    }

    AsyncTask<DataChannel> confChannel(DataChannel channel) {
        Platform platform = NostrUtils.getPlatform();
        return platform.wrapPromise((res1, rej1) -> {
            // channel.onClosed.register((DataChannel c) -> {
            //     for (RTCTransportListener listener : listeners) {
            //         listener.onRTCDisconnected();
            //     }
            // });
            channel.onError.register((c, error) -> {
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCChannelError(new Exception(error));
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error sending local candidate", e);
                    }
                }
                rej1.accept(new Exception(error));
            });

            this.conn.onStateChange.register((PeerConnection p, PeerState state) -> {
                    if (state == PeerState.RTC_CLOSED) {
                        this.connected = false;
                        for (RTCTransportListener listener : listeners) {
                            try {
                                listener.onRTCDisconnected("closed");
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error sending local candidate", e);
                            }
                        }
                        rej1.accept(new Exception("Peer connection closed"));
                    } else if (state == PeerState.RTC_FAILED) {
                        this.connected = false;
                        for (RTCTransportListener listener : listeners) {
                            try {
                                listener.onRTCDisconnected("failed");
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error sending local candidate", e);
                            }
                        }
                        rej1.accept(new Exception("Peer connection failed"));
                    }
                });

            if (channel.isOpen()) {
                logger.fine("Channel already opened");
                channel.onMessage.register(
                    Message.handleBinary((c, buffer) -> {
                        assert dbg(() -> {
                            logger.finest("Received Message");
                        });
                        for (RTCTransportListener listener : listeners) {
                            try {
                                listener.onRTCBinaryMessage(buffer);
                            } catch (Exception e) {
                                logger.log(Level.WARNING, "Error sending local candidate", e);
                            }
                        }
                    })
                );
                this.connected = true;
                for (RTCTransportListener listener : listeners) {
                    try {
                        listener.onRTCConnected();
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Error sending local candidate", e);
                    }
                }
                res1.accept(channel);
            } else {
                channel.onOpen.register((DataChannel cc) -> {
                    logger.fine("Channel opened");
                    channel.onMessage.register(
                        Message.handleBinary((c, buffer) -> {
                            assert dbg(() -> {
                                logger.finest("Received Message");
                            });
                            for (RTCTransportListener listener : listeners) {
                                try {
                                    listener.onRTCBinaryMessage(buffer);
                                } catch (Exception e) {
                                    logger.log(Level.WARNING, "Error sending local candidate", e);
                                }
                            }
                        })
                    );
                    this.connected = true;
                    for (RTCTransportListener listener : listeners) {
                        try {
                            listener.onRTCConnected();
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Error sending local candidate", e);
                        }
                    }
                    res1.accept(channel);
                });
            }
        });
    }

    @Override
    public AsyncTask<String> initiateChannel() {
        this.isInitiator = true;
        Platform platform = NostrUtils.getPlatform();
        return platform.wrapPromise((res, rej) -> {
            try {
                this.conn.onLocalDescription.register((PeerConnection peer, String sdp, SessionDescriptionType type) -> {
                        if (type == SessionDescriptionType.OFFER) {
                            res.accept(sdp);
                        }
                    });
                this.channel = this.conn.createDataChannel("nostr4j-" + connId);
                this.openChannel = confChannel(this.channel);
                // this.conn.setLocalDescription("offer");
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
                logger.fine("Adding remote candidate: " + candidate);
                trackedRemoteCandidates.add(candidate);
            }
        }
    }

    @Override
    public AsyncTask<String> connectToChannel(String offerOrAnswer) {
        Platform platform = NostrUtils.getPlatform();
        if (this.isInitiator) {
            logger.fine("Connect as initiator, use answer");
            String answer = offerOrAnswer;

            return platform.wrapPromise((res1, rej1) -> {
                this.conn.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
                this.conn.onStateChange.register((PeerConnection p, PeerState state) -> {
                        if (state == PeerState.RTC_CLOSED) {
                            rej1.accept(new Exception("Peer connection closed"));
                        } else if (state == PeerState.RTC_FAILED) {
                            rej1.accept(new Exception("Peer connection failed"));
                        }
                    });
                res1.accept(null);
            });
        } else {
            logger.fine("Connect using offer");

            String offer = offerOrAnswer;
            this.openChannel =
                platform.wrapPromise((res, rej) -> {
                    this.conn.onDataChannel.register((PeerConnection peer, DataChannel channel) -> {
                            this.channel = channel;
                            confChannel(channel)
                                .catchException(exc -> {
                                    rej.accept(exc);
                                })
                                .then(channel1 -> {
                                    res.accept(channel1);
                                    return null;
                                });
                        });
                });

            AsyncTask<String> answerSdpPromise = platform.wrapPromise((res2, rej2) -> {
                this.conn.onLocalDescription.register((PeerConnection peer, String sdp, SessionDescriptionType type) -> {
                        if (type == SessionDescriptionType.ANSWER) {
                            logger.fine("answer is ready: " + sdp);
                            res2.accept(sdp);
                        }
                    });

                this.conn.onStateChange.register((PeerConnection peer, PeerState state) -> {
                        logger.fine("Peer connection state changed: " + state);
                        if (state == PeerState.RTC_CLOSED) {
                            rej2.accept(new Exception("Peer connection closed"));
                        } else if (state == PeerState.RTC_FAILED) {
                            rej2.accept(new Exception("Peer connection failed"));
                        }
                    });
            });
            this.conn.setRemoteDescription(offer, SessionDescriptionType.OFFER);
            return answerSdpPromise;
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
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        if (this.channel != null) this.channel.close();
        // .catchException(exc->{
        if (this.conn != null) this.conn.close();
        // })
        // .then(channel->{

        //     this.conn.close();
        //     return null;
        // });
    }
}
