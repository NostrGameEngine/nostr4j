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
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.TestLogger;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.FailOnDoubleTracker;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip24.Nip24ExtraMetadata;
import org.ngengine.nostr4j.nip50.NostrSearchFilter;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrAllEOSEPoolFetchPolicy;
import org.ngengine.platform.AsyncTask;

public class NostrClient extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final Logger rootLogger = TestLogger.getRoot(Level.FINEST);

    // Metadata cache
    private static final Map<String, AsyncTask<Nip24ExtraMetadata>> metadataCache = new ConcurrentHashMap<>();
    private static final Map<String, String> nameCache = new ConcurrentHashMap<>();

    // UI Components
    private JPanel contentPanel;
    private JButton loadMoreButton;
    private NostrPool pool;
    private JTextField searchBar;

    // 90s Cyber theme colors
    private static final Color BACKGROUND_COLOR = new Color(0, 0, 32); // Dark blue background
    private static final Color PANEL_COLOR = new Color(20, 20, 45); // Slightly lighter blue for panels
    private static final Color HIGHLIGHT_COLOR = new Color(0, 255, 255); // Cyan highlight
    private static final Color NEON_PINK = new Color(255, 0, 128); // Neon pink
    private static final Color ELECTRIC_BLUE = new Color(0, 192, 255); // Electric blue
    private static final Color LIME_GREEN = new Color(0, 255, 128); // Lime green
    private static final Color TEXT_COLOR = new Color(240, 240, 255); // Light blue-white text
    private static final Color SECONDARY_TEXT = new Color(180, 180, 230); // Dim purple-white text

    private long earliestEvent = Long.MAX_VALUE;

    // Audio resources
    private byte[] newEventSoundData;
    private byte[] gmSoundData;
    private boolean soundEnabled = true;
    private final AudioFormat audioFormat = new AudioFormat(44100, 16, 1, true, false);

    // Custom title bar components
    private JPanel titleBar;
    private boolean isDragging = false;
    private Point dragOffset;

    public NostrClient(String title) {
        super(title);
        setSize(600, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setUndecorated(true); // Remove system decorations for custom title bar

        // Set up custom layout
        setLayout(new BorderLayout());

        // Create custom title bar
        createTitleBar(title);

        // Create main content panel with grid background
        JPanel mainPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();

                // Draw background gradient
                GradientPaint backgroundGradient = new GradientPaint(
                    0,
                    0,
                    new Color(0, 0, 45),
                    0,
                    getHeight(),
                    new Color(0, 0, 20)
                );
                g2d.setPaint(backgroundGradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // Draw grid lines
                g2d.setColor(new Color(60, 60, 120, 40));
                int gridSize = 20;

                // Horizontal lines
                for (int y = 0; y < getHeight(); y += gridSize) {
                    g2d.drawLine(0, y, getWidth(), y);
                }

                // Vertical lines
                for (int x = 0; x < getWidth(); x += gridSize) {
                    g2d.drawLine(x, 0, x, getHeight());
                }

                g2d.dispose();
            }
        };
        mainPanel.setLayout(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Create search panel with cyber style
        JPanel searchPanel = createSearchPanel();

        // Content area - scrollable panel with custom styling
        contentPanel =
            new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2d = (Graphics2D) g.create();

                    // Draw subtle grid lines
                    g2d.setColor(new Color(60, 60, 120, 20));
                    int gridSize = 15;

                    // Horizontal lines
                    for (int y = 0; y < getHeight(); y += gridSize) {
                        g2d.drawLine(0, y, getWidth(), y);
                    }

                    g2d.dispose();
                }
            };
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(PANEL_COLOR);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel contentContainerPanel = new JPanel(new BorderLayout());
        contentContainerPanel.add(contentPanel, BorderLayout.CENTER);

        // Custom scrollpane with 90s style
        JScrollPane scrollPane = new JScrollPane(contentContainerPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED // Enable horizontal scrolling
        );
        scrollPane.setBorder(createBevelBorder(PANEL_COLOR, 4));
        scrollPane.getViewport().setBackground(PANEL_COLOR);

        // Custom vertical scrollbar UI
        scrollPane
            .getVerticalScrollBar()
            .setUI(
                new BasicScrollBarUI() {
                    @Override
                    protected void configureScrollBarColors() {
                        this.thumbColor = ELECTRIC_BLUE;
                        this.trackColor = new Color(40, 40, 60);
                    }

                    @Override
                    protected JButton createDecreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    @Override
                    protected JButton createIncreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    private JButton createZeroButton() {
                        JButton button = new JButton();
                        button.setPreferredSize(new Dimension(0, 0));
                        button.setMinimumSize(new Dimension(0, 0));
                        button.setMaximumSize(new Dimension(0, 0));
                        return button;
                    }
                }
            );

        // Custom horizontal scrollbar UI
        scrollPane
            .getHorizontalScrollBar()
            .setUI(
                new BasicScrollBarUI() {
                    @Override
                    protected void configureScrollBarColors() {
                        this.thumbColor = NEON_PINK;
                        this.trackColor = new Color(40, 40, 60);
                    }

                    @Override
                    protected JButton createDecreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    @Override
                    protected JButton createIncreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    private JButton createZeroButton() {
                        JButton button = new JButton();
                        button.setPreferredSize(new Dimension(0, 0));
                        button.setMinimumSize(new Dimension(0, 0));
                        button.setMaximumSize(new Dimension(0, 0));
                        return button;
                    }
                }
            );

        // Create footer with load more button
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        footerPanel.setOpaque(false);

        loadMoreButton = createCyberButton("LOAD MORE");
        loadMoreButton.addActionListener(e -> loadMore());
        contentContainerPanel.add(loadMoreButton, BorderLayout.SOUTH);
        // Add sound toggle button with 90s style
        JToggleButton soundToggle = createCyberToggleButton("SOUND: ON");
        soundToggle.setSelected(true);
        soundToggle.addActionListener(e -> {
            soundEnabled = soundToggle.isSelected();
            soundToggle.setText(soundEnabled ? "SOUND: ON" : "SOUND: OFF");
        });
        footerPanel.add(soundToggle);

        // Add components to main panel
        mainPanel.add(searchPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(footerPanel, BorderLayout.SOUTH);

        // Add to frame
        add(titleBar, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);

        // Add border to entire frame
        getRootPane().setBorder(BorderFactory.createLineBorder(ELECTRIC_BLUE, 2));

        // Initialize Nostr connection
        initNostr();

        // Load sound resources
        initSounds();
    }

    private void createTitleBar(String title) {
        titleBar =
            new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2d = (Graphics2D) g.create();

                    // Background gradient
                    GradientPaint gradient = new GradientPaint(
                        0,
                        0,
                        new Color(60, 0, 100),
                        getWidth(),
                        0,
                        new Color(0, 50, 100)
                    );
                    g2d.setPaint(gradient);
                    g2d.fillRect(0, 0, getWidth(), getHeight());

                    // Draw "scanner" line animation
                    int scannerPos = (int) (System.currentTimeMillis() / 50 % getWidth());
                    g2d.setColor(new Color(ELECTRIC_BLUE.getRed(), ELECTRIC_BLUE.getGreen(), ELECTRIC_BLUE.getBlue(), 128));
                    g2d.fillRect(scannerPos, 0, 2, getHeight());

                    g2d.dispose();
                }
            };

        titleBar.setLayout(new BorderLayout());
        titleBar.setPreferredSize(new Dimension(getWidth(), 30));

        // Drag listener for moving window
        titleBar.addMouseListener(
            new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    isDragging = true;
                    dragOffset = e.getPoint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    isDragging = false;
                }
            }
        );

        titleBar.addMouseMotionListener(
            new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (isDragging) {
                        Point currentLocation = getLocation();
                        setLocation(currentLocation.x + e.getX() - dragOffset.x, currentLocation.y + e.getY() - dragOffset.y);
                    }
                }
            }
        );

        // Title with custom styling
        JLabel titleLabel = new JLabel(" :: " + title + " :: ");
        titleLabel.setForeground(HIGHLIGHT_COLOR);
        titleLabel.setFont(new Font("Courier New", Font.BOLD, 14));

        // Close button with cyber style
        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font("Arial", Font.BOLD, 16));
        closeButton.setForeground(NEON_PINK);
        closeButton.setBackground(new Color(40, 0, 60));
        closeButton.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(NEON_PINK, 1),
                BorderFactory.createEmptyBorder(0, 5, 0, 5)
            )
        );
        closeButton.setFocusPainted(false);
        closeButton.addActionListener(e -> System.exit(0));

        // Minimize button
        JButton minButton = new JButton("_");
        minButton.setFont(new Font("Arial", Font.BOLD, 14));
        minButton.setForeground(ELECTRIC_BLUE);
        minButton.setBackground(new Color(40, 0, 60));
        minButton.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ELECTRIC_BLUE, 1),
                BorderFactory.createEmptyBorder(0, 5, 0, 5)
            )
        );
        minButton.setFocusPainted(false);
        minButton.addActionListener(e -> setState(JFrame.ICONIFIED));

        // Add components to title bar
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        buttonPanel.setOpaque(false);
        buttonPanel.add(minButton);
        buttonPanel.add(closeButton);

        titleBar.add(titleLabel, BorderLayout.CENTER);
        titleBar.add(buttonPanel, BorderLayout.EAST);

        // Start animation timer to repaint title bar
        Timer timer = new Timer(50, e -> titleBar.repaint());
        timer.start();
    }

    private JPanel createSearchPanel() {
        JPanel searchPanel = new JPanel(new BorderLayout(10, 0));
        searchPanel.setBorder(
            BorderFactory.createCompoundBorder(
                createBevelBorder(PANEL_COLOR, 4),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
        );
        searchPanel.setBackground(PANEL_COLOR);

        // Search label with futuristic styling
        JLabel searchLabel = new JLabel("<< SEARCH >>");
        searchLabel.setFont(new Font("Courier New", Font.BOLD, 14));
        searchLabel.setForeground(HIGHLIGHT_COLOR);

        // Custom search field
        searchBar = new JTextField(20);
        searchBar.setFont(new Font("Courier New", Font.PLAIN, 14));
        searchBar.setForeground(HIGHLIGHT_COLOR);
        searchBar.setBackground(new Color(10, 10, 30));
        searchBar.setCaretColor(HIGHLIGHT_COLOR);
        searchBar.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ELECTRIC_BLUE, 1),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)
            )
        );
        searchBar.addActionListener(e -> performSearch(searchBar.getText().trim()));

        // Blinker symbol
        JLabel blinker = new JLabel("▋");
        blinker.setFont(new Font("Monospaced", Font.BOLD, 14));
        blinker.setForeground(HIGHLIGHT_COLOR);

        // Blink animation
        Timer blinkTimer = new Timer(
            500,
            e -> {
                blinker.setVisible(!blinker.isVisible());
            }
        );
        blinkTimer.start();

        // Search button with 90s style
        JButton searchButton = createCyberButton("RUN");
        searchButton.addActionListener(e -> performSearch(searchBar.getText().trim()));

        // Add components
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputPanel.setOpaque(false);
        inputPanel.add(searchBar, BorderLayout.CENTER);
        inputPanel.add(blinker, BorderLayout.EAST);

        searchPanel.add(searchLabel, BorderLayout.WEST);
        searchPanel.add(inputPanel, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);

        return searchPanel;
    }

    private JButton createCyberButton(String text) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();

                // Background gradient
                GradientPaint gradient;
                if (getModel().isPressed()) {
                    gradient = new GradientPaint(0, 0, new Color(0, 60, 100), 0, getHeight(), new Color(0, 30, 60));
                } else if (getModel().isRollover()) {
                    gradient = new GradientPaint(0, 0, new Color(0, 80, 120), 0, getHeight(), new Color(0, 40, 80));
                } else {
                    gradient = new GradientPaint(0, 0, new Color(0, 70, 110), 0, getHeight(), new Color(0, 30, 70));
                }

                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // Draw subtle scan lines
                g2d.setColor(new Color(255, 255, 255, 15));
                for (int y = 0; y < getHeight(); y += 2) {
                    g2d.drawLine(0, y, getWidth(), y);
                }

                // Draw text
                String displayText = getText().toUpperCase();
                FontMetrics fm = g2d.getFontMetrics();
                Rectangle2D textBounds = fm.getStringBounds(displayText, g2d);

                int textX = (int) (getWidth() - textBounds.getWidth()) / 2;
                int textY = (int) ((getHeight() - textBounds.getHeight()) / 2) + fm.getAscent();

                g2d.setColor(HIGHLIGHT_COLOR);
                g2d.drawString(displayText, textX, textY);

                g2d.dispose();
            }
        };

        button.setFont(new Font("Courier New", Font.BOLD, 12));
        button.setForeground(HIGHLIGHT_COLOR);
        button.setFocusPainted(false);
        button.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ELECTRIC_BLUE, 1),
                BorderFactory.createEmptyBorder(5, 15, 5, 15)
            )
        );
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        return button;
    }

    private JToggleButton createCyberToggleButton(String text) {
        JToggleButton button = new JToggleButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();

                // Background gradient based on selection state
                GradientPaint gradient;
                if (isSelected()) {
                    gradient = new GradientPaint(0, 0, new Color(0, 80, 50), 0, getHeight(), new Color(0, 40, 30));
                } else {
                    gradient = new GradientPaint(0, 0, new Color(80, 0, 50), 0, getHeight(), new Color(40, 0, 30));
                }

                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // Draw subtle scan lines
                g2d.setColor(new Color(255, 255, 255, 15));
                for (int y = 0; y < getHeight(); y += 2) {
                    g2d.drawLine(0, y, getWidth(), y);
                }

                // Draw text
                String displayText = getText().toUpperCase();
                FontMetrics fm = g2d.getFontMetrics();
                Rectangle2D textBounds = fm.getStringBounds(displayText, g2d);

                int textX = (int) (getWidth() - textBounds.getWidth()) / 2;
                int textY = (int) ((getHeight() - textBounds.getHeight()) / 2) + fm.getAscent();

                g2d.setColor(isSelected() ? LIME_GREEN : NEON_PINK);
                g2d.drawString(displayText, textX, textY);

                g2d.dispose();
            }
        };

        button.setFont(new Font("Courier New", Font.BOLD, 12));
        button.setForeground(HIGHLIGHT_COLOR);
        button.setFocusPainted(false);
        button.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ELECTRIC_BLUE, 1),
                BorderFactory.createEmptyBorder(5, 15, 5, 15)
            )
        );
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        return button;
    }

    private Border createBevelBorder(Color baseColor, int thickness) {
        Color brighter = baseColor.brighter().brighter();
        Color darker = baseColor.darker().darker();

        Border outside = BorderFactory.createMatteBorder(1, 1, 1, 1, darker);
        Border inside = BorderFactory.createMatteBorder(1, 1, 1, 1, brighter);
        Border center = BorderFactory.createEmptyBorder(thickness - 2, thickness - 2, thickness - 2, thickness - 2);

        return BorderFactory.createCompoundBorder(outside, BorderFactory.createCompoundBorder(center, inside));
    }

    private void initNostr() {
        this.pool = new NostrPool();
        this.pool.connectRelay(new NostrRelay("wss://nostr.wine"));

        // Show notice in a cyber dialog
        this.pool.addNoticeListener((relay, notice, ex) ->
                SwingUtilities.invokeLater(() -> showCyberMessageBox("SYSTEM NOTICE", notice))
            );

        // Subscribe to events
        NostrSubscription sub =
            this.pool.subscribe(Arrays.asList(new NostrFilter().withKind(1).limit(10)), ()->new FailOnDoubleTracker());

        sub.addEventListener((s, event, stored) -> addEventToFeed(event, true));
        sub.open();
    }

    private void generateInitSound() {
        // Generate a sawtooth wave sequence
        byte[] sequence = new byte[0];

        // Combine 4 notes in ascending order (startup sound)
        float[] frequencies = { 220, 330, 440, 660 };
        int[] durations = { 80, 80, 80, 150 };

        for (int i = 0; i < frequencies.length; i++) {
            byte[] note = generateRetroSound(frequencies[i], durations[i], i % 3);

            // Create tiny gap between notes
            byte[] gap = new byte[220];

            // Combine into sequence
            byte[] combined = new byte[sequence.length + note.length + gap.length];
            System.arraycopy(sequence, 0, combined, 0, sequence.length);
            System.arraycopy(note, 0, combined, sequence.length, note.length);
            System.arraycopy(gap, 0, combined, sequence.length + note.length, gap.length);

            sequence = combined;
        }

        // Play the startup sound
        playSound(sequence);
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
                case 2: // Sawtooth (classic 90s synth sound)
                    sample = (angle % (2 * Math.PI)) / Math.PI - 1.0;
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
            output[i * 2] = (byte) (value & 0xFF);
            output[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }

        return output;
    }

    private byte[] generateBeepSound(float frequency, int duration) {
        // Use sawtooth wave for more 90s computer feel
        return generateRetroSound(frequency, duration, 2);
    }

    private byte[] generateGoodMorningSound() {
        // Create a futuristic 90s jingle sequence
        byte[] note1 = generateRetroSound(523, 80, 0); // C5 - Square wave
        byte[] note2 = generateRetroSound(659, 80, 2); // E5 - Sawtooth
        byte[] note3 = generateRetroSound(784, 100, 0); // G5 - Square wave
        byte[] note4 = generateRetroSound(1047, 150, 2); // C6 - Sawtooth

        // Create tiny gaps between notes
        byte[] gap = new byte[220]; // 5ms of silence at 44.1kHz

        // Combine all notes with gaps
        byte[] combined = new byte[note1.length +
        gap.length +
        note2.length +
        gap.length +
        note3.length +
        gap.length +
        note4.length];

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
            // Generate notification sound (cyberpunk style blip)
            newEventSoundData = generateBeepSound(880, 120);

            // Generate GM sound (futuristic jingle)
            gmSoundData = generateGoodMorningSound();

            System.out.println("Cyber sounds initialized");

            // Play startup sound
            generateInitSound();
        } catch (Exception e) {
            System.err.println("Could not initialize sounds: " + e.getMessage());
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
        loadMoreButton.setText("LOADING...");

        this.pool.fetch(
                Arrays.asList(new NostrSearchFilter()
                    .withKind(1)
                    .search(searchBar.getText().trim())
                    .until(Instant.ofEpochSecond(earliestEvent))
                    .limit(5)),
                ()->new FailOnDoubleTracker(),
                NostrAllEOSEPoolFetchPolicy.get()
            )
            .catchException(e -> {
                SwingUtilities.invokeLater(() -> {
                    showCyberMessageBox("ERROR", e.getMessage());
                    loadMoreButton.setText("LOAD MORE");
                    loadMoreButton.setEnabled(true);
                });
            })
            .then(events -> {
                SwingUtilities.invokeLater(() -> {
                    for (SignedNostrEvent event : events) {
                        addEventToFeed(event, false);
                    }
                    loadMoreButton.setText("LOAD MORE");
                    loadMoreButton.setEnabled(true);
                });
                return null;
            });
    }

    private void performSearch(String query) {
        if (query.isEmpty()) return;

        // Clear content
        contentPanel.removeAll();

        // Add retro-style loading animation
        JPanel loadingPanel = new JPanel(new GridBagLayout());
        loadingPanel.setOpaque(false);

        JLabel searchingLabel = new JLabel("SEARCHING: " + query);
        searchingLabel.setFont(new Font("Courier New", Font.BOLD, 14));
        searchingLabel.setForeground(HIGHLIGHT_COLOR);

        loadingPanel.add(searchingLabel);
        contentPanel.add(loadingPanel);
        contentPanel.revalidate();
        contentPanel.repaint();

        // Reset tracking
        earliestEvent = Long.MAX_VALUE;

        // Perform search
        pool.unsubscribeAll();
        NostrSubscription sub = pool.subscribe(Arrays.asList(new NostrSearchFilter().withKind(1).limit(10).search(query)));

        sub.addEventListener((s, event, stored) -> {
            SwingUtilities.invokeLater(() -> {
                // Remove searching label on first result
                if (contentPanel.getComponentCount() == 1 && contentPanel.getComponent(0) == loadingPanel) {
                    contentPanel.removeAll();
                }
                addEventToFeed(event, true);
            });
        });

        sub.open();
    }

    private void addEventToFeed(SignedNostrEvent event, boolean top) {
        if (event.getCreatedAt().getEpochSecond() < earliestEvent) {
            earliestEvent = event.getCreatedAt().getEpochSecond();
        }

        // Create cyber-styled panel for this event
        JPanel eventPanel = createEventPanel(event);

        // Set maximum width and alignment
        eventPanel.setMaximumSize(new Dimension(Short.MAX_VALUE, eventPanel.getMaximumSize().height));
        eventPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (top) {
            contentPanel.add(eventPanel, 0);
        } else {
            contentPanel.add(eventPanel);
        }

        // Add cyber separator
        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL) {
            @Override
            public void paint(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();

                // Draw cyber-styled separator
                int y = getHeight() / 2;

                // Main line
                g2d.setColor(new Color(ELECTRIC_BLUE.getRed(), ELECTRIC_BLUE.getGreen(), ELECTRIC_BLUE.getBlue(), 80));
                g2d.drawLine(0, y, getWidth(), y);

                // Highlight points
                for (int x = 0; x < getWidth(); x += 50) {
                    g2d.setColor(ELECTRIC_BLUE);
                    g2d.fillRect(x, y - 1, 3, 3);
                }

                g2d.dispose();
            }
        };

        separator.setForeground(ELECTRIC_BLUE);
        separator.setPreferredSize(new Dimension(10, 15));

        if (top) {
            contentPanel.add(separator, 1);
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

        // Check for GM variations
        return lowerContent.contains("gm");
    }

    private JPanel createEventPanel(SignedNostrEvent event) {
        // Get pubkey and event ID
        final String fullPubkey = event.getPubkey().asHex();
        final String shortPubkey = fullPubkey.substring(0, 8) + "...";
        final String eventId = event.getId();
        final String shortEventId = eventId.substring(0, 8) + "...";

        // Create cyber-styled panel
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();

                // Background gradient
                GradientPaint gradient = new GradientPaint(0, 0, new Color(30, 30, 60), 0, getHeight(), new Color(20, 20, 45));
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // Draw techno border with data points
                g2d.setColor(ELECTRIC_BLUE);
                for (int x = 0; x < getWidth(); x += 20) {
                    int pointWidth = (x % 60 == 0) ? 5 : 2;
                    g2d.fillRect(x, 0, pointWidth, 1);
                    g2d.fillRect(x, getHeight() - 1, pointWidth, 1);
                }
                for (int y = 0; y < getHeight(); y += 20) {
                    int pointHeight = (y % 60 == 0) ? 5 : 2;
                    g2d.fillRect(0, y, 1, pointHeight);
                    g2d.fillRect(getWidth() - 1, y, 1, pointHeight);
                }

                g2d.dispose();
            }
        };

        panel.setLayout(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Header with author info
        JPanel headerPanel = new JPanel(new BorderLayout(5, 0));
        headerPanel.setOpaque(false);

        // Basic author label
        JLabel authorLabel = new JLabel(shortPubkey);
        authorLabel.setFont(new Font("Courier New", Font.BOLD, 13));
        authorLabel.setForeground(HIGHLIGHT_COLOR);
        authorLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        authorLabel.addMouseListener(
            new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    copyToClipboard(fullPubkey);
                    showCyberMessageBox("SYSTEM", "PUBKEY COPIED TO SYSTEM BUFFER");
                }
            }
        );

        // Event ID label with cyber style
        JPanel idPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        idPanel.setOpaque(false);

        JLabel idLabel = new JLabel("[ID:" + shortEventId + "]");
        idLabel.setFont(new Font("Courier New", Font.PLAIN, 11));
        idLabel.setForeground(SECONDARY_TEXT);
        idLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        idLabel.addMouseListener(
            new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    copyToClipboard(eventId);
                    showCyberMessageBox("SYSTEM", "EVENT ID COPIED TO SYSTEM BUFFER");
                }
            }
        );

        idPanel.add(idLabel);

        JPanel authorInfoPanel = new JPanel(new BorderLayout());
        authorInfoPanel.setOpaque(false);
        authorInfoPanel.add(authorLabel, BorderLayout.NORTH);
        authorInfoPanel.add(idPanel, BorderLayout.SOUTH);

        // Timestamp with futuristic style
        JLabel timestampLabel = new JLabel(formatTimestamp(event.getCreatedAt().getEpochSecond()));
        timestampLabel.setFont(new Font("Courier New", Font.BOLD, 11));
        timestampLabel.setForeground(NEON_PINK);
        timestampLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        headerPanel.add(authorInfoPanel, BorderLayout.WEST);
        headerPanel.add(timestampLabel, BorderLayout.EAST);

        // Create main content panel that can hold both text and images
        JPanel contentMainPanel = new JPanel();
        contentMainPanel.setLayout(new BoxLayout(contentMainPanel, BoxLayout.Y_AXIS));
        contentMainPanel.setOpaque(false);

        // Content with cyber styling
        JTextPane contentArea = new JTextPane();
        contentArea.setEditable(false);
        contentArea.setOpaque(false);
        contentArea.setText(event.getContent());
        contentArea.setFont(new Font("Courier New", Font.PLAIN, 13));
        contentArea.setForeground(TEXT_COLOR);

        // Center text and set style
        StyledDocument doc = contentArea.getStyledDocument();
        SimpleAttributeSet centerStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(centerStyle, TEXT_COLOR);
        doc.setParagraphAttributes(0, doc.getLength(), centerStyle, false);

        JScrollPane contentScroll = new JScrollPane(contentArea);
        contentScroll.setPreferredSize(new Dimension(10, 100));
        contentScroll.setBorder(BorderFactory.createLineBorder(ELECTRIC_BLUE, 1));
        contentScroll.setOpaque(false);
        contentScroll.getViewport().setOpaque(false);

        // Custom vertical scrollbar UI
        contentScroll
            .getVerticalScrollBar()
            .setUI(
                new BasicScrollBarUI() {
                    @Override
                    protected void configureScrollBarColors() {
                        this.thumbColor = ELECTRIC_BLUE;
                        this.trackColor = new Color(40, 40, 60);
                    }

                    @Override
                    protected JButton createDecreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    @Override
                    protected JButton createIncreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    private JButton createZeroButton() {
                        JButton button = new JButton();
                        button.setPreferredSize(new Dimension(0, 0));
                        button.setMinimumSize(new Dimension(0, 0));
                        button.setMaximumSize(new Dimension(0, 0));
                        return button;
                    }
                }
            );

        // Custom horizontal scrollbar UI
        contentScroll
            .getHorizontalScrollBar()
            .setUI(
                new BasicScrollBarUI() {
                    @Override
                    protected void configureScrollBarColors() {
                        this.thumbColor = NEON_PINK;
                        this.trackColor = new Color(40, 40, 60);
                    }

                    @Override
                    protected JButton createDecreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    @Override
                    protected JButton createIncreaseButton(int orientation) {
                        return createZeroButton();
                    }

                    private JButton createZeroButton() {
                        JButton button = new JButton();
                        button.setPreferredSize(new Dimension(0, 0));
                        button.setMinimumSize(new Dimension(0, 0));
                        button.setMaximumSize(new Dimension(0, 0));
                        return button;
                    }
                }
            );
        contentMainPanel.add(contentScroll);

        // Check for image URLs in content
        String content = event.getContent();
        Pattern pattern = Pattern.compile("(https?://\\S+\\.(jpg|jpeg|png|gif|bmp))", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(content);

        // If image URLs are found, create an image panel
        if (matcher.find()) {
            matcher.reset();
            while (matcher.find()) {
                String imageUrl = matcher.group(1);

                // Loading indicator with 90s cyber style
                JPanel imageContainer = new JPanel(new BorderLayout());
                imageContainer.setOpaque(false);
                imageContainer.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

                JPanel loadingPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
                loadingPanel.setOpaque(false);

                JLabel loadingLabel = new JLabel(
                    "LOADING IMAGE: [" + imageUrl.substring(0, Math.min(imageUrl.length(), 30)) + "...]"
                );
                loadingLabel.setFont(new Font("Courier New", Font.ITALIC, 11));
                loadingLabel.setForeground(ELECTRIC_BLUE);

                loadingPanel.add(loadingLabel);
                imageContainer.add(loadingPanel, BorderLayout.CENTER);
                contentMainPanel.add(imageContainer);

                // Load the image asynchronously
                loadImageAsync(imageUrl, imageContainer);
            }
        }

        // Add all components
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

                        // Create a panel with cyber border
                        JPanel imageWrapper = new JPanel(new BorderLayout());
                        imageWrapper.setBorder(
                            BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(ELECTRIC_BLUE, 1),
                                BorderFactory.createEmptyBorder(1, 1, 1, 1)
                            )
                        );
                        imageWrapper.setOpaque(false);

                        JLabel imageLabel = new JLabel(icon);
                        imageWrapper.add(imageLabel, BorderLayout.CENTER);

                        container.add(imageWrapper, BorderLayout.CENTER);
                        container.revalidate();
                        container.repaint();
                    } else {
                        container.removeAll();
                        JLabel errorLabel = new JLabel(">> IMAGE LOAD FAILED <<");
                        errorLabel.setFont(new Font("Courier New", Font.BOLD, 11));
                        errorLabel.setForeground(NEON_PINK);
                        errorLabel.setHorizontalAlignment(JLabel.CENTER);
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
            authorLabel.setText("_" + nameCache.get(key) + "_");
            return;
        }
        // Fetch metadata
        // AsyncTask<Nip24Metadata> task = metadataCache.computeIfAbsent(
        //     key,
        //     k -> Nip24Metadata.fetch(this.pool, pubkey)
        // );

        // task
        //     .then(metadata -> {
        //         if (metadata != null) {
        //             SwingUtilities.invokeLater(() -> {
        //                 String name = metadata.getDisplayName();
        //                 if (name == null) name = metadata.getName();
        //                 if (name == null || name.isEmpty()) {
        //                     name = key.substring(0, 8) + "...";
        //                 }

        //                 // Update cache and label with cyber style
        //                 nameCache.put(key, name);
        //                 authorLabel.setText("_" + name + "_");
        //             });
        //         }
        //         return null;
        //     })
        //     .exceptionally(ex -> {
        //         System.out.println(
        //             "Error fetching metadata: " + ex.getMessage()
        //         );
        //     });
    }

    private void copyToClipboard(String text) {
        StringSelection stringSelection = new StringSelection(text);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(stringSelection, null);
    }

    private String formatTimestamp(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(timestamp * 1000));
    }

    private void showCyberMessageBox(String title, String message) {
        // Create custom dialog with cyber style
        final JDialog dialog = new JDialog(this, title, true);
        dialog.setUndecorated(true);

        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();

                // Background gradient
                GradientPaint gradient = new GradientPaint(0, 0, new Color(20, 20, 60), 0, getHeight(), new Color(10, 10, 30));
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // Draw grid
                g2d.setColor(new Color(60, 60, 120, 30));
                int gridSize = 15;

                for (int y = 0; y < getHeight(); y += gridSize) {
                    g2d.drawLine(0, y, getWidth(), y);
                }

                for (int x = 0; x < getWidth(); x += gridSize) {
                    g2d.drawLine(x, 0, x, getHeight());
                }

                g2d.dispose();
            }
        };

        panel.setBorder(
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(NEON_PINK, 2),
                BorderFactory.createEmptyBorder(15, 20, 15, 20)
            )
        );

        // Title bar
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        titlePanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ELECTRIC_BLUE));

        JLabel titleLabel = new JLabel(":: " + title + " ::");
        titleLabel.setFont(new Font("Courier New", Font.BOLD, 14));
        titleLabel.setForeground(HIGHLIGHT_COLOR);
        titlePanel.add(titleLabel, BorderLayout.CENTER);

        // Message with style
        JTextPane messagePane = new JTextPane();
        messagePane.setOpaque(false);
        messagePane.setEditable(false);
        messagePane.setText(message);
        messagePane.setFont(new Font("Courier New", Font.PLAIN, 13));
        messagePane.setForeground(TEXT_COLOR);

        // OK button with cyber style
        JButton okButton = createCyberButton("OK");
        okButton.addActionListener(e -> dialog.dispose());

        // Layout components
        JPanel contentPanel = new JPanel(new BorderLayout(0, 15));
        contentPanel.setOpaque(false);
        contentPanel.add(titlePanel, BorderLayout.NORTH);
        contentPanel.add(messagePane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setOpaque(false);
        buttonPanel.add(okButton);

        contentPanel.add(buttonPanel, BorderLayout.SOUTH);
        panel.add(contentPanel, BorderLayout.CENTER);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setSize(Math.max(300, dialog.getWidth()), Math.max(150, dialog.getHeight()));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    public static void main(String[] args) {
        // Set system properties for better font rendering
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {
            NostrClient client = new NostrClient("NOSTR FEED");
            client.setVisible(true);
        });
    }
}
