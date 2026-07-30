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

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.rtc.listeners.NostrRTCSocketListener;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.InternalRoutedTransport;
import org.ngengine.nostr4j.rtc.routing.InternalRoutingChannels;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutePath;
import org.ngengine.nostr4j.rtc.routing.RouteTransportProfile;
import org.ngengine.nostr4j.rtc.routing.RoutedTransportEngine;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.routing.WeightedRoutePlanner;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastTree;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastTreeBuilder;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyControlPlane;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologySnapshot;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;
import org.ngengine.nostr4j.rtc.signal.NostrRTCLocalPeer;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.transport.RTCTransportIceCandidate;

/**
 * Headful topology viewer backed by real nostr4j rooms and WebRTC DataChannels.
 *
 * <p>The relay is used only for Nostr discovery, encrypted signaling, and
 * private dc4 topology events. Green links are actual WebRTC connections.
 * Routed sends and broadcasts call the public {@link NostrRTCRoom} API and are
 * forwarded by nostr4j's onion transport.</p>
 */
public final class OnionTopologyDemo extends JFrame {

    static final String DEFAULT_RELAY = "wss://relay.ngengine.org";
    static final String APPLICATION_ID = "nostr4j-onion-topology-demo";
    static final String PROTOCOL_ID = "onion-topology-v1";
    private static final String MESSAGE_PREFIX = "onion-demo-v1";

    private static final Color BACKGROUND = new Color(8, 13, 24);
    private static final Color PANEL = new Color(14, 22, 38);
    private static final Color TEXT = new Color(224, 232, 244);
    private static final Color MUTED = new Color(125, 143, 166);
    private static final Color RTC = new Color(57, 220, 151);
    private static final Color TURN = new Color(87, 180, 255);
    private static final Color PENDING = new Color(62, 75, 94);
    private static final Color ONION = new Color(255, 174, 66);

    private final Options options;
    private final CopyOnWriteArrayList<DemoPeer> peers = new CopyOnWriteArrayList<DemoPeer>();
    private final Map<String, DemoPeer> peersByIdentity = new ConcurrentHashMap<String, DemoPeer>();
    private final Map<Long, PendingTransfer> pendingTransfers = new ConcurrentHashMap<Long, PendingTransfer>();
    private final Map<Long, Set<NodeId>> broadcastReceivers = new ConcurrentHashMap<Long, Set<NodeId>>();
    private final AtomicLong transferSequence = new AtomicLong();
    private final AtomicBoolean sendBusy = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean verified = new AtomicBoolean();
    private final AtomicBoolean broadcastVerified = new AtomicBoolean();
    private final AtomicInteger verificationStage = new AtomicInteger();
    private final AtomicLong networkSequence = new AtomicLong();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor(r -> daemonThread(r, "onion-demo-network")
    );
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        2,
        r -> daemonThread(r, "onion-demo-timer")
    );

    private final TopologyPanel topologyPanel = new TopologyPanel();
    private final JTextArea eventLog = new JTextArea();
    private final JLabel status = new JLabel("Starting…");
    private final JLabel relayStatus = new JLabel(DEFAULT_RELAY);
    private final JButton sendButton = new JButton("Send routed packet");
    private final JButton broadcastButton = new JButton("Broadcast");
    private final JButton rebuildButton = new JButton("Rebuild and reconnect");
    private final SpinnerNumberModel peerModel;
    private final SpinnerNumberModel directConnectionsModel;
    private final JSpinner peerSpinner;
    private final JSpinner directConnectionsSpinner;
    private final JComboBox<String> sender = new JComboBox<String>();
    private final JComboBox<String> recipient = new JComboBox<String>();
    private final JTextField message = new JTextField("Hello over real onion routing");
    private final JSlider hopDelay = new JSlider(150, 1800, 750);

    private volatile NostrPool pool;
    private volatile NostrTURNPool turnPool;
    private volatile NostrKeyPair roomKeys;
    private volatile RoutingScope routingScope;
    private volatile RoutePath highlightedRoute;
    private volatile BroadcastTree highlightedBroadcast;
    private volatile long highlightedAtNanos;
    private volatile int configuredPeerCount;
    private volatile int configuredMaxDirectPeers;
    private volatile int visualHopDelayMillis = 750;
    private volatile String lastResult = "Waiting for real WebRTC links";
    private volatile String lastTopologySummary = "";

    private OnionTopologyDemo(Options options) {
        super("nostr4j · Real Onion WebRTC Topology");
        this.options = options;
        this.configuredPeerCount = options.peerCount;
        this.configuredMaxDirectPeers = options.maxDirectPeers;
        this.peerModel = new SpinnerNumberModel(options.peerCount, 3, 64, 1);
        this.directConnectionsModel =
            new SpinnerNumberModel(options.maxDirectPeers, RTCSettings.MIN_MAX_DIRECT_PEERS, options.peerCount - 1, 1);
        this.peerSpinner = new JSpinner(peerModel);
        this.directConnectionsSpinner = new JSpinner(directConnectionsModel);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 760));
        setSize(1480, 920);
        setLocationByPlatform(true);
        getContentPane().setBackground(BACKGROUND);
        buildUi();
        addWindowListener(
            new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    shutdown(0);
                }
            }
        );
        new Timer(200, event -> refreshUi()).start();
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("OnionTopologyDemo must run headful; java.awt.headless is true");
        }
        configureTopologyLogging();
        AtomicReference<OnionTopologyDemo> reference = new AtomicReference<OnionTopologyDemo>();
        SwingUtilities.invokeAndWait(() -> {
            OnionTopologyDemo demo = new OnionTopologyDemo(options);
            reference.set(demo);
            demo.setVisible(true);
        });
        reference.get().startNetwork();
    }

    private static void configureTopologyLogging() {
        configureFineLogger(TopologyControlPlane.class);
        configureFineLogger(RoutedTransportEngine.class);
        configureFineLogger(NostrRTCChannel.class);
    }

    private static void configureFineLogger(Class<?> type) {
        Logger logger = Logger.getLogger(type.getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.FINE);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.FINE);
        logger.addHandler(handler);
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void buildUi() {
        JPanel header = new JPanel(new BorderLayout(18, 0));
        header.setBackground(PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JLabel title = new JLabel("REAL ONION ROUTING · NOSTR4J / WEBRTC");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        header.add(title, BorderLayout.WEST);

        configureButton(sendButton);
        configureButton(broadcastButton);
        configureButton(rebuildButton);
        sendButton.setEnabled(false);
        broadcastButton.setEnabled(false);
        rebuildButton.setEnabled(false);
        sender.setEnabled(false);
        recipient.setEnabled(false);
        message.setEnabled(false);
        sendButton.addActionListener(event -> triggerRoutedSend());
        broadcastButton.addActionListener(event -> triggerBroadcast((String) sender.getSelectedItem(), message.getText()));
        rebuildButton.addActionListener(event -> requestRebuild());
        peerSpinner.setName("peerCount");
        directConnectionsSpinner.setName("maxDirectConnections");
        sender.setName("sender");
        recipient.setName("recipient");
        message.setName("payload");
        hopDelay.setName("hopDelay");
        rebuildButton.setName("rebuildTopology");
        sendButton.setName("sendUnicast");
        broadcastButton.setName("sendBroadcast");
        peerSpinner.addChangeListener(event -> updateDirectConnectionsMaximum());
        hopDelay.addChangeListener(event -> visualHopDelayMillis = hopDelay.getValue());

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBackground(PANEL);
        footer.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        status.setForeground(TEXT);
        relayStatus.setForeground(MUTED);
        footer.add(status, BorderLayout.WEST);
        footer.add(relayStatus, BorderLayout.EAST);

        eventLog.setEditable(false);
        eventLog.setBackground(new Color(7, 11, 19));
        eventLog.setForeground(new Color(183, 198, 218));
        eventLog.setCaretColor(TEXT);
        eventLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        eventLog.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JScrollPane logScroll = new JScrollPane(eventLog);
        logScroll.setBorder(BorderFactory.createLineBorder(new Color(28, 42, 63)));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topologyPanel, logScroll);
        split.setBorder(null);
        split.setResizeWeight(0.79);
        split.setDividerSize(5);
        split.setBackground(BACKGROUND);

        add(header, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    private JComponent buildControls() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setPreferredSize(new Dimension(370, 820));
        controls.setBorder(new EmptyBorder(18, 18, 18, 18));
        controls.setBackground(PANEL);

        JLabel help = new JLabel(
            "<html>Configure and rebuild real WebRTC rooms, then choose exactly which logical peers exchange data.</html>"
        );
        help.setForeground(MUTED);
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(help);
        controls.add(Box.createVerticalStrut(18));

        controls.add(sectionLabel("TOPOLOGY"));
        controls.add(labeled("Peers in the room", peerSpinner));
        controls.add(labeled("Max direct connections per peer", directConnectionsSpinner));
        rebuildButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        rebuildButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        controls.add(rebuildButton);
        controls.add(Box.createVerticalStrut(20));

        controls.add(sectionLabel("MESSAGE"));
        controls.add(labeled("Sender", sender));
        controls.add(labeled("Recipient", recipient));
        controls.add(labeled("Payload", message));
        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        actions.add(sendButton);
        actions.add(broadcastButton);
        controls.add(actions);
        controls.add(Box.createVerticalStrut(20));

        controls.add(sectionLabel("VISUAL SPEED"));
        hopDelay.setOpaque(false);
        hopDelay.setMajorTickSpacing(550);
        hopDelay.setPaintTicks(true);
        hopDelay.setToolTipText("Visual delay between route hops; WebRTC transport is not throttled");
        hopDelay.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(hopDelay);
        JLabel delayLabel = new JLabel("150 ms  ← fast        slow →  1800 ms");
        delayLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        delayLabel.setForeground(MUTED);
        delayLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(delayLabel);
        controls.add(Box.createVerticalGlue());

        JLabel note = new JLabel(
            "<html>The animation follows the route selected by nostr4j. Every green/blue edge is a real RTC/TURN link.</html>"
        );
        note.setForeground(MUTED);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(note);
        return controls;
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        label.setForeground(ONION);
        label.setBorder(new EmptyBorder(0, 0, 7, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JComponent labeled(String text, JComponent component) {
        JPanel row = new JPanel(new BorderLayout(0, 5));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 61));
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        row.add(label, BorderLayout.NORTH);
        component.setPreferredSize(new Dimension(330, 32));
        row.add(component, BorderLayout.CENTER);
        row.setBorder(new EmptyBorder(0, 0, 9, 0));
        return row;
    }

    private static void configureButton(JButton button) {
        button.setFocusPainted(false);
        button.setBackground(new Color(33, 47, 68));
        button.setForeground(TEXT);
        button.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(66, 86, 113)),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)
            )
        );
    }

    private void updateDirectConnectionsMaximum() {
        int peerCount = ((Number) peerModel.getNumber()).intValue();
        int maximum = maximumDirectPeersFor(peerCount);
        directConnectionsModel.setMaximum(Integer.valueOf(maximum));
        if (((Number) directConnectionsModel.getNumber()).intValue() > maximum) {
            directConnectionsModel.setValue(Integer.valueOf(maximum));
        }
    }

    static int maximumDirectPeersFor(int peerCount) {
        if (peerCount < 3 || peerCount > 64) {
            throw new IllegalArgumentException("Peer count must be between 3 and 64");
        }
        return peerCount - 1;
    }

    private void requestRebuild() {
        int peerCount = ((Number) peerSpinner.getValue()).intValue();
        int maxDirectPeers = ((Number) directConnectionsSpinner.getValue()).intValue();
        if (!sendBusy.compareAndSet(false, true)) {
            log("Wait for the current network operation to finish before rebuilding");
            return;
        }
        setControlsReady(false);
        lastResult = "Rebuilding real WebRTC rooms";
        networkExecutor.execute(() -> {
            try {
                connectSignalingPool();
                closeCurrentNetwork();
                buildNetwork(peerCount, maxDirectPeers);
            } catch (Throwable error) {
                closeCurrentNetwork();
                lastResult = "Rebuild failed: " + rootMessage(error);
                log("REBUILD FAILED: " + rootMessage(error));
                error.printStackTrace();
            } finally {
                sendBusy.set(false);
                SwingUtilities.invokeLater(() -> setControlsReady(!peers.isEmpty()));
            }
        });
    }

    private void startNetwork() {
        networkExecutor.execute(() -> {
            try {
                connectSignalingPool();
                buildNetwork(options.peerCount, options.maxDirectPeers);
                if (options.verify) {
                    scheduler.scheduleWithFixedDelay(this::triggerVerificationSend, 4L, 5L, TimeUnit.SECONDS);
                }
                scheduler.scheduleWithFixedDelay(this::reportTopology, 2L, 3L, TimeUnit.SECONDS);
                if (options.verify) {
                    scheduler.schedule(
                        () -> {
                            if (!verified.get() || !broadcastVerified.get()) {
                                log(
                                    "VERIFY FAILED: real multi-hop send/broadcast did not both complete within " +
                                    options.timeoutSeconds +
                                    "s"
                                );
                                shutdown(2);
                            }
                        },
                        options.timeoutSeconds,
                        TimeUnit.SECONDS
                    );
                }
            } catch (Throwable error) {
                closeCurrentNetwork();
                lastResult = "Startup failed: " + rootMessage(error);
                log("STARTUP FAILED: " + rootMessage(error));
                error.printStackTrace();
                if (options.verify) {
                    shutdown(2);
                } else {
                    SwingUtilities.invokeLater(() -> {
                        peerSpinner.setEnabled(true);
                        directConnectionsSpinner.setEnabled(true);
                        rebuildButton.setEnabled(true);
                    });
                }
            }
        });
    }

    private void connectSignalingPool() throws Exception {
        if (pool != null) return;
        log("Connecting Nostr signaling pool to " + options.relay);
        NostrPool newPool = new NostrPool();
        try {
            newPool.addRelay(new NostrRelay(options.relay)).await();
            pool = newPool;
            SwingUtilities.invokeLater(() -> relayStatus.setText(options.relay + " · signaling connected"));
        } catch (Exception error) {
            newPool.close();
            throw error;
        }
    }

    private void buildNetwork(int peerCount, int maxDirectPeers) throws Exception {
        configuredPeerCount = peerCount;
        configuredMaxDirectPeers = maxDirectPeers;
        verified.set(false);
        broadcastVerified.set(false);
        pendingTransfers.clear();
        broadcastReceivers.clear();
        highlightedRoute = null;
        highlightedBroadcast = null;
        lastTopologySummary = "";

        roomKeys =
            options.roomPrivateKey == null
                ? new NostrKeyPair()
                : new NostrKeyPair(NostrPrivateKey.fromHex(options.roomPrivateKey));
        routingScope = new RoutingScope(roomKeys.getPublicKey(), PROTOCOL_ID, APPLICATION_ID);
        turnPool = new NostrTURNPool();
        RTCSettings settings = RTCSettings.DEFAULT.withMaxDirectPeers(maxDirectPeers);
        long generation = networkSequence.incrementAndGet();

        for (int index = 0; index < peerCount; index++) {
            createPeer(index, generation, settings);
        }
        SwingUtilities.invokeLater(this::populatePeerSelectors);
        for (DemoPeer peer : peers) {
            peer.room.start().await();
            log(peer.name + " announced dc4 presence " + shortId(peer.nodeId));
        }

        lastResult = "Waiting for real WebRTC links";
        log("Room " + roomKeys.getPublicKey().asHex() + " · " + peerCount + " peers · maxDirectPeers=" + maxDirectPeers);
        SwingUtilities.invokeLater(() -> setControlsReady(true));
    }

    private void closeCurrentNetwork() {
        if (!peers.isEmpty()) log("Closing " + peers.size() + " current WebRTC rooms");
        ArrayList<DemoPeer> previousPeers = new ArrayList<DemoPeer>(peers);
        peers.clear();
        peersByIdentity.clear();
        pendingTransfers.clear();
        broadcastReceivers.clear();
        highlightedRoute = null;
        highlightedBroadcast = null;
        routingScope = null;
        for (DemoPeer peer : previousPeers) {
            try {
                peer.room.close();
            } catch (Throwable ignored) {}
        }
        NostrTURNPool previousTurnPool = turnPool;
        turnPool = null;
        if (previousTurnPool != null) previousTurnPool.close();
        NostrKeyPair previousRoomKeys = roomKeys;
        roomKeys = null;
        if (previousRoomKeys != null) previousRoomKeys.close();
        SwingUtilities.invokeLater(this::populatePeerSelectors);
    }

    private void setControlsReady(boolean networkReady) {
        boolean configurable = !options.verify && !closing.get() && !sendBusy.get();
        peerSpinner.setEnabled(configurable);
        directConnectionsSpinner.setEnabled(configurable);
        rebuildButton.setEnabled(configurable);
        sender.setEnabled(networkReady);
        recipient.setEnabled(networkReady);
        message.setEnabled(networkReady);
        sendButton.setEnabled(networkReady && !closing.get() && !sendBusy.get());
        broadcastButton.setEnabled(networkReady && !closing.get() && !sendBusy.get());
    }

    private void populatePeerSelectors() {
        Object previousSender = sender.getSelectedItem();
        Object previousRecipient = recipient.getSelectedItem();
        sender.removeAllItems();
        recipient.removeAllItems();
        for (DemoPeer peer : peers) {
            sender.addItem(peer.name);
            recipient.addItem(peer.name);
        }
        restoreSelection(sender, previousSender, 0);
        restoreSelection(recipient, previousRecipient, Math.min(1, Math.max(0, peers.size() - 1)));
    }

    private static void restoreSelection(JComboBox<String> selector, Object previous, int fallbackIndex) {
        if (previous != null) selector.setSelectedItem(previous);
        if (selector.getSelectedIndex() < 0 && selector.getItemCount() > 0) {
            selector.setSelectedIndex(Math.min(fallbackIndex, selector.getItemCount() - 1));
        }
    }

    private void createPeer(int index, long generation, RTCSettings settings) {
        NostrKeyPair identity = new NostrKeyPair();
        NostrRTCLocalPeer local = new NostrRTCLocalPeer(
            new NostrKeyPairSigner(identity),
            options.stunServers,
            APPLICATION_ID,
            PROTOCOL_ID,
            "onion-" + roomKeys.getPublicKey().asHex().substring(0, 10) + "-" + generation + "-" + index,
            roomKeys,
            null
        );
        NostrRTCRoom room = new NostrRTCRoom(settings, local, roomKeys, pool, null, turnPool);
        DemoPeer demoPeer = new DemoPeer(index, "P" + (index + 1), local, room);
        peers.add(demoPeer);
        peersByIdentity.put(identityKey(local), demoPeer);

        room.addPeerDiscoveryListener((remote, announce, state) -> repaintSoon());
        room.addPeerSocketAvailableListener((remote, socket) -> {
            if (demoPeer.observedSockets.add(socket)) {
                socket.addListener(new SocketObserver(demoPeer, remote));
            }
            repaintSoon();
        });
        room.addDisconnectionListener((remote, socket) -> repaintSoon());
        room.addMessageListener((remote, socket, channel, payload, turn) -> onApplicationMessage(demoPeer, remote, payload));
    }

    private void onApplicationMessage(DemoPeer receiver, NostrRTCPeer remote, ByteBuffer payload) {
        if (!peers.contains(receiver)) return;
        String message = StandardCharsets.UTF_8.decode(payload.asReadOnlyBuffer()).toString();
        String[] fields = message.split("\\|", 6);
        if (fields.length < 5 || !MESSAGE_PREFIX.equals(fields[0])) {
            log(receiver.name + " received " + payload.remaining() + " application bytes");
            return;
        }
        long id;
        try {
            id = Long.parseLong(fields[2]);
        } catch (NumberFormatException ignored) {
            return;
        }
        String kind = fields[1];
        String applicationPayload = fields.length == 6 ? fields[5] : "";
        receiver.received.incrementAndGet();
        PendingTransfer pending = pendingTransfers.remove(id);
        if ("route".equals(kind)) {
            int hops = pending == null ? 0 : pending.route.getHopCount();
            lastResult = receiver.name + " received routed #" + id + " across " + hops + " WebRTC hops";
            log(
                "DELIVERED #" +
                id +
                " to " +
                receiver.name +
                " from " +
                shortPeer(remote) +
                " · hops=" +
                hops +
                " · payload=\"" +
                applicationPayload +
                "\""
            );
            if (pending != null && pending.destination.equals(receiver.nodeId) && pending.route.getHopCount() > 1) {
                verified.set(true);
                log("VERIFIED: nostr4j onion payload crossed real WebRTC DataChannels " + describe(pending.route));
                if (options.verify) {
                    scheduler.schedule(() -> triggerBroadcast(), 800L, TimeUnit.MILLISECONDS);
                }
            }
        } else if ("broadcast".equals(kind)) {
            lastResult = receiver.name + " received broadcast #" + id;
            log("BROADCAST #" + id + " reached " + receiver.name + " · payload=\"" + applicationPayload + "\"");
            Set<NodeId> receivers = broadcastReceivers.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet());
            receivers.add(receiver.nodeId);
            if (
                options.verify &&
                verified.get() &&
                receivers.size() == Math.max(0, peers.size() - 1) &&
                broadcastVerified.compareAndSet(false, true)
            ) {
                log("VERIFIED: broadcast #" + id + " reached every other peer over the real WebRTC tree");
                broadcastReceivers.remove(id);
                if (verificationStage.compareAndSet(0, 1)) {
                    scheduler.schedule(this::verifyRebuildControls, 1200L, TimeUnit.MILLISECONDS);
                } else {
                    log("VERIFIED: rebuilt controls produced a second working real WebRTC onion network");
                    scheduler.schedule(() -> shutdown(0), 1500L, TimeUnit.MILLISECONDS);
                }
            }
        }
        repaintSoon();
    }

    private void verifyRebuildControls() {
        if (closing.get()) return;
        if (sendBusy.get()) {
            scheduler.schedule(this::verifyRebuildControls, 500L, TimeUnit.MILLISECONDS);
            return;
        }
        int rebuiltPeerCount = configuredPeerCount > 3 ? configuredPeerCount - 1 : configuredPeerCount + 1;
        int rebuiltMaxDirectPeers = Math.min(configuredMaxDirectPeers, rebuiltPeerCount - 1);
        log(
            "VERIFY CONTROLS: changing peer spinner " +
            configuredPeerCount +
            " → " +
            rebuiltPeerCount +
            " and rebuilding real WebRTC rooms"
        );
        SwingUtilities.invokeLater(() -> {
            peerSpinner.setValue(Integer.valueOf(rebuiltPeerCount));
            directConnectionsSpinner.setValue(Integer.valueOf(rebuiltMaxDirectPeers));
            requestRebuild();
        });
    }

    private void triggerVerificationSend() {
        if (closing.get() || verified.get() || sendBusy.get()) return;
        ActiveTopology topology = captureTopology();
        SelectedRoute selected = selectRoomRoute(topology);
        if (selected == null) return;
        DemoPeer destination = topology.peersByNode.get(selected.route.getDestination());
        if (destination == null) return;
        SwingUtilities.invokeLater(() -> {
            if (closing.get() || verified.get() || sendBusy.get()) return;
            sender.setSelectedItem(selected.source.name);
            recipient.setSelectedItem(destination.name);
            message.setText("Verification payload selected by the controls");
            log("VERIFY CONTROLS: selected sender=" + selected.source.name + " recipient=" + destination.name);
            triggerRoutedSend();
        });
    }

    private void triggerRoutedSend() {
        String requestedSource = (String) sender.getSelectedItem();
        String requestedDestination = (String) recipient.getSelectedItem();
        String requestedPayload = message.getText();
        if (requestedSource == null || requestedDestination == null) {
            log("Choose a sender and recipient");
            return;
        }
        if (requestedSource.equals(requestedDestination)) {
            log("Choose two different peers");
            lastResult = "Sender and recipient must be different";
            return;
        }
        if (closing.get() || !sendBusy.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> setControlsReady(false));
        networkExecutor.execute(() -> {
            boolean handedOff = false;
            try {
                ActiveTopology topology = captureTopology();
                SelectedRoute selected = selectRoomRoute(topology, requestedSource, requestedDestination);
                if (selected == null) {
                    log(
                        "No active route from " +
                        requestedSource +
                        " to " +
                        requestedDestination +
                        " yet; waiting for mutually attested WebRTC links"
                    );
                    lastResult =
                        "Converging: " +
                        topology.activeEdgeCount() +
                        " onion-ready WebRTC links; waiting for private dc4 attestations/channels";
                    return;
                }
                RoutePath route = selected.route;
                DemoPeer source = selected.source;
                DemoPeer destination = topology.peersByNode.get(route.getDestination());
                NostrRTCPeer remote = findRemotePeer(source.room, destination.localPeer);
                if (remote == null) {
                    lastResult = "Logical membership is still converging";
                    return;
                }
                NostrRTCSocket destinationSocket = source.room.getSocket(remote);
                if (destinationSocket == null) {
                    lastResult = "Waiting for the selected logical destination socket";
                    return;
                }
                NostrRTCChannel logicalChannel = destinationSocket.getChannel(NostrRTCSocket.DEFAULT_CHANNEL_NAME);
                boolean routeReady = logicalChannel != null && logicalChannel.isReady();
                if (route.getHopCount() > 1) {
                    InternalRoutedTransport routedTransport = destinationSocket.getRoutedTransport();
                    routeReady =
                        routeReady &&
                        routedTransport != null &&
                        routedTransport.isRouteReady(logicalChannel) &&
                        routedTransport.shouldUseRoute(logicalChannel);
                }
                if (!routeReady) {
                    lastResult = "Selected path is visible but its logical channel is not ready";
                    log(lastResult);
                    return;
                }

                long id = transferSequence.incrementAndGet();
                PendingTransfer transfer = new PendingTransfer(id, source.nodeId, destination.nodeId, route);
                pendingTransfers.put(id, transfer);
                highlightedRoute = route;
                highlightedBroadcast = null;
                highlightedAtNanos = System.nanoTime();
                String message =
                    MESSAGE_PREFIX +
                    "|route|" +
                    id +
                    "|" +
                    source.nodeId.asHex() +
                    "|" +
                    destination.nodeId.asHex() +
                    "|" +
                    requestedPayload;
                log(
                    "SEND #" +
                    id +
                    " " +
                    source.name +
                    " → " +
                    destination.name +
                    " via " +
                    describe(route) +
                    " · payload=\"" +
                    requestedPayload +
                    "\""
                );
                handedOff = true;
                source.room
                    .send(remote, ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)))
                    .then(ignored -> {
                        log("ACK #" + id + " end-to-end delivery acknowledged");
                        finishSend();
                        return null;
                    })
                    .catchException(error -> {
                        pendingTransfers.remove(id);
                        lastResult = "Route attempt failed: " + rootMessage(error);
                        log("RETRY #" + id + " failed while topology converges: " + rootMessage(error));
                        finishSend();
                    });
            } catch (Throwable error) {
                lastResult = "Route attempt failed: " + rootMessage(error);
                log("ROUTED SEND FAILED: " + rootMessage(error));
            } finally {
                if (!handedOff) finishSend();
            }
        });
    }

    private void triggerBroadcast() {
        String sourceName = peers.isEmpty() ? null : peers.get(0).name;
        SwingUtilities.invokeLater(() -> {
            if (sourceName == null || closing.get()) return;
            sender.setSelectedItem(sourceName);
            message.setText("Verification broadcast payload selected by the controls");
            log("VERIFY CONTROLS: selected broadcast sender=" + sourceName);
            triggerBroadcast((String) sender.getSelectedItem(), message.getText());
        });
    }

    private void triggerBroadcast(String sourceName, String applicationPayload) {
        if (sourceName == null) {
            log("Choose the broadcast sender");
            return;
        }
        if (closing.get() || !sendBusy.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> setControlsReady(false));
        networkExecutor.execute(() -> {
            boolean handedOff = false;
            try {
                ActiveTopology topology = captureTopology();
                if (topology.graph.connectedComponents().size() != 1 || peers.isEmpty()) {
                    log("Broadcast waits for one connected, mutually attested topology");
                    return;
                }
                DemoPeer source = findPeer(sourceName);
                if (source == null) {
                    log("The selected broadcast sender is no longer part of the current room");
                    return;
                }
                highlightedRoute = null;
                highlightedBroadcast = new BroadcastTreeBuilder().build(topology.graph, source.nodeId);
                highlightedAtNanos = System.nanoTime();
                long id = transferSequence.incrementAndGet();
                String message =
                    MESSAGE_PREFIX +
                    "|broadcast|" +
                    id +
                    "|" +
                    source.nodeId.asHex() +
                    "|" +
                    Instant.now().toEpochMilli() +
                    "|" +
                    applicationPayload;
                broadcastReceivers.put(id, ConcurrentHashMap.newKeySet());
                log(
                    "BROADCAST #" +
                    id +
                    " from " +
                    source.name +
                    " through nostr4j's WebRTC tree · payload=\"" +
                    applicationPayload +
                    "\""
                );
                handedOff = true;
                source.room
                    .broadcast(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)))
                    .then(ignored -> {
                        lastResult = "Broadcast #" + id + " forwarded over the onion tree";
                        finishSend();
                        return null;
                    })
                    .catchException(error -> {
                        broadcastReceivers.remove(id);
                        lastResult = "Broadcast failed: " + rootMessage(error);
                        log("BROADCAST FAILED: " + rootMessage(error));
                        finishSend();
                    });
            } catch (Throwable error) {
                lastResult = "Broadcast failed: " + rootMessage(error);
                log("BROADCAST FAILED: " + rootMessage(error));
            } finally {
                if (!handedOff) finishSend();
            }
        });
    }

    private void finishSend() {
        sendBusy.set(false);
        SwingUtilities.invokeLater(() -> setControlsReady(!peers.isEmpty()));
        repaintSoon();
    }

    static RoutePath chooseMultiHopRoute(TopologyGraph graph) {
        ArrayList<NodeId> nodes = new ArrayList<NodeId>(graph.getNodes());
        Collections.sort(nodes);
        RoutePath selected = null;
        for (NodeId source : nodes) {
            RoutePath candidate = chooseMultiHopRoute(graph, source);
            if (
                candidate != null &&
                (
                    selected == null ||
                    candidate.getHopCount() > selected.getHopCount() ||
                    (candidate.getHopCount() == selected.getHopCount() && compareRoute(candidate, selected) < 0)
                )
            ) {
                selected = candidate;
            }
        }
        return selected;
    }

    static RoutePath chooseMultiHopRoute(TopologyGraph graph, NodeId source) {
        if (!graph.getNodes().contains(source)) return null;
        WeightedRoutePlanner planner = new WeightedRoutePlanner();
        ArrayList<NodeId> nodes = new ArrayList<NodeId>(graph.getNodes());
        Collections.sort(nodes);
        RoutePath selected = null;
        for (NodeId destination : nodes) {
            if (source.equals(destination) || graph.findEdge(source, destination) != null) continue;
            List<RoutePath> routes = planner.plan(graph, source, destination, Instant.now());
            if (routes.isEmpty() || routes.get(0).getHopCount() < 2) continue;
            RoutePath candidate = routes.get(0);
            if (
                selected == null ||
                candidate.getHopCount() > selected.getHopCount() ||
                (candidate.getHopCount() == selected.getHopCount() && compareRoute(candidate, selected) < 0)
            ) {
                selected = candidate;
            }
        }
        return selected;
    }

    static RoutePath chooseRoute(TopologyGraph graph, NodeId source, NodeId destination) {
        if (source.equals(destination) || !graph.getNodes().contains(source) || !graph.getNodes().contains(destination)) {
            return null;
        }
        List<RoutePath> routes = new WeightedRoutePlanner().plan(graph, source, destination, Instant.now());
        return routes.isEmpty() ? null : routes.get(0);
    }

    private SelectedRoute selectRoomRoute(ActiveTopology active) {
        SelectedRoute selected = null;
        for (DemoPeer peer : peers) {
            RoutePath candidate = chooseMultiHopRoute(peer.room.getRoutingTopology(), peer.nodeId);
            if (candidate == null || !pathIsPhysicallyActive(candidate, active.graph)) continue;
            if (
                selected == null ||
                candidate.getHopCount() > selected.route.getHopCount() ||
                (candidate.getHopCount() == selected.route.getHopCount() && compareRoute(candidate, selected.route) < 0)
            ) {
                selected = new SelectedRoute(peer, candidate);
            }
        }
        return selected;
    }

    private SelectedRoute selectRoomRoute(ActiveTopology active, String sourceName, String destinationName) {
        DemoPeer source = findPeer(sourceName);
        DemoPeer destination = findPeer(destinationName);
        if (source == null || destination == null) return null;
        RoutePath route = chooseRoute(source.room.getRoutingTopology(), source.nodeId, destination.nodeId);
        return route == null || !pathIsPhysicallyActive(route, active.graph) ? null : new SelectedRoute(source, route);
    }

    private DemoPeer findPeer(String name) {
        for (DemoPeer peer : peers) {
            if (peer.name.equals(name)) return peer;
        }
        return null;
    }

    private static boolean pathIsPhysicallyActive(RoutePath route, TopologyGraph active) {
        List<NodeId> nodes = route.getNodes();
        for (int index = 0; index + 1 < nodes.size(); index++) {
            if (active.findEdge(nodes.get(index), nodes.get(index + 1)) == null) return false;
        }
        return true;
    }

    private static int compareRoute(RoutePath left, RoutePath right) {
        int count = Math.min(left.getNodes().size(), right.getNodes().size());
        for (int index = 0; index < count; index++) {
            int comparison = left.getNodes().get(index).compareTo(right.getNodes().get(index));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.getNodes().size(), right.getNodes().size());
    }

    private ActiveTopology captureTopology() {
        RoutingScope scope = routingScope;
        LinkedHashMap<NodeId, DemoPeer> byNode = new LinkedHashMap<NodeId, DemoPeer>();
        HashSet<NodeId> graphNodes = new HashSet<NodeId>();
        for (DemoPeer peer : peers) {
            byNode.put(peer.nodeId, peer);
            graphNodes.add(peer.nodeId);
        }

        HashMap<LinkKey, LinkAccumulator> accumulated = new HashMap<LinkKey, LinkAccumulator>();
        if (scope != null) {
            for (DemoPeer local : peers) {
                for (NostrRTCSocket socket : local.room.getSockets()) {
                    DemoPeer remote = peersByIdentity.get(identityKey(socket.getRemotePeer()));
                    if (remote == null || remote == local || !socket.isPhysicalLinkEnabled()) continue;
                    LinkKey key = new LinkKey(local.nodeId, remote.nodeId);
                    LinkAccumulator link = accumulated.computeIfAbsent(key, ignored -> new LinkAccumulator(key));
                    link.enabledSides.add(local.nodeId);
                    NostrRTCSocket.TransportPath path = socket.getActiveTransportPath();
                    if (path == NostrRTCSocket.TransportPath.RTC && socket.isRTCConnected()) {
                        link.rtcSides.add(local.nodeId);
                    } else if (path == NostrRTCSocket.TransportPath.TURN && socket.hasUsableTransport()) {
                        link.turnSides.add(local.nodeId);
                    }
                    NostrRTCChannel control = socket.getChannel(InternalRoutingChannels.CONTROL);
                    NostrRTCChannel data = socket.getChannel(
                        InternalRoutingChannels.data(RouteTransportProfile.RELIABLE_ORDERED)
                    );
                    if (control != null && control.isConnected() && data != null && data.isConnected()) {
                        link.internalChannelSides.add(local.nodeId);
                    }
                }
            }
        }

        ArrayList<LinkView> links = new ArrayList<LinkView>();
        HashSet<TopologyEdge> activeEdges = new HashSet<TopologyEdge>();
        for (LinkAccumulator link : accumulated.values()) {
            LinkState state = LinkState.PENDING;
            TopologyTransport transport = TopologyTransport.UNKNOWN;
            if (link.turnSides.size() == 2 && link.internalChannelSides.size() == 2) {
                state = LinkState.TURN;
                transport = TopologyTransport.TURN;
            } else if (link.rtcSides.size() == 2 && link.internalChannelSides.size() == 2) {
                state = LinkState.RTC;
                transport = TopologyTransport.RTC;
            }
            links.add(new LinkView(link.key, state));
            if (transport != TopologyTransport.UNKNOWN) {
                activeEdges.add(
                    new TopologyEdge(
                        EdgeId.derive(scope, link.key.first, link.key.second),
                        link.key.first,
                        link.key.second,
                        transport,
                        transport
                    )
                );
            }
        }
        links.sort(Comparator.comparing((LinkView link) -> link.key.first).thenComparing(link -> link.key.second));
        return new ActiveTopology(new TopologyGraph(graphNodes, activeEdges), byNode, links);
    }

    private void refreshUi() {
        ActiveTopology topology = captureTopology();
        int logical = 0;
        for (DemoPeer peer : peers) logical += peer.room.getPeers().size();
        int expectedLogical = peers.size() * Math.max(0, peers.size() - 1);
        status.setText(
            "peers " +
            peers.size() +
            "/" +
            configuredPeerCount +
            "  ·  max direct " +
            configuredMaxDirectPeers +
            "  ·  logical sockets " +
            logical +
            "/" +
            expectedLogical +
            "  ·  real RTC edges " +
            topology.activeEdgeCount() +
            "  ·  " +
            lastResult
        );
        topologyPanel.snapshot = topology;
        topologyPanel.repaint();
    }

    private void reportTopology() {
        ActiveTopology active = captureTopology();
        ArrayList<String> rooms = new ArrayList<String>();
        for (DemoPeer peer : peers) {
            TopologyGraph graph = peer.room.getRoutingTopology();
            Collection<TopologySnapshot> snapshots = peer.room.getRoutingTopologySnapshots();
            int neighborClaims = snapshots.stream().mapToInt(snapshot -> snapshot.getNeighbors().size()).sum();
            rooms.add(
                peer.name +
                "=" +
                graph.getNodes().size() +
                "n/" +
                graph.getEdges().size() +
                "e/" +
                snapshots.size() +
                "s/" +
                neighborClaims +
                "c"
            );
        }
        String summary = "TOPOLOGY real=" + active.activeEdgeCount() + "e · " + String.join(" ", rooms);
        if (!summary.equals(lastTopologySummary)) {
            lastTopologySummary = summary;
            log(summary);
        }
    }

    private void repaintSoon() {
        SwingUtilities.invokeLater(topologyPanel::repaint);
    }

    private void log(String message) {
        String line = String.format(Locale.ROOT, "%1$tH:%1$tM:%1$tS  %2$s%n", new java.util.Date(), message);
        System.out.print(line);
        System.out.flush();
        SwingUtilities.invokeLater(() -> {
            eventLog.append(line);
            eventLog.setCaretPosition(eventLog.getDocument().getLength());
        });
    }

    private void shutdown(int exitCode) {
        if (!closing.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> setControlsReady(false));
        Thread closer = daemonThread(
            () -> {
                if (options.verify) {
                    Thread haltWatchdog = daemonThread(
                        () -> {
                            try {
                                Thread.sleep(5000L);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            Runtime.getRuntime().halt(exitCode);
                        },
                        "onion-demo-exit-watchdog"
                    );
                    haltWatchdog.start();
                }
                log("Closing rooms and Nostr signaling");
                closeCurrentNetwork();
                NostrPool currentPool = pool;
                pool = null;
                if (currentPool != null) currentPool.close();
                scheduler.shutdownNow();
                networkExecutor.shutdownNow();
                SwingUtilities.invokeLater(() -> {
                    dispose();
                    System.exit(exitCode);
                });
            },
            "onion-demo-close"
        );
        closer.start();
    }

    private static NostrRTCPeer findRemotePeer(NostrRTCRoom room, NostrRTCPeer expected) {
        String identity = identityKey(expected);
        for (NostrRTCPeer peer : room.getPeers()) {
            if (identity.equals(identityKey(peer))) return peer;
        }
        return null;
    }

    private static String identityKey(NostrRTCPeer peer) {
        return peer.getPubkey().asHex() + "/" + peer.getSessionId();
    }

    private static String shortPeer(NostrRTCPeer peer) {
        return peer.getPubkey().asHex().substring(0, 8);
    }

    private static String shortId(NodeId node) {
        return node.asHex().substring(0, 8);
    }

    private String describe(RoutePath route) {
        ArrayList<String> names = new ArrayList<String>();
        Map<NodeId, DemoPeer> byNode = captureTopology().peersByNode;
        for (NodeId node : route.getNodes()) {
            DemoPeer peer = byNode.get(node);
            names.add(peer == null ? shortId(node) : peer.name);
        }
        return String.join(" → ", names) + " (" + route.getHopCount() + " hops)";
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    static final class Options {

        final String relay;
        final int peerCount;
        final int maxDirectPeers;
        final int timeoutSeconds;
        final boolean verify;
        final String roomPrivateKey;
        final Collection<String> stunServers;

        private Options(
            String relay,
            int peerCount,
            int maxDirectPeers,
            int timeoutSeconds,
            boolean verify,
            String roomPrivateKey,
            Collection<String> stunServers
        ) {
            this.relay = relay;
            this.peerCount = peerCount;
            this.maxDirectPeers = maxDirectPeers;
            this.timeoutSeconds = timeoutSeconds;
            this.verify = verify;
            this.roomPrivateKey = roomPrivateKey;
            this.stunServers = stunServers;
        }

        static Options parse(String[] args) {
            String relay = DEFAULT_RELAY;
            int peerCount = 8;
            int maxDirectPeers = 2;
            int timeoutSeconds = 120;
            boolean verify = false;
            String roomPrivateKey = null;
            Collection<String> stun = List.of("stun.cloudflare.com:3478", "stun.l.google.com:19302");
            for (String argument : args) {
                if ("--verify".equals(argument)) {
                    verify = true;
                } else if ("--no-stun".equals(argument)) {
                    stun = Collections.emptyList();
                } else if (argument.startsWith("--relay=")) {
                    relay = argument.substring("--relay=".length()).trim();
                } else if (argument.startsWith("--peers=")) {
                    peerCount = integer(argument, "--peers=");
                } else if (argument.startsWith("--max-direct=")) {
                    maxDirectPeers = integer(argument, "--max-direct=");
                } else if (argument.startsWith("--timeout=")) {
                    timeoutSeconds = integer(argument, "--timeout=");
                } else if (argument.startsWith("--room-key=")) {
                    roomPrivateKey = argument.substring("--room-key=".length()).trim();
                } else {
                    throw new IllegalArgumentException("Unknown OnionTopologyDemo argument: " + argument);
                }
            }
            if (!relay.startsWith("wss://") && !relay.startsWith("ws://")) {
                throw new IllegalArgumentException("Relay must use ws:// or wss://");
            }
            if (peerCount < 3 || peerCount > 64) {
                throw new IllegalArgumentException("Peer count must be between 3 and 64");
            }
            if (maxDirectPeers < RTCSettings.MIN_MAX_DIRECT_PEERS || maxDirectPeers > maximumDirectPeersFor(peerCount)) {
                throw new IllegalArgumentException("maxDirectPeers must be between 2 and peers - 1");
            }
            if (timeoutSeconds < 10) {
                throw new IllegalArgumentException("Timeout must be at least 10 seconds");
            }
            if (roomPrivateKey != null && !roomPrivateKey.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Room key must be a 64-character hexadecimal private key");
            }
            return new Options(relay, peerCount, maxDirectPeers, timeoutSeconds, verify, roomPrivateKey, List.copyOf(stun));
        }

        private static int integer(String argument, String prefix) {
            try {
                return Integer.parseInt(argument.substring(prefix.length()));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid integer argument: " + argument, error);
            }
        }
    }

    private final class SocketObserver implements NostrRTCSocketListener {

        private final DemoPeer local;
        private final NostrRTCPeer remote;

        private SocketObserver(DemoPeer local, NostrRTCPeer remote) {
            this.local = local;
            this.remote = remote;
        }

        @Override
        public void onRTCSocketRouteUpdate(
            NostrRTCSocket socket,
            Collection<RTCTransportIceCandidate> candidates,
            String turnServer
        ) {}

        @Override
        public void onRTCSocketClose(NostrRTCSocket socket) {
            repaintSoon();
        }

        @Override
        public void onRTCChannelReady(NostrRTCChannel channel) {
            repaintSoon();
        }

        @Override
        public void onRTCChannel(NostrRTCChannel channel) {
            repaintSoon();
        }

        @Override
        public void onRTCSocketTransportSwitch(
            NostrRTCSocket socket,
            NostrRTCSocket.TransportPath from,
            NostrRTCSocket.TransportPath to,
            String reason
        ) {
            log(local.name + " ↔ " + shortPeer(remote) + " transport " + from + " → " + to + " (" + reason + ")");
            repaintSoon();
        }

        @Override
        public void onRTCSocketTransportDegraded(NostrRTCSocket socket, NostrRTCSocket.TransportPath active, String reason) {
            log(local.name + " ↔ " + shortPeer(remote) + " degraded: " + reason);
            repaintSoon();
        }
    }

    private final class TopologyPanel extends JPanel {

        private volatile ActiveTopology snapshot = ActiveTopology.empty();

        private TopologyPanel() {
            setOpaque(true);
            setBackground(BACKGROUND);
            setMinimumSize(new Dimension(600, 440));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ActiveTopology topology = snapshot;
                Map<NodeId, Point> positions = positions(topology.peersByNode.keySet(), getWidth(), getHeight());
                drawLinks(g, topology.links, positions);
                drawHighlightedRoute(g, positions);
                drawHighlightedBroadcast(g, positions);
                drawNodes(g, topology, positions);
                drawLegend(g);
            } finally {
                g.dispose();
            }
        }

        private void drawLinks(Graphics2D g, List<LinkView> links, Map<NodeId, Point> positions) {
            for (LinkView link : links) {
                Point first = positions.get(link.key.first);
                Point second = positions.get(link.key.second);
                if (first == null || second == null) continue;
                if (link.state == LinkState.PENDING) {
                    g.setColor(PENDING);
                    g.setStroke(
                        new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] { 6f, 7f }, 0f)
                    );
                } else {
                    g.setColor(link.state == LinkState.TURN ? TURN : RTC);
                    g.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                }
                g.drawLine(first.x, first.y, second.x, second.y);
            }
        }

        private void drawHighlightedRoute(Graphics2D g, Map<NodeId, Point> positions) {
            RoutePath route = highlightedRoute;
            if (route == null || route.getHopCount() == 0) return;
            long elapsed = System.nanoTime() - highlightedAtNanos;
            long hopDuration = TimeUnit.MILLISECONDS.toNanos(Math.max(1, visualHopDelayMillis));
            long routeDuration = hopDuration * route.getHopCount();
            if (elapsed > routeDuration + TimeUnit.SECONDS.toNanos(3)) return;
            g.setColor(ONION);
            g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            List<NodeId> nodes = route.getNodes();
            for (int index = 0; index + 1 < nodes.size(); index++) {
                Point first = positions.get(nodes.get(index));
                Point second = positions.get(nodes.get(index + 1));
                if (first != null && second != null) g.drawLine(first.x, first.y, second.x, second.y);
            }
            double edgePosition = Math.min(route.getHopCount(), (double) elapsed / hopDuration);
            int edgeIndex = Math.min(route.getHopCount() - 1, (int) edgePosition);
            double fraction = edgePosition >= route.getHopCount() ? 1d : edgePosition - edgeIndex;
            Point first = positions.get(nodes.get(edgeIndex));
            Point second = positions.get(nodes.get(edgeIndex + 1));
            if (first != null && second != null) {
                int x = (int) Math.round(first.x + (second.x - first.x) * fraction);
                int y = (int) Math.round(first.y + (second.y - first.y) * fraction);
                g.setColor(Color.WHITE);
                g.fillOval(x - 6, y - 6, 12, 12);
            }
        }

        private void drawHighlightedBroadcast(Graphics2D g, Map<NodeId, Point> positions) {
            BroadcastTree tree = highlightedBroadcast;
            if (tree == null) return;
            long elapsed = System.nanoTime() - highlightedAtNanos;
            long hopDuration = TimeUnit.MILLISECONDS.toNanos(Math.max(1, visualHopDelayMillis));
            int maximumDepth = 0;
            for (NodeId node : tree.getNodes()) maximumDepth = Math.max(maximumDepth, tree.getDepth(node));
            if (elapsed > hopDuration * maximumDepth + TimeUnit.SECONDS.toNanos(3)) return;
            double visibleDepth = Math.min(maximumDepth, (double) elapsed / hopDuration);
            g.setColor(ONION);
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (NodeId child : tree.getNodes()) {
                NodeId parent = tree.getParent(child);
                if (parent == null || tree.getDepth(child) > visibleDepth + 1d) continue;
                Point first = positions.get(parent);
                Point second = positions.get(child);
                if (first == null || second == null) continue;
                double fraction = Math.min(1d, Math.max(0d, visibleDepth - tree.getDepth(parent)));
                int x = (int) Math.round(first.x + (second.x - first.x) * fraction);
                int y = (int) Math.round(first.y + (second.y - first.y) * fraction);
                g.drawLine(first.x, first.y, x, y);
            }
        }

        private void drawNodes(Graphics2D g, ActiveTopology topology, Map<NodeId, Point> positions) {
            for (Map.Entry<NodeId, DemoPeer> entry : topology.peersByNode.entrySet()) {
                DemoPeer peer = entry.getValue();
                Point point = positions.get(entry.getKey());
                int logicalPeers = peer.room.getPeers().size();
                boolean discoveredAll = logicalPeers == Math.max(0, peers.size() - 1);
                g.setColor(discoveredAll ? new Color(38, 61, 82) : new Color(57, 47, 38));
                g.fillOval(point.x - 28, point.y - 28, 56, 56);
                g.setColor(discoveredAll ? RTC : ONION);
                g.setStroke(new BasicStroke(2.5f));
                g.drawOval(point.x - 28, point.y - 28, 56, 56);
                g.setFont(getFont().deriveFont(Font.BOLD, 14f));
                center(g, peer.name, point.x, point.y + 5, TEXT);
                g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                center(g, shortId(peer.nodeId), point.x, point.y + 44, MUTED);
                center(g, "rx " + peer.received.get(), point.x, point.y + 59, MUTED);
            }
        }

        private void drawLegend(Graphics2D g) {
            int x = 18;
            int y = 25;
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            legend(g, x, y, RTC, "real WebRTC");
            legend(g, x + 125, y, PENDING, "selected / connecting");
            legend(g, x + 300, y, ONION, "active onion route");
        }

        private void legend(Graphics2D g, int x, int y, Color color, String label) {
            g.setColor(color);
            g.fillRoundRect(x, y - 8, 22, 5, 4, 4);
            g.setColor(MUTED);
            g.drawString(label, x + 30, y);
        }

        private Map<NodeId, Point> positions(Collection<NodeId> nodeIds, int width, int height) {
            ArrayList<NodeId> sorted = new ArrayList<NodeId>(nodeIds);
            Collections.sort(sorted);
            HashMap<NodeId, Point> result = new HashMap<NodeId, Point>();
            double radius = Math.max(120d, Math.min(width * 0.38d, height * 0.35d));
            double centerX = width / 2d;
            double centerY = height / 2d + 15d;
            for (int index = 0; index < sorted.size(); index++) {
                double angle = -Math.PI / 2d + (Math.PI * 2d * index / Math.max(1, sorted.size()));
                result.put(
                    sorted.get(index),
                    new Point(
                        (int) Math.round(centerX + Math.cos(angle) * radius),
                        (int) Math.round(centerY + Math.sin(angle) * radius)
                    )
                );
            }
            return result;
        }

        private void center(Graphics2D g, String value, int centerX, int baseline, Color color) {
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(color);
            g.drawString(value, centerX - metrics.stringWidth(value) / 2, baseline);
        }
    }

    private final class DemoPeer {

        final int index;
        final String name;
        final NostrRTCLocalPeer localPeer;
        final NostrRTCRoom room;
        final NodeId nodeId;
        final Set<NostrRTCSocket> observedSockets = ConcurrentHashMap.newKeySet();
        final AtomicLong received = new AtomicLong();

        private DemoPeer(int index, String name, NostrRTCLocalPeer localPeer, NostrRTCRoom room) {
            this.index = index;
            this.name = name;
            this.localPeer = localPeer;
            this.room = room;
            this.nodeId = NodeId.derive(routingScope, localPeer.getPubkey(), localPeer.getSessionId());
        }
    }

    private enum LinkState {
        PENDING,
        RTC,
        TURN,
    }

    private static final class LinkKey {

        final NodeId first;
        final NodeId second;

        private LinkKey(NodeId left, NodeId right) {
            if (left.compareTo(right) <= 0) {
                first = left;
                second = right;
            } else {
                first = right;
                second = left;
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LinkKey && first.equals(((LinkKey) other).first) && second.equals(((LinkKey) other).second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }

    private static final class LinkAccumulator {

        final LinkKey key;
        final Set<NodeId> enabledSides = new HashSet<NodeId>();
        final Set<NodeId> rtcSides = new HashSet<NodeId>();
        final Set<NodeId> turnSides = new HashSet<NodeId>();
        final Set<NodeId> internalChannelSides = new HashSet<NodeId>();

        private LinkAccumulator(LinkKey key) {
            this.key = key;
        }
    }

    private static final class LinkView {

        final LinkKey key;
        final LinkState state;

        private LinkView(LinkKey key, LinkState state) {
            this.key = key;
            this.state = state;
        }
    }

    private static final class ActiveTopology {

        final TopologyGraph graph;
        final Map<NodeId, DemoPeer> peersByNode;
        final List<LinkView> links;

        private ActiveTopology(TopologyGraph graph, Map<NodeId, DemoPeer> peersByNode, List<LinkView> links) {
            this.graph = graph;
            this.peersByNode = Collections.unmodifiableMap(new LinkedHashMap<NodeId, DemoPeer>(peersByNode));
            this.links = Collections.unmodifiableList(new ArrayList<LinkView>(links));
        }

        static ActiveTopology empty() {
            return new ActiveTopology(
                new TopologyGraph(Collections.emptySet(), Collections.emptySet()),
                Collections.emptyMap(),
                Collections.emptyList()
            );
        }

        int activeEdgeCount() {
            return graph.getEdges().size();
        }
    }

    private static final class PendingTransfer {

        final long id;
        final NodeId source;
        final NodeId destination;
        final RoutePath route;

        private PendingTransfer(long id, NodeId source, NodeId destination, RoutePath route) {
            this.id = id;
            this.source = source;
            this.destination = destination;
            this.route = route;
        }
    }

    private static final class SelectedRoute {

        final DemoPeer source;
        final RoutePath route;

        private SelectedRoute(DemoPeer source, RoutePath route) {
            this.source = source;
            this.route = route;
        }
    }

    private static final class Point {

        final int x;
        final int y;

        private Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
