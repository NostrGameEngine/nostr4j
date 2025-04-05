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
package org.ngengine.nostr4j.client;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.util.regex.Pattern;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip24.Nip24Metadata;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.nostr4j.platform.AsyncTask;

public class NostrClient extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final Logger rootLogger = TestLogger.getRoot(Level.FINEST);
    
    // Metadata cache
    private static final Map<String, AsyncTask<Nip24Metadata>> metadataCache = new ConcurrentHashMap<>();
    private static final Map<String, String> nameCache = new ConcurrentHashMap<>();
    
    // UI Components
    private JPanel contentPanel;
    private JButton loadMoreButton;
    private NostrPool pool;
    private JTextField searchBar;
    
    // Simple theme colors for old-school look
    private static final Color BACKGROUND_COLOR = new Color(240, 240, 240);
    private static final Color BORDER_COLOR = Color.GRAY;
    private static final Color TEXT_COLOR = Color.BLACK;

    private long earliestEvent = Long.MAX_VALUE;

    // Audio resources
    private byte[] newEventSoundData;
    private byte[] gmSoundData;
    private boolean soundEnabled = true;
    private final AudioFormat audioFormat = new AudioFormat(44100, 16, 1, true, false);
    public NostrClient(String title) {
        super(title);
        setSize(500, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        setLayout(new BorderLayout(5, 5));
        
        // Search panel with basic components
        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.setBackground(BACKGROUND_COLOR);
        searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JLabel searchLabel = new JLabel("Search:");
        searchBar = new JTextField(20);
        searchBar.addActionListener(e -> performSearch(searchBar.getText().trim()));
        
        searchPanel.add(searchLabel, BorderLayout.WEST);
        searchPanel.add(searchBar, BorderLayout.CENTER);
        
        // Content area - simple scrollable panel
        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(BACKGROUND_COLOR);
        
        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        
        // Load more button
        loadMoreButton = new JButton("Load More");
        loadMoreButton.addActionListener(e -> loadMore());
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setBackground(BACKGROUND_COLOR);
        buttonPanel.add(loadMoreButton);

        // Add sound toggle to UI
        JCheckBox soundToggle = new JCheckBox("Sound", true);
        soundToggle.setBackground(BACKGROUND_COLOR);
        soundToggle.addActionListener(e -> soundEnabled = soundToggle.isSelected());
        buttonPanel.add(soundToggle);
        
        // Add components to frame
        add(searchPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        
        // Initialize Nostr connection
        initNostr();

        // Load sound resources
        initSounds();
    }
    
    private void initNostr() {
        this.pool = new NostrPool();
        this.pool.ensureRelay("wss://nostr.wine");
        
        // Simple notice handling
        this.pool.addNoticeListener((relay, notice) -> 
            SwingUtilities.invokeLater(() -> 
                JOptionPane.showMessageDialog(this, notice)));
        
        // Subscribe to events
        NostrSubscription sub = this.pool.subscribe(
                Arrays.asList(new NostrSearchFilter().kind(1).limit(10)));
        
        sub.listenEvent((s, event, stored) -> addEventToFeed(event, true));
        sub.open();
        
        
    }

   
    private byte[] generateRetroSound(float baseFrequency, int duration, int waveType) {
        int sampleRate = (int) audioFormat.getSampleRate();
        int samples = (duration * sampleRate) / 1000;
        byte[] output = new byte[samples * 2]; // 16-bit samples
        
        double period = (double) sampleRate / baseFrequency;
        double maxAmplitude = 16384.0; // Less than max to avoid distortion
        
        for (int i = 0; i < samples; i++) {
            double time = (double) i / sampleRate;
            double angle = 2.0 * Math.PI * i / period;
            
            // Apply pitch bend for more interesting sound
            double pitchBend = 1.0 + 0.1 * Math.sin(2.0 * Math.PI * time * 3.0);
            angle = 2.0 * Math.PI * i / (period / pitchBend);
            
            double sample;
            
            // Generate different waveforms for variety
            switch (waveType) {
                case 0: // Square wave (classic 8-bit sound)
                    sample = Math.sin(angle) >= 0 ? 1.0 : -1.0;
                    break;
                case 1: // Triangle wave (softer retro sound)
                    sample = 2.0 * Math.abs(angle % (2 * Math.PI) / Math.PI - 1.0) - 1.0;
                    break;
                case 2: // Noise (for percussion or effects)
                    sample = 2.0 * Math.random() - 1.0;
                    break;
                default:
                    sample = Math.sin(angle); // Fallback to sine
            }
            
            // Apply ADSR envelope for better sound shaping
            double envelope = 1.0;
            double attackTime = 0.1; // 10% attack
            double decayTime = 0.2; // 20% decay
            double sustainLevel = 0.7; // 70% sustain
            double releaseTime = 0.3; // 30% release
            
            double normalizedTime = (double) i / samples;
            
            if (normalizedTime < attackTime) {
                // Attack phase - ramp up
                envelope = normalizedTime / attackTime;
            } else if (normalizedTime < attackTime + decayTime) {
                // Decay phase - ramp down to sustain level
                double decayProgress = (normalizedTime - attackTime) / decayTime;
                envelope = 1.0 - (decayProgress * (1.0 - sustainLevel));
            } else if (normalizedTime < 1.0 - releaseTime) {
                // Sustain phase - maintain level
                envelope = sustainLevel;
            } else {
                // Release phase - ramp down to zero
                double releaseProgress = (normalizedTime - (1.0 - releaseTime)) / releaseTime;
                envelope = sustainLevel * (1.0 - releaseProgress);
            }
            
            short value = (short) (maxAmplitude * sample * envelope);
            
            // Write 16-bit sample, little-endian
            output[i*2] = (byte) (value & 0xFF);
            output[i*2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        
        return output;
    }

    /**
     * Generates a typical retro notification blip sound
     */
    private byte[] generateBeepSound(float frequency, int duration) {
        // Use square wave for that classic computer beep
        return generateRetroSound(frequency, duration, 0);
    }

    /**
     * Generates a cheerful retro "good morning" sound sequence
     */
    private byte[] generateGoodMorningSound() {
        // Create a happy arcade-style jingle
        byte[] note1 = generateRetroSound(440, 80, 0);  // A4 - Square wave
        byte[] note2 = generateRetroSound(523, 80, 1);  // C5 - Triangle wave
        byte[] note3 = generateRetroSound(659, 100, 0); // E5 - Square wave
        byte[] note4 = generateRetroSound(880, 150, 1); // A5 - Triangle wave
        
        // Create tiny gaps between notes (just 10ms of silence)
        byte[] gap = new byte[440]; // 10ms of silence at 44.1kHz
        
        // Combine all notes with gaps
        byte[] combined = new byte[
            note1.length + gap.length + 
            note2.length + gap.length + 
            note3.length + gap.length + 
            note4.length
        ];
        
        // Copy all segments into combined array
        int offset = 0;
        System.arraycopy(note1, 0, combined, offset, note1.length);
        offset += note1.length;
        
        System.arraycopy(gap, 0, combined, offset, gap.length);
        offset += gap.length;
        
        System.arraycopy(note2, 0, combined, offset, note2.length);
        offset += note2.length;
        
        System.arraycopy(gap, 0, combined, offset, gap.length);
        offset += gap.length;
        
        System.arraycopy(note3, 0, combined, offset, note3.length);
        offset += note3.length;
        
        System.arraycopy(gap, 0, combined, offset, gap.length);
        offset += gap.length;
        
        System.arraycopy(note4, 0, combined, offset, note4.length);
        
        return combined;
    }

    private void initSounds() {
        try {
            // Generate notification sound (classic 8-bit style blip)
            newEventSoundData = generateBeepSound(880, 120); // A5, short blip
            
            // Generate GM sound (cheerful retro game-style jingle)
            gmSoundData = generateGoodMorningSound();
            
            System.out.println("Retro sounds initialized successfully");
        } catch (Exception e) {
            System.err.println("Could not initialize sounds: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void playSound(byte[] soundData) {
        if (!soundEnabled || soundData == null) return;
        
        try {
            // Create a new clip each time to avoid concurrency issues
            Clip clip = AudioSystem.getClip();
            AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(soundData),
                audioFormat,
                soundData.length / audioFormat.getFrameSize()
            );
            
            clip.open(ais);
            clip.start();
            
            // Auto-close the clip when done playing
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
        } catch (Exception e) {
            System.err.println("Error playing sound: " + e.getMessage());
        }
    }

    private void loadMore() {
        loadMoreButton.setEnabled(false);
        loadMoreButton.setText("Loading...");
        
        this.pool.fetch(
                new NostrSearchFilter()
                        .kind(1)
                        .search(searchBar.getText()
                                .trim())
                        .until(Instant.ofEpochSecond(earliestEvent))
                        .limit(5))
                .exceptionally(e -> {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
                        loadMoreButton.setText("Load More");
                        loadMoreButton.setEnabled(true);
                    });
                })
                .then(events -> {
                    SwingUtilities.invokeLater(() -> {
                        for (SignedNostrEvent event : events) {
                            addEventToFeed(event, false);
                        }
                        loadMoreButton.setText("Load More");
                        loadMoreButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void performSearch(String query) {
        if (query.isEmpty()) return;
        
        // Clear content
        contentPanel.removeAll();
        JLabel searchingLabel = new JLabel("Searching for: " + query);
        contentPanel.add(searchingLabel);
        contentPanel.revalidate();
        contentPanel.repaint();
        
        // Reset tracking
        earliestEvent = Long.MAX_VALUE;
        
        // Perform search
        pool.unsubscribeAll();
        NostrSubscription sub = pool.subscribe(
                Arrays.asList(new NostrSearchFilter().kind(1).limit(10).search(query)));
        
        sub.listenEvent((s, event, stored) -> {
            SwingUtilities.invokeLater(() -> {
                // Remove searching label on first result
                if (contentPanel.getComponentCount() == 1 && 
                        contentPanel.getComponent(0) == searchingLabel) {
                    contentPanel.removeAll();
                }
                addEventToFeed(event, true);
            });
        });
        
        sub.open();
    }

    private void addEventToFeed(SignedNostrEvent event, boolean top) {
        if (event.getCreatedAt() < earliestEvent) {
            earliestEvent = event.getCreatedAt();
        }

        // Create simple panel for this event
        JPanel eventPanel = createEventPanel(event);
        
        if (top) {
            contentPanel.add(eventPanel, 0);
        } else {
            contentPanel.add(eventPanel);
        }
        
        // Add separator
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        if (top) {
            contentPanel.add(separator, top ? 1 : contentPanel.getComponentCount());
        } else {
            contentPanel.add(separator);
        }
        
        contentPanel.revalidate();
        contentPanel.repaint();
        
        // Play appropriate sound based on content
        if (isGoodMorningMessage(event.getContent())) {
            playSound(gmSoundData);
        } else {
            playSound(newEventSoundData);
        }
    }

    private boolean isGoodMorningMessage(String content) {
        if (content == null) return false;
        
        // Convert to lowercase and trim for easier matching
        String lowerContent = content.toLowerCase().trim();
        
        // Check for common GM variations
        return lowerContent.contains("gm");
    }

    private JPanel createEventPanel(SignedNostrEvent event) {
        // Get pubkey and event ID
        final String fullPubkey = event.getPubkey().asHex();
        final String shortPubkey = fullPubkey.substring(0, 8) + "...";
        final String eventId = event.getId();
        final String shortEventId = eventId.substring(0, 8) + "...";
        
        // Create a simple panel with fixed height
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setBackground(BACKGROUND_COLOR);
        
        // Header with author info
        JPanel headerPanel = new JPanel(new BorderLayout(5, 0));
        headerPanel.setBackground(BACKGROUND_COLOR);
        
        // Basic author label
        JLabel authorLabel = new JLabel(shortPubkey);
        authorLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        authorLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                copyToClipboard(fullPubkey);
                JOptionPane.showMessageDialog(panel, "Pubkey copied!");
            }
        });
        
        // Event ID label
        JPanel idPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        idPanel.setBackground(BACKGROUND_COLOR);
        
        JLabel idLabel = new JLabel("ID: " + shortEventId);
        idLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        idLabel.setForeground(Color.DARK_GRAY);
        idLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        idLabel.setToolTipText("Click to copy event ID");
        idLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                copyToClipboard(eventId);
                JOptionPane.showMessageDialog(panel, "Event ID copied!");
            }
        });
        
        idPanel.add(idLabel);
        
        JPanel authorInfoPanel = new JPanel(new BorderLayout());
        authorInfoPanel.setBackground(BACKGROUND_COLOR);
        authorInfoPanel.add(authorLabel, BorderLayout.NORTH);
        authorInfoPanel.add(idPanel, BorderLayout.SOUTH);
        
        // Timestamp
        JLabel timestampLabel = new JLabel(formatTimestamp(event.getCreatedAt()));
        timestampLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        timestampLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        
        headerPanel.add(authorInfoPanel, BorderLayout.WEST);
        headerPanel.add(timestampLabel, BorderLayout.EAST);
        
        // Create main content panel that can hold both text and images
        JPanel contentMainPanel = new JPanel();
        contentMainPanel.setLayout(new BoxLayout(contentMainPanel, BoxLayout.Y_AXIS));
        contentMainPanel.setBackground(BACKGROUND_COLOR);
        
        // Content with fixed size to prevent layout issues
        JTextArea contentArea = new JTextArea(event.getContent());
        contentArea.setEditable(false);
        contentArea.setLineWrap(true);
        contentArea.setWrapStyleWord(true);
        contentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        contentArea.setRows(4);
        
        JScrollPane contentScroll = new JScrollPane(contentArea);
        contentScroll.setPreferredSize(new Dimension(450, 100));
        contentScroll.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        
        contentMainPanel.add(contentScroll);
        
        // Check for image URLs in content
        String content = event.getContent();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(https?://\\S+\\.(jpg|jpeg|png|gif|bmp))", 
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        // If image URLs are found, create an image panel
        if (matcher.find()) {
            matcher.reset();
            while (matcher.find()) {
                String imageUrl = matcher.group(1);
                JLabel loadingLabel = new JLabel("Loading image: " + imageUrl, JLabel.CENTER);
                loadingLabel.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 10));
                
                JPanel imageContainer = new JPanel(new BorderLayout());
                imageContainer.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
                imageContainer.add(loadingLabel, BorderLayout.CENTER);
                contentMainPanel.add(imageContainer);
                
                // Load the image asynchronously
                loadImageAsync(imageUrl, imageContainer);
            }
        }
        
        // Add to main panel
        panel.add(headerPanel, BorderLayout.NORTH);
        panel.add(contentMainPanel, BorderLayout.CENTER);
        
        // Load metadata and update author label
        fetchMetadata(event.getPubkey(), authorLabel);
        
        return panel;
    }

    private void loadImageAsync(String imageUrl, JPanel container) {
        SwingWorker<ImageIcon, Void> worker = new SwingWorker<>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                try {
                    URL url = new URL(imageUrl);
                    return new ImageIcon(url);
                } catch (Exception e) {
                    System.out.println("Error loading image: " + e.getMessage());
                    return null;
                }
            }
            
            @Override
            protected void done() {
                try {
                    ImageIcon icon = get();
                    if (icon != null) {
                        // Remove loading message
                        container.removeAll();
                        
                        // Scale down large images
                        Image img = icon.getImage();
                        int maxWidth = 400;
                        
                        if (icon.getIconWidth() > maxWidth) {
                            double ratio = (double) maxWidth / icon.getIconWidth();
                            int newHeight = (int) (icon.getIconHeight() * ratio);
                            img = img.getScaledInstance(maxWidth, newHeight, Image.SCALE_SMOOTH);
                            icon = new ImageIcon(img);
                        }
                        
                        JLabel imageLabel = new JLabel(icon);
                        imageLabel.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
                        
                        container.add(imageLabel, BorderLayout.CENTER);
                        container.revalidate();
                        container.repaint();
                    } else {
                        container.removeAll();
                        JLabel errorLabel = new JLabel("Image load failed", JLabel.CENTER);
                        errorLabel.setFont(new Font(Font.MONOSPACED, Font.ITALIC, 10));
                        errorLabel.setForeground(Color.RED);
                        container.add(errorLabel, BorderLayout.CENTER);
                        container.revalidate();
                        container.repaint();
                    }
                } catch (Exception e) {
                    System.out.println("Error displaying image: " + e.getMessage());
                }
            }
        };
        
        worker.execute();
    }

    private void fetchMetadata(NostrPublicKey pubkey, JLabel authorLabel) {
        String key = pubkey.asHex();
        
        // Check cache first
        if (nameCache.containsKey(key)) {
            authorLabel.setText(nameCache.get(key));
            return;
        }
        
        // Fetch metadata
        AsyncTask<Nip24Metadata> task = metadataCache.computeIfAbsent(
            key, k -> Nip24Metadata.fetch(this.pool, pubkey));
            
        task.then(metadata -> {
            if (metadata != null) {
                SwingUtilities.invokeLater(() -> {
                    String name = metadata.getDisplayName();
                    if (name == null) name = metadata.getName();
                    if (name == null || name.isEmpty()) {
                        name = key.substring(0, 8) + "...";
                    }
                    
                    // Update cache and label
                    nameCache.put(key, name);
                    authorLabel.setText(name);
                });
            }
            return null;
        }).exceptionally(ex -> {
            System.out.println("Error fetching metadata: " + ex.getMessage());
        });
    }

    private void copyToClipboard(String text) {
        StringSelection stringSelection = new StringSelection(text);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(stringSelection, null);
    }

    private String formatTimestamp(long timestamp) {
        // Simple date format for old-school look
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new java.util.Date(timestamp * 1000));
    }

    public static void main(String[] args) {
        try {
            // Set system look and feel for old-school appearance
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(() -> {
            NostrClient client = new NostrClient("Simple Nostr Reader");
            client.setVisible(true);
        });
    }
}