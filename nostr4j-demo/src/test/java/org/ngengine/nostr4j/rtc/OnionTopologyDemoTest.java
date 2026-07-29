/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.Test;
import org.ngengine.nostr4j.rtc.OnionTopologyDemo.DemoTopology;
import org.ngengine.nostr4j.rtc.routing.NodeId;
import org.ngengine.nostr4j.rtc.routing.broadcast.BroadcastTree;

public class OnionTopologyDemoTest {

    @Test
    public void defaultDemoUsesConnectedTenPeerDegreeTwoOverlay() {
        DemoTopology topology = OnionTopologyDemo.buildTopology(10, 2);

        assertEquals(10, topology.getPlan().getNodes().size());
        assertEquals(10, topology.getPlan().getEdges().size());
        assertEquals(2, topology.maximumDirectConnections());
        assertTrue(topology.getPlan().isConnected());
        for (NodeId node : topology.getPlan().getNodes()) {
            assertEquals(2, topology.getPlan().degree(node));
        }
        assertTrue("The visual route should demonstrate forwarding through intermediate peers", topology.getRoute().size() > 2);
    }

    @Test
    public void rendererProducesExpectedCanvas() {
        BufferedImage image = OnionTopologyDemo.renderImage(OnionTopologyDemo.buildTopology(10, 2));

        assertEquals(OnionTopologyDemo.IMAGE_WIDTH, image.getWidth());
        assertEquals(OnionTopologyDemo.IMAGE_HEIGHT, image.getHeight());
    }

    @Test
    public void increasingDirectConnectionLimitRebuildsDenserProductionGraph() {
        DemoTopology sparse = OnionTopologyDemo.buildTopology(10, 2);
        DemoTopology dense = OnionTopologyDemo.buildTopology(10, 4);

        assertTrue(dense.getPlan().getEdges().size() > sparse.getPlan().getEdges().size());
        assertEquals(4, dense.maximumDirectConnections());
        assertTrue(dense.getPlan().isConnected());
    }

    @Test
    public void sixtyFourPeerSparseTopologyCanStillBeRebuiltAndRendered() {
        DemoTopology topology = OnionTopologyDemo.buildTopology(64, 2);

        assertEquals(64, topology.getPlan().getNodes().size());
        assertEquals(2, topology.maximumDirectConnections());
        assertTrue(topology.getPlan().isConnected());
        assertEquals(9, topology.getRoute().size());
        assertEquals(OnionTopologyDemo.IMAGE_WIDTH, OnionTopologyDemo.renderImage(topology).getWidth());
    }

    @Test
    public void unicastAndBroadcastUseProductionRoutingAlgorithms() {
        DemoTopology topology = OnionTopologyDemo.buildTopology(10, 2);
        NodeId source = topology.node("P01");
        NodeId destination = topology.node("P06");

        List<NodeId> route = topology.route(source, destination);
        assertEquals(source, route.get(0));
        assertEquals(destination, route.get(route.size() - 1));
        for (int index = 0; index + 1 < route.size(); index++) {
            assertTrue(topology.getGraph().findEdge(route.get(index), route.get(index + 1)) != null);
        }

        BroadcastTree tree = topology.broadcastTree(source);
        assertEquals(10, tree.getNodes().size());
        assertEquals(9, tree.edgeCount());
        assertEquals(0, tree.getDepth(source));
    }

    @Test
    public void interactiveControlPanelCanRenderOffscreen() {
        JComponent content = OnionTopologyWindow.createContent(OnionTopologyDemo.buildTopology(10, 2));
        content.setSize(1400, 880);
        content.doLayout();
        BufferedImage image = new BufferedImage(1400, 880, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            content.paint(graphics);
        } finally {
            graphics.dispose();
        }

        assertEquals(1400, image.getWidth());
        assertEquals(880, image.getHeight());
    }

    @Test
    public void interactiveControlPanelStartsWithAUsableUnicastSelection() throws Exception {
        JComponent content = OnionTopologyWindow.createContent(OnionTopologyDemo.buildTopology(10, 2));
        JComboBox<?> sender = (JComboBox<?>) findNamed(content, "sender");
        JComboBox<?> recipient = (JComboBox<?>) findNamed(content, "recipient");
        JButton send = (JButton) findNamed(content, "sendUnicast");
        JTextArea eventLog = (JTextArea) findNamed(content, "eventLog");

        assertNotEquals(sender.getSelectedItem(), recipient.getSelectedItem());
        SwingUtilities.invokeAndWait(send::doClick);
        assertTrue(eventLog.getText().contains("ONION P01"));
    }

    private static Component findNamed(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                Component found = findNamed(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
