package com.audiostreaming;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.concurrent.*;

/**
 * Discord-like voice chat UI built with Swing that connects to VoiceChatClient.
 * Uses a hardcoded server IP (127.0.0.1) and the default VoiceChatClient constructor.
 * Handles graceful shutdown on close or force exit.
 */
public class AudioStreamingUI {

    private final String serverIp = "127.0.0.1";

    private JFrame frame;
    private JLabel channelTitleLabel;
    private JLabel muteLabel;
    private JTextField usernameField;
    private DefaultListModel<String> userModel;
    private JList<String> userList;

    private JButton joinButton;
    private JButton leaveButton;
    private JButton muteButton;

    private boolean muted = false;
    private volatile boolean connected = false;
    private volatile boolean joined = false;
    private volatile boolean awaitingMuteAck = false;
    private int myAssignedId = -1;

    private VoiceChatClient client;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService bgPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ui-bg");
        t.setDaemon(true);
        return t;
    });

    public void showUI() {
        frame = new JFrame("Discord-like Voice Server");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(buildRoot());

        // Confirm close
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int confirm = JOptionPane.showConfirmDialog(frame,
                        "Are you sure you want to exit?",
                        "Confirm Exit", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    shutdown();
                    frame.dispose();
                    System.exit(0);
                }
            }
        });

        // JVM shutdown hook — catches Ctrl+C, OS kill, etc.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdown();
            } catch (Exception ignored) {}
        }));

        // Periodically poll participant list while joined
        scheduler.scheduleAtFixedRate(() -> {
            if (connected && joined && client != null) refreshParticipants();
        }, 0, 2, TimeUnit.SECONDS);

        frame.setVisible(true);
    }

    private JPanel buildRoot() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(43, 45, 49));
        root.add(buildHeader(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(buildSidebar());
        splitPane.setRightComponent(buildMain());
        splitPane.setDividerLocation(220);
        splitPane.setDividerSize(1);
        splitPane.setBorder(null);
        root.add(splitPane, BorderLayout.CENTER);

        root.add(buildFooter(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(37, 39, 43));
        header.setBorder(new EmptyBorder(10, 20, 10, 20));

        channelTitleLabel = new JLabel("Voice Channel: General");
        channelTitleLabel.setForeground(Color.WHITE);
        channelTitleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));

        header.add(channelTitleLabel, BorderLayout.WEST);
        return header;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(new Color(37, 39, 43));
        sidebar.setBorder(new EmptyBorder(15, 10, 15, 10));

        JLabel serversTitle = new JLabel("Servers");
        serversTitle.setForeground(Color.GRAY);
        serversTitle.setFont(new Font("Segoe UI", Font.BOLD, 14));
        serversTitle.setBorder(new EmptyBorder(0, 15, 10, 0));

        DefaultListModel<String> serverModel = new DefaultListModel<>();
        serverModel.addElement("🎮 My Server");

        JList<String> serverList = new JList<>(serverModel);
        serverList.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        serverList.setBackground(new Color(37, 39, 43));
        serverList.setForeground(Color.WHITE);
        serverList.setSelectionBackground(new Color(60, 63, 68));
        serverList.setCellRenderer(new ServerCellRenderer());
        serverList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 1) connectToServer();
            }
        });

        JScrollPane scroll = new JScrollPane(serverList);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(new Color(37, 39, 43));

        sidebar.add(serversTitle, BorderLayout.NORTH);
        sidebar.add(scroll, BorderLayout.CENTER);
        return sidebar;
    }

    private JPanel buildMain() {
        JPanel main = new JPanel(new BorderLayout(20, 20));
        main.setBackground(new Color(43, 45, 49));
        main.setBorder(new EmptyBorder(20, 20, 20, 20));

        // Participants panel
        JPanel participantsPanel = new RoundedPanel(12);
        participantsPanel.setBackground(new Color(50, 52, 56));
        participantsPanel.setLayout(new BorderLayout(10, 10));
        participantsPanel.setBorder(new EmptyBorder(15, 20, 15, 20));

        JLabel participantsTitle = new JLabel("Participants in Call:");
        participantsTitle.setForeground(Color.WHITE);
        participantsTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        participantsTitle.setBorder(new EmptyBorder(0, 0, 10, 0));

        userModel = new DefaultListModel<>();
        userList = new JList<>(userModel);
        userList.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        userList.setBackground(new Color(50, 52, 56));
        userList.setForeground(Color.WHITE);
        userList.setSelectionBackground(new Color(60, 63, 68));
        userList.setFixedCellHeight(36);
        userList.setCellRenderer(new UserCellRenderer());

        JScrollPane scroll = new JScrollPane(userList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(50, 52, 56));

        participantsPanel.add(participantsTitle, BorderLayout.NORTH);
        participantsPanel.add(scroll, BorderLayout.CENTER);

        // Controls panel
        JPanel controlsPanel = new RoundedPanel(12);
        controlsPanel.setBackground(new Color(50, 52, 56));
        controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
        controlsPanel.setBorder(new EmptyBorder(15, 20, 15, 20));

        JPanel usernamePanel = new JPanel();
        usernamePanel.setLayout(new BoxLayout(usernamePanel, BoxLayout.Y_AXIS));
        usernamePanel.setOpaque(false);

        // Label
        JLabel userLabel = new JLabel("Enter your username:");
        userLabel.setForeground(Color.LIGHT_GRAY);
        userLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        userLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Text field
        usernameField = new JTextField("Guest");
        usernameField.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        usernameField.setMaximumSize(new Dimension(300, 30));
        usernameField.setBackground(new Color(60, 63, 68));
        usernameField.setForeground(Color.WHITE);
        usernameField.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        usernameField.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Add spacing
        usernamePanel.add(userLabel);
        usernamePanel.add(Box.createVerticalStrut(5));
        usernamePanel.add(usernameField);


        // Buttons
        JButton joinBtn = styledButton("Join Call", new Color(59, 130, 246));
        joinBtn.addActionListener(e -> joinCall());
        JButton leaveBtn = styledButton("Leave Call", new Color(220, 53, 69));
        leaveBtn.addActionListener(e -> leaveServer());
        leaveBtn.setVisible(false);

        JButton muteBtn = styledButton("Toggle Mute", new Color(75, 130, 246));
        muteBtn.addActionListener(e -> toggleMute());
        muteLabel = new JLabel("", JLabel.LEFT);
        muteLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));

        // Button containers
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(joinBtn);
        buttonPanel.add(leaveBtn);

        JPanel mutePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        mutePanel.setOpaque(false);
        mutePanel.add(muteBtn);
        mutePanel.add(muteLabel);

        controlsPanel.add(userLabel);
        controlsPanel.add(Box.createVerticalStrut(5));
        controlsPanel.add(usernameField);
        controlsPanel.add(Box.createVerticalStrut(15));
        controlsPanel.add(buttonPanel);
        controlsPanel.add(Box.createVerticalStrut(15));
        controlsPanel.add(mutePanel);

        main.add(participantsPanel, BorderLayout.CENTER);
        main.add(controlsPanel, BorderLayout.SOUTH);

        this.joinButton = joinBtn;
        this.leaveButton = leaveBtn;
        this.muteButton = muteBtn;
        updateMuteStatus();

        return main;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(37, 39, 43));
        footer.setBorder(new EmptyBorder(6, 20, 6, 20));

        JLabel left = new JLabel("Connected to server | Audio Streaming Active");
        left.setForeground(Color.GRAY);
        JLabel right = new JLabel("© 2025 Voice App");
        right.setForeground(Color.GRAY);

        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    // ===== Functional methods =====
    private void connectToServer() {
        if (connected) {
            JOptionPane.showMessageDialog(frame, "Already connected.");
            return;
        }

        userModel.clear();
        userModel.addElement("Connecting...");

        bgPool.submit(() -> {
            try {
                client = new VoiceChatClient(); // Uses hardcoded IP
                client.connect();
                connected = true;
                SwingUtilities.invokeLater(() -> {
                    userModel.clear();
                    userModel.addElement("Connected to " + serverIp);
                    joinButton.setVisible(true);
                    JOptionPane.showMessageDialog(frame, "Connected successfully!");
                });
            } catch (Exception e) {
                e.printStackTrace();
                connected = false;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, "Failed to connect: " + e.getMessage()));
            }
        });
    }

    private void joinCall() {
        if (!connected || client == null) {
            JOptionPane.showMessageDialog(frame, "Connect first.");
            return;
        }
        String user = usernameField.getText().trim();
        if (user.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Enter username!");
            return;
        }
        if (joined) {
            JOptionPane.showMessageDialog(frame, "Already joined!");
            return;
        }

        bgPool.submit(() -> {
            try {
                int id = client.register(user);
                myAssignedId = id;
                client.joinSession();
                joined = true;
                refreshParticipants();
                SwingUtilities.invokeLater(() -> {
                    joinButton.setVisible(false);
                    leaveButton.setVisible(true);
                });
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, "Join failed: " + e.getMessage()));
            }
        });
    }

    private void leaveServer() {
        if (!joined) return;
        joined = false;
        connected = false;

        SwingUtilities.invokeLater(() -> {
            leaveButton.setVisible(false);
            joinButton.setVisible(true);
            userModel.clear();
            userModel.addElement("Disconnected");
        });

        bgPool.submit(() -> {
            try {
                if (client != null) {
                    client.leaveSession();
                    client.disconnect();
                }
            } catch (Exception ignored) {}
            client = null;
        });

        muted = true;
        updateMuteStatus();
    }

    private void toggleMute() {
        if (client == null) {
            JOptionPane.showMessageDialog(frame, "Not connected.");
            return;
        }
        if (awaitingMuteAck) return;

        awaitingMuteAck = true;
        muteButton.setEnabled(false);

        bgPool.submit(() -> {
            try {
                if (!muted) client.mute();
                else client.unmute();
                muted = !muted;
            } catch (IOException ignored) {
            } finally {
                awaitingMuteAck = false;
                SwingUtilities.invokeLater(() -> {
                    muteButton.setEnabled(true);
                    updateMuteStatus();
                });
            }
        });
    }

    private void refreshParticipants() {
        if (client == null) return;
        try {
            String[] fetched = client.getActiveUsers();
            final String[] users = (fetched != null) ? fetched : new String[0];

            String myName = usernameField.getText().trim();
            SwingUtilities.invokeLater(() -> {
                userModel.clear();
                for (String u : users) {
                    if (u.equals(myName)) {
                        userModel.addElement(u + " (You)"); // mark yourself
                    } else {
                        userModel.addElement(u);
                    }
                }
            });
        } catch (IOException ignored) {}
    }

    private void updateMuteStatus() {
        if (muted) {
            muteLabel.setText("Mic: Muted");
            muteLabel.setForeground(Color.RED);
        } else {
            muteLabel.setText("Mic: Live");
            muteLabel.setForeground(new Color(0, 200, 0));
        }
    }

    private void shutdown() {
        System.out.println("[UI] Cleaning up before exit...");
        try { leaveServer(); } catch (Exception ignored) {}
        try { scheduler.shutdownNow(); } catch (Exception ignored) {}
        try { bgPool.shutdownNow(); } catch (Exception ignored) {}
        try { if (client != null) client.disconnect(); } catch (Exception ignored) {}
        client = null;
    }

    private JButton styledButton(String text, Color color) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btn.setBackground(color);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(color.brighter()); }
            @Override public void mouseExited(MouseEvent e) { btn.setBackground(color); }
        });
        return btn;
    }

    private static class RoundedPanel extends JPanel {
        private final int radius;
        public RoundedPanel(int radius) { this.radius = radius; setOpaque(false); }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class ServerCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            label.setBorder(new EmptyBorder(8, 15, 8, 15));
            label.setFont(new Font("Segoe UI", Font.PLAIN, 15));
            label.setForeground(Color.WHITE);
            label.setBackground(isSelected ? new Color(60, 63, 68) : new Color(37, 39, 43));
            return label;
        }
    }

    private static class UserCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            label.setBorder(new EmptyBorder(6, 15, 6, 15));
            label.setFont(new Font("Segoe UI", Font.PLAIN, 15));
            label.setForeground(Color.WHITE);
            label.setBackground(isSelected ? new Color(60, 63, 68) : new Color(50, 52, 56));
            return label;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new AudioStreamingUI().showUI());
    }
}
