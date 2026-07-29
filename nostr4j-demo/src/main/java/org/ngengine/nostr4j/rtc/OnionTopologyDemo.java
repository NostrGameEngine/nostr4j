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
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.rtc.routing.EdgeId;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.RoutePath;
import org.ngengine.nostr4j.rtc.routing.RoutingScope;
import org.ngengine.nostr4j.rtc.routing.WeightedRoutePlanner;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastTree;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastTreeBuilder;
import org.ngengine.nostr4j.rtc.routing.topology.BoundedOverlaySelector;
import org.ngengine.nostr4j.rtc.routing.topology.DesiredDirectEdge;
import org.ngengine.nostr4j.rtc.routing.topology.OverlayEdgePriority;
import org.ngengine.nostr4j.rtc.routing.topology.OverlayPlan;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyEdge;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyGraph;
import org.ngengine.nostr4j.rtc.routing.topology.TopologyTransport;

/**
 * Visualizes the production dc4 bounded-overlay selection with Java2D.
 *
 * <p>The default is intentionally small: ten logical peers and at most two
 * physical direct links per peer. This produces a connected ring while the
 * highlighted route demonstrates how non-neighboring peers communicate through
 * onion-routed intermediate hops.</p>
 */
public final class OnionTopologyDemo {

    public static final int DEFAULT_PEER_COUNT = 10;
    public static final int DEFAULT_MAX_DIRECT_PEERS = 2;
    public static final int IMAGE_WIDTH = 1280;
    public static final int IMAGE_HEIGHT = 840;

    private static final Color BACKGROUND = new Color(12, 17, 23);
    private static final Color PANEL = new Color(19, 27, 35);
    private static final Color GRID = new Color(42, 56, 68);
    private static final Color TEXT = new Color(225, 235, 241);
    private static final Color MUTED = new Color(137, 157, 169);
    private static final Color CYAN = new Color(69, 211, 198);
    private static final Color ORANGE = new Color(255, 173, 77);
    private static final Color VIOLET = new Color(155, 135, 245);
    private static final Color NODE = new Color(34, 50, 62);

    private OnionTopologyDemo() {}

    public static void main(String[] args) throws Exception {
        int peerCount = Integer.getInteger("nostr4j.demo.peers", DEFAULT_PEER_COUNT);
        int maxDirectPeers = Integer.getInteger("nostr4j.demo.maxDirectPeers", DEFAULT_MAX_DIRECT_PEERS);
        Path output = Paths
            .get(System.getProperty("nostr4j.demo.output", "build/demo/onion-topology.png"))
            .toAbsolutePath()
            .normalize();

        DemoTopology topology = buildTopology(peerCount, maxDirectPeers);
        BufferedImage image = renderImage(topology);
        writeImage(image, output);
        printTextGraph(topology, output);

        if (Boolean.getBoolean("nostr4j.demo.window")) {
            showWindow(topology);
        }
    }

    public static DemoTopology buildTopology(int peerCount, int maxDirectPeers) {
        if (peerCount < 3) {
            throw new IllegalArgumentException("peerCount must be at least 3");
        }
        if (maxDirectPeers < 2 || maxDirectPeers > 64) {
            throw new IllegalArgumentException("maxDirectPeers must be between 2 and 64");
        }

        NostrPrivateKey roomPrivateKey = NostrPrivateKey.fromHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        );
        RoutingScope scope;
        try (NostrKeyPair roomKeys = new NostrKeyPair(roomPrivateKey)) {
            scope = new RoutingScope(roomKeys.getPublicKey(), "dc4-demo", "nostr4j-onion-visualizer");
        }

        List<NodeId> members = new ArrayList<NodeId>(peerCount);
        for (int index = 1; index <= peerCount; index++) {
            byte[] bytes = new byte[NodeId.SIZE];
            bytes[NodeId.SIZE - 4] = (byte) (index >>> 24);
            bytes[NodeId.SIZE - 3] = (byte) (index >>> 16);
            bytes[NodeId.SIZE - 2] = (byte) (index >>> 8);
            bytes[NodeId.SIZE - 1] = (byte) index;
            members.add(NodeId.fromHex(toHex(bytes)));
        }

        OverlayPlan plan = new BoundedOverlaySelector().select(scope, members, maxDirectPeers);
        if (!plan.isConnected()) {
            throw new IllegalStateException("The generated overlay is not connected");
        }
        for (NodeId node : plan.getNodes()) {
            if (plan.degree(node) > maxDirectPeers) {
                throw new IllegalStateException("The generated overlay exceeds maxDirectPeers");
            }
        }

        Map<NodeId, String> labels = new LinkedHashMap<NodeId, String>();
        for (int index = 0; index < plan.getNodes().size(); index++) {
            labels.put(plan.getNodes().get(index), String.format("P%02d", index + 1));
        }
        Set<TopologyEdge> graphEdges = new HashSet<TopologyEdge>();
        for (DesiredDirectEdge edge : plan.getEdges()) {
            graphEdges.add(
                new TopologyEdge(
                    EdgeId.derive(scope, edge.getFirst(), edge.getSecond()),
                    edge.getFirst(),
                    edge.getSecond(),
                    TopologyTransport.RTC,
                    TopologyTransport.RTC
                )
            );
        }
        TopologyGraph graph = new TopologyGraph(new HashSet<NodeId>(plan.getNodes()), graphEdges);
        NodeId source = plan.getNodes().get(0);
        // Keep the static example inside the protocol hop budget even when the
        // interactive demo is opened with a large, degree-two ring.
        NodeId destination = plan.getNodes().get(Math.min(plan.getNodes().size() / 2, 8));
        List<NodeId> route = firstRoute(graph, source, destination);
        return new DemoTopology(plan, graph, labels, route, maxDirectPeers);
    }

    public static BufferedImage renderImage(DemoTopology topology) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            paint(graphics, topology);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void paint(Graphics2D graphics, DemoTopology topology) {
        graphics.setColor(BACKGROUND);
        graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
        graphics.setColor(TEXT);
        graphics.drawString("NIP-DC · onion-routed peer overlay", 48, 58);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        graphics.setColor(MUTED);
        graphics.drawString(
            topology.getPlan().getNodes().size() +
            " logical peers  /  max " +
            topology.getMaxDirectPeers() +
            " direct links per peer  /  production BoundedOverlaySelector",
            49,
            87
        );

        drawStat(graphics, 48, 112, 176, "PEERS", Integer.toString(topology.getPlan().getNodes().size()), CYAN);
        drawStat(graphics, 239, 112, 176, "DIRECT EDGES", Integer.toString(topology.getPlan().getEdges().size()), VIOLET);
        drawStat(graphics, 430, 112, 176, "MAX DIRECT/PEER", Integer.toString(topology.maximumDirectConnections()), ORANGE);
        drawStat(graphics, 621, 112, 176, "CONNECTED", topology.getPlan().isConnected() ? "YES" : "NO", CYAN);

        int centerX = 420;
        int centerY = 455;
        int radius = 220;
        Map<NodeId, java.awt.Point> positions = circularPositions(topology.getPlan().getNodes(), centerX, centerY, radius);

        graphics.setStroke(new BasicStroke(1.4f));
        for (DesiredDirectEdge edge : sortedEdges(topology)) {
            if (!topology.routeContains(edge.getFirst(), edge.getSecond())) {
                drawEdge(graphics, positions, edge, edge.getPriority() == OverlayEdgePriority.CHORD ? VIOLET : GRID, 1.5f);
            }
        }
        for (DesiredDirectEdge edge : sortedEdges(topology)) {
            if (topology.routeContains(edge.getFirst(), edge.getSecond())) {
                drawEdge(graphics, positions, edge, ORANGE, 5.0f);
            }
        }

        for (NodeId node : topology.getPlan().getNodes()) {
            drawNode(graphics, topology, positions.get(node), node);
        }

        drawSidebar(graphics, topology);
        drawFooter(graphics, topology);
    }

    private static void drawStat(Graphics2D graphics, int x, int y, int width, String label, String value, Color accent) {
        graphics.setColor(PANEL);
        graphics.fillRoundRect(x, y, width, 76, 12, 12);
        graphics.setColor(accent);
        graphics.fillRoundRect(x, y, 5, 76, 12, 12);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        graphics.setColor(MUTED);
        graphics.drawString(label, x + 18, y + 24);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 25));
        graphics.setColor(TEXT);
        graphics.drawString(value, x + 18, y + 55);
    }

    private static Map<NodeId, java.awt.Point> circularPositions(List<NodeId> nodes, int centerX, int centerY, int radius) {
        Map<NodeId, java.awt.Point> result = new HashMap<NodeId, java.awt.Point>();
        for (int index = 0; index < nodes.size(); index++) {
            double angle = -Math.PI / 2.0 + 2.0 * Math.PI * index / nodes.size();
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int y = centerY + (int) Math.round(Math.sin(angle) * radius);
            result.put(nodes.get(index), new java.awt.Point(x, y));
        }
        return result;
    }

    private static List<DesiredDirectEdge> sortedEdges(DemoTopology topology) {
        List<DesiredDirectEdge> edges = new ArrayList<DesiredDirectEdge>(topology.getPlan().getEdges());
        edges.sort(
            Comparator
                .comparing((DesiredDirectEdge edge) -> topology.getLabels().get(edge.getFirst()))
                .thenComparing(edge -> topology.getLabels().get(edge.getSecond()))
        );
        return edges;
    }

    private static void drawEdge(
        Graphics2D graphics,
        Map<NodeId, java.awt.Point> positions,
        DesiredDirectEdge edge,
        Color color,
        float width
    ) {
        java.awt.Point first = positions.get(edge.getFirst());
        java.awt.Point second = positions.get(edge.getSecond());
        graphics.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(color);
        graphics.draw(new Line2D.Double(first.x, first.y, second.x, second.y));
    }

    private static void drawNode(Graphics2D graphics, DemoTopology topology, java.awt.Point position, NodeId node) {
        boolean source = node.equals(topology.getRoute().get(0));
        boolean destination = node.equals(topology.getRoute().get(topology.getRoute().size() - 1));
        boolean routed = topology.getRoute().contains(node);
        Color border = source ? CYAN : destination ? ORANGE : routed ? VIOLET : GRID;
        double size = source || destination ? 56.0 : 48.0;

        graphics.setColor(NODE);
        graphics.fill(new Ellipse2D.Double(position.x - size / 2, position.y - size / 2, size, size));
        graphics.setColor(border);
        graphics.setStroke(new BasicStroke(source || destination ? 4.0f : 2.2f));
        graphics.draw(new Ellipse2D.Double(position.x - size / 2, position.y - size / 2, size, size));

        String label = topology.getLabels().get(node);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.setColor(TEXT);
        graphics.drawString(label, position.x - metrics.stringWidth(label) / 2, position.y + 5);

        String degree = "d=" + topology.getPlan().degree(node);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        metrics = graphics.getFontMetrics();
        graphics.setColor(MUTED);
        graphics.drawString(degree, position.x - metrics.stringWidth(degree) / 2, position.y + 43);
    }

    private static void drawSidebar(Graphics2D graphics, DemoTopology topology) {
        int x = 835;
        int y = 112;
        int width = 397;
        int height = 596;
        graphics.setColor(PANEL);
        graphics.fillRoundRect(x, y, width, height, 14, 14);

        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        graphics.setColor(TEXT);
        graphics.drawString("Direct-neighbor table", x + 24, y + 38);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        graphics.setColor(MUTED);
        graphics.drawString("Only physical RTC/TURN edges are listed", x + 24, y + 60);

        int rowY = y + 95;
        int shown = 0;
        for (NodeId node : topology.getPlan().getNodes()) {
            if (rowY > y + height - 55) {
                graphics.drawString("… " + (topology.getPlan().getNodes().size() - shown) + " more peers", x + 24, rowY);
                break;
            }
            List<String> neighbors = new ArrayList<String>();
            for (NodeId neighbor : topology.getPlan().getNeighbors(node)) {
                neighbors.add(topology.getLabels().get(neighbor));
            }
            Collections.sort(neighbors);
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
            graphics.setColor(topology.getRoute().contains(node) ? ORANGE : CYAN);
            graphics.drawString(topology.getLabels().get(node), x + 24, rowY);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            graphics.setColor(TEXT);
            graphics.drawString("→  " + String.join(", ", neighbors), x + 78, rowY);
            rowY += 42;
            shown++;
        }
    }

    private static void drawFooter(Graphics2D graphics, DemoTopology topology) {
        int x = 48;
        int y = 738;
        graphics.setColor(PANEL);
        graphics.fillRoundRect(x, y, 1184, 66, 12, 12);
        graphics.setColor(ORANGE);
        graphics.fillOval(x + 20, y + 20, 12, 12);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        graphics.setColor(TEXT);
        graphics.drawString(
            "Example onion route  " + topology.routeLabels() + "  (" + (topology.getRoute().size() - 1) + " hops)",
            x + 45,
            y + 29
        );
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        graphics.setColor(MUTED);
        graphics.drawString(
            "Orange edges carry this logical connection; intermediate peers forward opaque payloads and learn only adjacent hops.",
            x + 45,
            y + 49
        );
    }

    private static List<NodeId> firstRoute(TopologyGraph graph, NodeId source, NodeId destination) {
        List<RoutePath> routes = new WeightedRoutePlanner().plan(graph, source, destination, java.time.Instant.now());
        if (routes.isEmpty()) {
            throw new IllegalStateException("No dc4 route exists between the selected demo peers");
        }
        return routes.get(0).getNodes();
    }

    private static void writeImage(BufferedImage image, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("No PNG writer is available");
        }
    }

    private static void printTextGraph(DemoTopology topology, Path output) {
        System.out.println();
        System.out.println("NIP-DC onion topology");
        System.out.println(
            "peers=" +
            topology.getPlan().getNodes().size() +
            " directEdges=" +
            topology.getPlan().getEdges().size() +
            " maxDirectConnectionsPerPeer=" +
            topology.maximumDirectConnections() +
            " connected=" +
            topology.getPlan().isConnected()
        );
        for (NodeId node : topology.getPlan().getNodes()) {
            List<String> neighbors = new ArrayList<String>();
            for (NodeId neighbor : topology.getPlan().getNeighbors(node)) {
                neighbors.add(topology.getLabels().get(neighbor));
            }
            Collections.sort(neighbors);
            System.out.printf("  %s -> %s%n", topology.getLabels().get(node), String.join(", ", neighbors));
        }
        System.out.println("onionRoute=" + topology.routeLabels());
        System.out.println("png=" + output);
    }

    private static void showWindow(DemoTopology topology) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot open the demo window in a headless graphics environment");
        }
        OnionTopologyWindow.show(topology);
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    public static final class DemoTopology {

        private final OverlayPlan plan;
        private final TopologyGraph graph;
        private final Map<NodeId, String> labels;
        private final List<NodeId> route;
        private final int maxDirectPeers;

        private DemoTopology(
            OverlayPlan plan,
            TopologyGraph graph,
            Map<NodeId, String> labels,
            List<NodeId> route,
            int maxDirectPeers
        ) {
            this.plan = plan;
            this.graph = graph;
            this.labels = Collections.unmodifiableMap(new LinkedHashMap<NodeId, String>(labels));
            this.route = route;
            this.maxDirectPeers = maxDirectPeers;
        }

        public OverlayPlan getPlan() {
            return plan;
        }

        public Map<NodeId, String> getLabels() {
            return labels;
        }

        public TopologyGraph getGraph() {
            return graph;
        }

        public List<NodeId> getRoute() {
            return route;
        }

        public int getMaxDirectPeers() {
            return maxDirectPeers;
        }

        public int maximumDirectConnections() {
            int maximum = 0;
            for (NodeId node : plan.getNodes()) {
                maximum = Math.max(maximum, plan.degree(node));
            }
            return maximum;
        }

        public List<NodeId> route(NodeId source, NodeId destination) {
            return firstRoute(graph, source, destination);
        }

        public BroadcastTree broadcastTree(NodeId source) {
            return new BroadcastTreeBuilder().build(graph, source);
        }

        public NodeId node(String label) {
            for (Map.Entry<NodeId, String> entry : labels.entrySet()) {
                if (entry.getValue().equals(label)) {
                    return entry.getKey();
                }
            }
            return null;
        }

        public String label(NodeId node) {
            return labels.get(node);
        }

        public boolean routeContains(NodeId first, NodeId second) {
            for (int index = 0; index + 1 < route.size(); index++) {
                NodeId left = route.get(index);
                NodeId right = route.get(index + 1);
                if ((left.equals(first) && right.equals(second)) || (left.equals(second) && right.equals(first))) {
                    return true;
                }
            }
            return false;
        }

        public String routeLabels() {
            List<String> routeLabels = new ArrayList<String>(route.size());
            for (NodeId node : route) {
                routeLabels.add(labels.get(node));
            }
            return String.join(" → ", routeLabels);
        }
    }
}
