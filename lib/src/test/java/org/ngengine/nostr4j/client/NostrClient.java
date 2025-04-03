package org.ngengine.nostr4j.client;

import java.awt.*;
import java.awt.event.*;
import java.time.Instant;

import javax.swing.*;
import javax.swing.border.*;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.nostr4j.platform.jvm.JVMAsyncPlatform;
import org.ngengine.nostr4j.utils.Bech32.Bech32Exception;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NostrClient extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final Logger rootLogger = TestLogger.getRoot(Level.FINEST);

    // Custom event data structure
   
    // UI Components
    private JScrollPane scrollPane;
    private JPanel contentPanel;
    private JButton loadMoreButton;
    private JPanel footerPanel;
    private List<JPanel> eventPanels = new ArrayList<>();
    private NostrPool pool;
    private JTextField searchBar;
    private Timer debounceTimer;

    private long earliestEvent = Long.MAX_VALUE;

    public NostrClient(String title) {
        super(title);

        // Initialize UI
        setupUI();

        this.pool = new NostrPool();
        this.pool.ensureRelay("wss://nostr.wine");
        this.pool.addNoticeListener((relay, notice) -> {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(null, notice, "Notice", JOptionPane.INFORMATION_MESSAGE);
            });
        });

        NostrSubscription sub = this.pool
                .subscribe(
                Arrays.asList(
                        new NostrSearchFilter().kind(1).limit(10)));

        sub.listenEvent((s, event, stored) -> {
            addEventToFeed(event, true);
            
        });

        sub.open();
       
    }

    private void loadMore(){
        this.pool.fetch(
            new NostrFilter().kind(1)
            .until(Instant.ofEpochSecond(earliestEvent))
            .limit(3)           
        ).exceptionally((e) -> {
            JOptionPane.showMessageDialog(this, "Error loading more events: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }).then((events) -> {
            for (SignedNostrEvent event : events) {
                System.out.println("Loaded more event: " + event);
                addEventToFeed(event, false);
                
            }
            return null;
        });
    }

    private void setupUI() {
        // Set window properties
        setSize(600, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Set look and feel to system default
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Create layout
        setLayout(new BorderLayout());

        // Create search bar
        searchBar = new JTextField();
        searchBar.setFont(new Font("SansSerif", Font.PLAIN, 14));
        searchBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        searchBar.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (debounceTimer != null) {
                    debounceTimer.stop();
                }
                debounceTimer = new Timer(300, evt -> performSearch(searchBar.getText().trim()));
                debounceTimer.setRepeats(false);
                debounceTimer.start();
            }
        });

        // Create content panel with vertical layout
        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(Color.WHITE);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Create footer panel with load more button
        footerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        footerPanel.setBackground(Color.WHITE);

        loadMoreButton = new JButton("Load More");
        loadMoreButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        loadMoreButton.setFocusPainted(false);
        loadMoreButton.addActionListener(e -> loadMore());

        footerPanel.add(loadMoreButton);

        // Main container to hold content and footer
        JPanel mainContainer = new JPanel();
        mainContainer.setLayout(new BoxLayout(mainContainer, BoxLayout.Y_AXIS));
        mainContainer.setBackground(Color.WHITE);
        mainContainer.add(contentPanel);
        mainContainer.add(footerPanel);

        // Create scroll pane
        scrollPane = new JScrollPane(mainContainer);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16); // Smoother scrolling

        // Add components to the frame
        add(searchBar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void performSearch(String query) {
        if (query.isEmpty()) {
            return;
        }

        // Reset earliestEvent and clear displayed events
        earliestEvent = Long.MAX_VALUE;
        eventPanels.clear();
        contentPanel.removeAll();

        // Reinitialize the pool with the new search filter
        pool.unsubscribeAll();
        NostrSubscription sub = pool.subscribe(
                Arrays.asList(new NostrSearchFilter().kind(1).limit(10).search(query)));

        sub.listenEvent((s, event, stored) -> {
            addEventToFeed(event, true);
        });

        sub.open();

        // Refresh UI
        contentPanel.revalidate();
        contentPanel.repaint();
    }
    
    private void addEventToFeed(SignedNostrEvent event, boolean top) {
        if(event.getCreatedAt() < earliestEvent){
            earliestEvent = event.getCreatedAt();
        }

        // Create panel for this event
        JPanel eventPanel = createEventPanel(event);

        if (top) {
            // Add to the beginning of the list
            eventPanels.add(0, eventPanel);

            // Add to the top of the content panel
            contentPanel.add(eventPanel, 0);
            contentPanel.add(Box.createVerticalStrut(10), 1);
        } else {
            // Add to the end of the list
            eventPanels.add(eventPanel);

            // Add to the bottom of the content panel
            contentPanel.add(eventPanel);
            contentPanel.add(Box.createVerticalStrut(10));
        }

        // Refresh UI
        contentPanel.revalidate();
        contentPanel.repaint();
    }
    private JPanel createEventPanel(SignedNostrEvent event)  {
        // Main panel with rounded corners and shadow effect
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.setBackground(new Color(248, 249, 250));
        panel.setBorder(BorderFactory.createCompoundBorder(
                new SoftBevelBorder(BevelBorder.RAISED, Color.LIGHT_GRAY, Color.GRAY),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        // Header panel (author info)
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.X_AXIS));
        headerPanel.setBackground(new Color(248, 249, 250));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        // Avatar panel with custom painting
        JPanel avatarPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();

                // Enable antialiasing for smoother circles
                g2d.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                // Draw avatar circle
                g2d.setColor(new Color(200, 200, 220));
                g2d.fillOval(0, 0, getWidth(), getHeight());

                // Draw text
                g2d.setColor(Color.BLACK);
                g2d.setFont(new Font("SansSerif", Font.BOLD, 14));
                FontMetrics fm = g2d.getFontMetrics();
                String text = event.getPubkey().asHex();
                int textWidth = fm.stringWidth(text);
                int textHeight = fm.getHeight();

                g2d.drawString(text,
                        (getWidth() - textWidth) / 2,
                        (getHeight() - textHeight) / 2 + fm.getAscent());

                g2d.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(40, 40);
            }
        };
        avatarPanel.setMaximumSize(new Dimension(40, 40));
        headerPanel.add(avatarPanel);
        headerPanel.add(Box.createHorizontalStrut(10));

        // Author details panel (name and timestamp)
        JPanel authorDetailsPanel = new JPanel();
        authorDetailsPanel.setLayout(new BoxLayout(authorDetailsPanel, BoxLayout.Y_AXIS));
        authorDetailsPanel.setBackground(new Color(248, 249, 250));

        // Author name
        JLabel authorLabel = new JLabel("@" + event.getPubkey().asHex());
        authorLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        authorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        authorDetailsPanel.add(authorLabel);

        // Timestamp
        JLabel timestampLabel = new JLabel(formatTimestamp(event.getCreatedAt()));
        timestampLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
        timestampLabel.setForeground(Color.GRAY);
        timestampLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        authorDetailsPanel.add(timestampLabel);

        headerPanel.add(authorDetailsPanel);
        headerPanel.add(Box.createHorizontalGlue()); // Push everything to the left

        // Content text area
        JTextArea contentText = new JTextArea(event.getContent());
        contentText.setLineWrap(true);
        contentText.setWrapStyleWord(true);
        contentText.setEditable(false);
        contentText.setBackground(new Color(248, 249, 250));
        contentText.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        contentText.setFont(new Font("SansSerif", Font.PLAIN, 14));

        // Calculate needed rows based on content length (approximate)
        int rows = Math.max(2, Math.min(6, event.getContent().length() / 50 + 1));
        contentText.setRows(rows);

        // Add components to main panel
        panel.add(headerPanel, BorderLayout.NORTH);
        panel.add(contentText, BorderLayout.CENTER);

        return panel;
    }

    private String formatTimestamp(long timestamp) {
        return new java.text.SimpleDateFormat("MMM d, yyyy 'at' h:mm a").format(
                new java.util.Date(timestamp * 1000));
    }

    public static void main(String[] args) throws Exception {
        Logger.getLogger("org.ngengine.nostr4j").setLevel(Level.FINEST);

     
        SwingUtilities.invokeLater(() -> {
            NostrClient client = new NostrClient("Nostr Feed");
            client.setVisible(true);


        });
    }
}