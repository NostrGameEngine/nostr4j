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
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import org.ngengine.nostr4j.rtc.OnionTopologyDemo.DemoTopology;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastTree;
import org.ngengine.nostr4j.rtc.routing.topology.DesiredDirectEdge;
import org.ngengine.nostr4j.rtc.routing.topology.OverlayEdgePriority;

/**
 * Interactive Swing simulator for dc4 topology, unicast onion routes, and
 * broadcast propagation.
 */
public final class OnionTopologyWindow {

    private static final Color BACKGROUND = new Color(11, 16, 22);
    private static final Color PANEL = new Color(19, 27, 35);
    private static final Color FIELD = new Color(28, 39, 49);
    private static final Color EDGE = new Color(47, 63, 76);
    private static final Color TEXT = new Color(225, 235, 241);
    private static final Color MUTED = new Color(137, 157, 169);
    private static final Color CYAN = new Color(69, 211, 198);
    private static final Color ORANGE = new Color(255, 173, 77);
    private static final Color VIOLET = new Color(155, 135, 245);

    private OnionTopologyWindow() {}

    public static void show(DemoTopology initialTopology) {
        SwingUtilities.invokeLater(() -> {
            installTheme();
            JFrame frame = new JFrame("nostr4j · onion routing simulator");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(createContent(initialTopology));
            frame.setMinimumSize(new Dimension(1180, 760));
            frame.setPreferredSize(new Dimension(1480, 920));
            frame.pack();
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        });
    }

    static JComponent createContent(DemoTopology initialTopology) {
        return new DemoPanel(initialTopology);
    }

    private static void installTheme() {
        UIManager.put("Panel.background", PANEL);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("TextField.background", FIELD);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextArea.background", BACKGROUND);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("ComboBox.background", FIELD);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("Spinner.background", FIELD);
        UIManager.put("Button.background", FIELD);
        UIManager.put("Button.foreground", TEXT);
    }

    private static final class DemoPanel extends JPanel {

        private DemoTopology topology;
        private final SpinnerNumberModel peerModel;
        private final SpinnerNumberModel directConnectionsModel;
        private final JSpinner peerSpinner;
        private final JSpinner directConnectionsSpinner;
        private final JComboBox<String> sender = new JComboBox<String>();
        private final JComboBox<String> recipient = new JComboBox<String>();
        private final JTextField message = new JTextField("Hello over onion routing");
        private final JSlider hopDelay = new JSlider(150, 1800, 750);
        private final JLabel topologySummary = new JLabel();
        private final JLabel status = new JLabel();
        private final JTextArea eventLog = new JTextArea();
        private final GraphCanvas canvas = new GraphCanvas();
        private final Timer animationTimer;

        private Animation animation;
        private int nextFrame;
        private final Set<String> completedEdges = new LinkedHashSet<String>();
        private List<Link> activeEdges = Collections.emptyList();
        private final Set<NodeId> reachedNodes = new LinkedHashSet<NodeId>();

        private DemoPanel(DemoTopology initialTopology) {
            super(new BorderLayout());
            this.topology = initialTopology;
            int peers = initialTopology.getPlan().getNodes().size();
            this.peerModel = new SpinnerNumberModel(peers, 3, 64, 1);
            this.directConnectionsModel =
                new SpinnerNumberModel(initialTopology.getMaxDirectPeers(), 2, Math.max(2, peers - 1), 1);
            this.peerSpinner = new JSpinner(peerModel);
            this.directConnectionsSpinner = new JSpinner(directConnectionsModel);
            this.animationTimer = new Timer(hopDelay.getValue(), ignored -> advanceAnimation());
            this.animationTimer.setInitialDelay(0);
            peerSpinner.setName("peerCount");
            directConnectionsSpinner.setName("maxDirectConnections");
            sender.setName("sender");
            recipient.setName("recipient");
            message.setName("payload");
            hopDelay.setName("hopDelay");
            eventLog.setName("eventLog");

            setBackground(BACKGROUND);
            setBorder(new EmptyBorder(0, 0, 0, 0));
            add(canvas, BorderLayout.CENTER);
            add(buildControls(), BorderLayout.EAST);

            peerSpinner.addChangeListener(ignored -> updateDirectConnectionsMaximum());
            hopDelay.addChangeListener(ignored -> animationTimer.setDelay(hopDelay.getValue()));
            populatePeerSelectors();
            resetAnimation("Network ready. Choose a sender and recipient.");
        }

        private JComponent buildControls() {
            JPanel controls = new JPanel();
            controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
            controls.setPreferredSize(new Dimension(390, 820));
            controls.setBorder(new EmptyBorder(24, 22, 22, 22));
            controls.setBackground(PANEL);

            JLabel title = new JLabel("NETWORK CONTROL");
            title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
            title.setForeground(CYAN);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(title);
            controls.add(Box.createVerticalStrut(8));

            JLabel help = new JLabel(
                "<html>Rebuild the physical links, then watch the message travel through the network hop by hop.</html>"
            );
            help.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            help.setForeground(MUTED);
            help.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(help);
            controls.add(Box.createVerticalStrut(20));

            controls.add(section("TOPOLOGY"));
            controls.add(labeled("Peers in the room", peerSpinner));
            controls.add(labeled("Max direct connections per peer", directConnectionsSpinner));
            JButton rebuild = button("Rebuild and reconnect", CYAN);
            rebuild.setName("rebuildTopology");
            rebuild.addActionListener(ignored -> rebuildTopology());
            controls.add(rebuild);
            controls.add(Box.createVerticalStrut(10));
            topologySummary.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            topologySummary.setForeground(MUTED);
            topologySummary.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(topologySummary);
            controls.add(Box.createVerticalStrut(20));

            controls.add(section("MESSAGE"));
            controls.add(labeled("Sender", sender));
            controls.add(labeled("Recipient", recipient));
            controls.add(labeled("Payload", message));
            JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
            actions.setOpaque(false);
            actions.setAlignmentX(Component.LEFT_ALIGNMENT);
            actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            JButton unicast = button("Send to peer", ORANGE);
            JButton broadcast = button("Broadcast", VIOLET);
            unicast.setName("sendUnicast");
            broadcast.setName("sendBroadcast");
            unicast.addActionListener(ignored -> startUnicast());
            broadcast.addActionListener(ignored -> startBroadcast());
            actions.add(unicast);
            actions.add(broadcast);
            controls.add(actions);
            controls.add(Box.createVerticalStrut(18));

            controls.add(section("SPEED"));
            hopDelay.setOpaque(false);
            hopDelay.setMajorTickSpacing(550);
            hopDelay.setPaintTicks(true);
            hopDelay.setToolTipText("Delay between consecutive hops");
            hopDelay.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(hopDelay);
            JLabel delayLabel = new JLabel("150 ms  ← fast        slow →  1800 ms");
            delayLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            delayLabel.setForeground(MUTED);
            delayLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(delayLabel);
            controls.add(Box.createVerticalStrut(18));

            status.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            status.setForeground(TEXT);
            status.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(status);
            controls.add(Box.createVerticalStrut(10));

            eventLog.setEditable(false);
            eventLog.setLineWrap(true);
            eventLog.setWrapStyleWord(true);
            eventLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            eventLog.setMargin(new Insets(8, 8, 8, 8));
            JScrollPane logScroll = new JScrollPane(eventLog);
            logScroll.setBorder(BorderFactory.createLineBorder(EDGE));
            logScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
            logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            controls.add(logScroll);
            return controls;
        }

        private JLabel section(String text) {
            JLabel label = new JLabel(text);
            label.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
            label.setForeground(VIOLET);
            label.setBorder(new EmptyBorder(0, 0, 7, 0));
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            return label;
        }

        private JComponent labeled(String labelText, JComponent component) {
            JPanel row = new JPanel(new BorderLayout(0, 5));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 61));
            JLabel label = new JLabel(labelText);
            label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            label.setForeground(MUTED);
            row.add(label, BorderLayout.NORTH);
            component.setPreferredSize(new Dimension(330, 32));
            row.add(component, BorderLayout.CENTER);
            row.setBorder(new EmptyBorder(0, 0, 9, 0));
            return row;
        }

        private JButton button(String text, Color accent) {
            JButton button = new JButton(text);
            button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            button.setForeground(TEXT);
            button.setBackground(FIELD);
            button.setBorder(
                BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent), new EmptyBorder(8, 11, 8, 11))
            );
            button.setFocusPainted(false);
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            return button;
        }

        private void updateDirectConnectionsMaximum() {
            int peerCount = ((Number) peerModel.getNumber()).intValue();
            int maximum = Math.max(2, peerCount - 1);
            directConnectionsModel.setMaximum(Integer.valueOf(maximum));
            if (((Number) directConnectionsModel.getNumber()).intValue() > maximum) {
                directConnectionsModel.setValue(Integer.valueOf(maximum));
            }
        }

        private void rebuildTopology() {
            animationTimer.stop();
            int peerCount = ((Number) peerSpinner.getValue()).intValue();
            int maxDirectConnections = ((Number) directConnectionsSpinner.getValue()).intValue();
            try {
                topology = OnionTopologyDemo.buildTopology(peerCount, maxDirectConnections);
                populatePeerSelectors();
                resetAnimation(
                    peerCount + " peers reconnected; limit " + maxDirectConnections + " direct connections per peer."
                );
            } catch (RuntimeException error) {
                setStatus("Could not rebuild: " + error.getMessage(), ORANGE);
            }
        }

        private void populatePeerSelectors() {
            Object previousSender = sender.getSelectedItem();
            Object previousRecipient = recipient.getSelectedItem();
            sender.removeAllItems();
            recipient.removeAllItems();
            for (NodeId node : topology.getPlan().getNodes()) {
                String label = topology.label(node);
                sender.addItem(label);
                recipient.addItem(label);
            }
            restoreSelection(sender, previousSender, 0);
            restoreSelection(recipient, previousRecipient, topology.getPlan().getNodes().size() / 2);
            updateTopologySummary();
        }

        private static void restoreSelection(JComboBox<String> combo, Object previous, int fallbackIndex) {
            if (previous != null) {
                combo.setSelectedItem(previous);
            }
            if ((previous == null || combo.getSelectedIndex() < 0) && combo.getItemCount() > 0) {
                combo.setSelectedIndex(Math.min(fallbackIndex, combo.getItemCount() - 1));
            }
        }

        private void updateTopologySummary() {
            topologySummary.setText(
                "<html>" +
                topology.getPlan().getEdges().size() +
                " physical links · observed maximum " +
                topology.maximumDirectConnections() +
                "/peer<br>connected graph: " +
                (topology.getPlan().isConnected() ? "yes" : "no") +
                "</html>"
            );
        }

        private void startUnicast() {
            NodeId source = selectedNode(sender);
            NodeId destination = selectedNode(recipient);
            if (source == null || destination == null || source.equals(destination)) {
                setStatus("Choose two different peers.", ORANGE);
                return;
            }
            try {
                List<NodeId> route = topology.route(source, destination);
                List<List<Link>> frames = new ArrayList<List<Link>>();
                for (int index = 0; index + 1 < route.size(); index++) {
                    frames.add(List.of(new Link(route.get(index), route.get(index + 1))));
                }
                startAnimation(
                    new Animation(Mode.UNICAST, source, destination, payload(), frames),
                    "ONION " + labels(route) + " · " + (route.size() - 1) + " hop"
                );
            } catch (RuntimeException error) {
                setStatus("No route exists within the dc4 hop limit. Increase the direct connections.", ORANGE);
            }
        }

        private void startBroadcast() {
            NodeId source = selectedNode(sender);
            if (source == null) {
                setStatus("Choose the sender peer.", ORANGE);
                return;
            }
            BroadcastTree tree;
            try {
                tree = topology.broadcastTree(source);
            } catch (RuntimeException error) {
                setStatus("The broadcast exceeds the dc4 hop limit. Increase the direct connections.", ORANGE);
                return;
            }
            int maxDepth = 0;
            for (NodeId node : tree.getNodes()) {
                maxDepth = Math.max(maxDepth, tree.getDepth(node));
            }
            List<List<Link>> frames = new ArrayList<List<Link>>();
            for (int depth = 1; depth <= maxDepth; depth++) {
                List<Link> wave = new ArrayList<Link>();
                for (NodeId node : sortedNodes(tree.getNodes())) {
                    if (tree.getDepth(node) == depth) {
                        wave.add(new Link(tree.getParent(node), node));
                    }
                }
                frames.add(Collections.unmodifiableList(wave));
            }
            startAnimation(
                new Animation(Mode.BROADCAST, source, null, payload(), frames),
                "BROADCAST from " + topology.label(source) + " · " + tree.edgeCount() + " forwarding steps"
            );
        }

        private String payload() {
            String value = message.getText().trim();
            return value.isEmpty() ? "(empty payload)" : value;
        }

        private NodeId selectedNode(JComboBox<String> combo) {
            Object value = combo.getSelectedItem();
            return value == null ? null : topology.node(value.toString());
        }

        private void startAnimation(Animation next, String summary) {
            animationTimer.stop();
            animation = next;
            nextFrame = 0;
            completedEdges.clear();
            activeEdges = Collections.emptyList();
            reachedNodes.clear();
            reachedNodes.add(next.source);
            eventLog.setText(summary + "\nPayload: \"" + next.message + "\"\n\n");
            setStatus("Transmission started…", next.accent());
            canvas.repaint();
            animationTimer.setDelay(hopDelay.getValue());
            animationTimer.restart();
        }

        private void advanceAnimation() {
            if (animation == null) {
                animationTimer.stop();
                return;
            }
            for (Link link : activeEdges) {
                completedEdges.add(link.key());
            }
            if (nextFrame >= animation.frames.size()) {
                activeEdges = Collections.emptyList();
                animationTimer.stop();
                String completion = animation.mode == Mode.BROADCAST
                    ? "Broadcast received by all " + reachedNodes.size() + " peers."
                    : "Message delivered to " + topology.label(animation.destination) + ".";
                eventLog.append("\n✓ " + completion + "\n");
                setStatus(completion, animation.accent());
                canvas.repaint();
                return;
            }

            activeEdges = animation.frames.get(nextFrame);
            eventLog.append("t+" + (nextFrame + 1) + "  ");
            for (int index = 0; index < activeEdges.size(); index++) {
                Link link = activeEdges.get(index);
                reachedNodes.add(link.from);
                reachedNodes.add(link.to);
                if (index > 0) eventLog.append("  ·  ");
                eventLog.append(topology.label(link.from) + " → " + topology.label(link.to));
            }
            eventLog.append("\n");
            eventLog.setCaretPosition(eventLog.getDocument().getLength());
            nextFrame++;
            setStatus(
                (animation.mode == Mode.BROADCAST ? "Broadcast wave " : "Onion hop ") +
                nextFrame +
                "/" +
                animation.frames.size(),
                animation.accent()
            );
            canvas.repaint();
        }

        private void resetAnimation(String message) {
            animationTimer.stop();
            animation = null;
            nextFrame = 0;
            completedEdges.clear();
            activeEdges = Collections.emptyList();
            reachedNodes.clear();
            eventLog.setText(message + "\n");
            setStatus(message, CYAN);
            canvas.repaint();
        }

        private void setStatus(String text, Color color) {
            status.setText("<html>" + text + "</html>");
            status.setForeground(color);
        }

        private String labels(List<NodeId> nodes) {
            List<String> values = new ArrayList<String>(nodes.size());
            for (NodeId node : nodes) values.add(topology.label(node));
            return String.join(" → ", values);
        }

        private List<NodeId> sortedNodes(Set<NodeId> nodes) {
            List<NodeId> result = new ArrayList<NodeId>(nodes);
            result.sort(Comparator.comparing(topology::label));
            return result;
        }

        private final class GraphCanvas extends JPanel {

            private GraphCanvas() {
                setBackground(BACKGROUND);
                setPreferredSize(new Dimension(1020, 820));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    paintGraph(g);
                } finally {
                    g.dispose();
                }
            }

            private void paintGraph(Graphics2D g) {
                int width = getWidth();
                int height = getHeight();
                g.setColor(BACKGROUND);
                g.fillRect(0, 0, width, height);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
                g.setColor(TEXT);
                g.drawString("NIP-DC · onion routing live", 34, 48);
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                g.setColor(MUTED);
                g.drawString(
                    topology.getPlan().getNodes().size() +
                    " peer · max " +
                    topology.getMaxDirectPeers() +
                    " direct connections/peer · " +
                    topology.getPlan().getEdges().size() +
                    " physical links",
                    35,
                    72
                );

                int usableHeight = Math.max(300, height - 155);
                int centerX = width / 2;
                int centerY = 90 + usableHeight / 2;
                int radius = Math.max(120, Math.min(width - 120, usableHeight - 80) / 2);
                Map<NodeId, Point> positions = positions(centerX, centerY, radius);
                paintPhysicalEdges(g, positions);
                paintAnimatedEdges(g, positions);
                paintNodes(g, positions);
                paintLegend(g, width, height);
            }

            private Map<NodeId, Point> positions(int centerX, int centerY, int radius) {
                Map<NodeId, Point> result = new HashMap<NodeId, Point>();
                List<NodeId> nodes = topology.getPlan().getNodes();
                for (int index = 0; index < nodes.size(); index++) {
                    double angle = -Math.PI / 2.0 + 2.0 * Math.PI * index / nodes.size();
                    result.put(
                        nodes.get(index),
                        new Point(
                            centerX + (int) Math.round(Math.cos(angle) * radius),
                            centerY + (int) Math.round(Math.sin(angle) * radius)
                        )
                    );
                }
                return result;
            }

            private void paintPhysicalEdges(Graphics2D g, Map<NodeId, Point> positions) {
                List<DesiredDirectEdge> edges = new ArrayList<DesiredDirectEdge>(topology.getPlan().getEdges());
                edges.sort(
                    Comparator
                        .comparing((DesiredDirectEdge edge) -> topology.label(edge.getFirst()))
                        .thenComparing(edge -> topology.label(edge.getSecond()))
                );
                float width = topology.getPlan().getEdges().size() > 120 ? 0.55f : 1.15f;
                for (DesiredDirectEdge edge : edges) {
                    Point first = positions.get(edge.getFirst());
                    Point second = positions.get(edge.getSecond());
                    g.setStroke(new BasicStroke(width));
                    g.setColor(edge.getPriority() == OverlayEdgePriority.CHORD ? new Color(75, 68, 112) : EDGE);
                    g.draw(new Line2D.Double(first.x, first.y, second.x, second.y));
                }
            }

            private void paintAnimatedEdges(Graphics2D g, Map<NodeId, Point> positions) {
                if (animation == null) return;
                Color accent = animation.accent();
                for (String key : completedEdges) {
                    Link link = animation.linksByKey.get(key);
                    if (link != null) drawAnimatedLink(g, positions, link, accent.darker(), 3.0f);
                }
                for (Link link : activeEdges) {
                    drawAnimatedLink(
                        g,
                        positions,
                        link,
                        new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 60),
                        11.0f
                    );
                    drawAnimatedLink(g, positions, link, accent, 5.0f);
                }
            }

            private void drawAnimatedLink(Graphics2D g, Map<NodeId, Point> positions, Link link, Color color, float width) {
                Point first = positions.get(link.from);
                Point second = positions.get(link.to);
                g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(color);
                g.draw(new Line2D.Double(first.x, first.y, second.x, second.y));
            }

            private void paintNodes(Graphics2D g, Map<NodeId, Point> positions) {
                int count = topology.getPlan().getNodes().size();
                int diameter = Math.max(16, 46 - Math.max(0, count - 16) / 2);
                NodeId source = animation == null ? null : animation.source;
                NodeId destination = animation == null ? null : animation.destination;
                Color accent = animation == null ? CYAN : animation.accent();
                for (NodeId node : topology.getPlan().getNodes()) {
                    Point point = positions.get(node);
                    boolean reached = reachedNodes.contains(node);
                    boolean isSource = node.equals(source);
                    boolean isDestination = node.equals(destination);
                    Color fill = reached ? new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 115) : FIELD;
                    Color border = isSource ? CYAN : isDestination ? ORANGE : reached ? accent : EDGE;
                    g.setColor(fill);
                    g.fill(new Ellipse2D.Double(point.x - diameter / 2.0, point.y - diameter / 2.0, diameter, diameter));
                    g.setStroke(new BasicStroke(isSource || isDestination ? 3.5f : 1.6f));
                    g.setColor(border);
                    g.draw(new Ellipse2D.Double(point.x - diameter / 2.0, point.y - diameter / 2.0, diameter, diameter));

                    if (count <= 32 || isSource || isDestination || reached) {
                        String label = topology.label(node);
                        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, count <= 20 ? 12 : 9));
                        FontMetrics metrics = g.getFontMetrics();
                        g.setColor(TEXT);
                        g.drawString(label, point.x - metrics.stringWidth(label) / 2, point.y + 4);
                    }
                }
            }

            private void paintLegend(Graphics2D g, int width, int height) {
                int y = height - 48;
                g.setColor(PANEL);
                g.fillRoundRect(24, y - 25, Math.max(300, width - 48), 48, 12, 12);
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                g.setColor(MUTED);
                String value = animation == null
                    ? "Gray = direct physical connection · use the controls on the right to begin"
                    : (
                        animation.mode == Mode.UNICAST
                            ? "Orange = onion route selected by WeightedRoutePlanner"
                            : "Violet = propagation tree selected by BroadcastTreeBuilder"
                    );
                g.drawString(value, 42, y + 4);
            }
        }
    }

    private enum Mode {
        UNICAST,
        BROADCAST,
    }

    private static final class Animation {

        private final Mode mode;
        private final NodeId source;
        private final NodeId destination;
        private final String message;
        private final List<List<Link>> frames;
        private final Map<String, Link> linksByKey = new HashMap<String, Link>();

        private Animation(Mode mode, NodeId source, NodeId destination, String message, List<List<Link>> frames) {
            this.mode = mode;
            this.source = source;
            this.destination = destination;
            this.message = message;
            this.frames = Collections.unmodifiableList(new ArrayList<List<Link>>(frames));
            for (List<Link> frame : frames) {
                for (Link link : frame) linksByKey.put(link.key(), link);
            }
        }

        private Color accent() {
            return mode == Mode.UNICAST ? ORANGE : VIOLET;
        }
    }

    private static final class Link {

        private final NodeId from;
        private final NodeId to;

        private Link(NodeId from, NodeId to) {
            this.from = from;
            this.to = to;
        }

        private String key() {
            return from.compareTo(to) < 0 ? from.asHex() + ':' + to.asHex() : to.asHex() + ':' + from.asHex();
        }
    }
}
