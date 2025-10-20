package com.audiostreaming;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Discord-like voice chat UI built with Swing.
 * <p>
 * Provides a graphical interface for connecting to the audio server,
 * joining voice calls, managing username, and controlling microphone mute state.
 * </p>
 */
public class AudioStreamingUI {

    private JLabel channelTitleLabel;
    private JFrame frame;
    private JTextField usernameField;
    private JLabel muteLabel;
    private DefaultListModel<String> userModel;
    private JList<String> userList;
    // Start unmuted to match client default
    private boolean muted = false;
    private boolean connected = false;
    private boolean joined = false;
    private VoiceChatClient client;
    private java.util.function.Consumer<String> uiServerListener;
    private final java.util.Map<Integer, String> idToName = new java.util.concurrent.ConcurrentHashMap<>();
    private int myAssignedId = -1;

    private JButton joinButton;
    private JButton leaveButton;
    private JButton muteButton; // store reference to disable/enable during toggle
    private volatile boolean awaitingMuteAck = false;

    /**
     * Displays the voice chat UI window.
     */
    public void showUI() {
        frame = new JFrame("Discord-like Voice Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(buildRoot());
        frame.setVisible(true);
    }

    /**
     * Initializes UI components without showing the window (for testing).
     */
    public void initForTests() {
        buildRoot();
    }

    /**
     * Sets the client instance for testing.
     * 
     * @param c the VoiceChatClient instance
     * @param connected whether the client is connected
     */
    public void setClientForTests(VoiceChatClient c, boolean connected) {
        this.client = c;
        this.connected = connected;
    }

    /**
     * Sets the username field value for testing.
     * 
     * @param username the username to set
     */
    public void setUsernameForTests(String username) {
        if (this.usernameField == null) initForTests();
        this.usernameField.setText(username);
    }

    /**
     * Triggers the join call action for testing.
     */
    public void callJoin() { joinCall(); }
    
    /**
     * Triggers the leave server action for testing.
     */
    public void callLeave() { leaveServer(); }

    /**
     * Returns the user model for testing.
     * 
     * @return the DefaultListModel containing participant names
     */
    public DefaultListModel<String> getUserModelForTests() {
        if (this.userModel == null) initForTests();
        return this.userModel;
    }

    /**
     * Sets the user model for testing.
     * 
     * @param model the DefaultListModel to use
     */
    public void setUserModelForTests(DefaultListModel<String> model) {
        if (this.userList == null) initForTests();
        this.userModel = model;
        this.userList.setModel(model);
    }

    /**
     * Sets the joined state for testing.
     * 
     * @param j whether the user is joined
     */
    public void setJoinedForTests(boolean j) { this.joined = j; }

    /**
     * Toggles mute state for testing.
     */
    public void callToggleMute() { toggleMute(); }
    
    /**
     * Returns the mute label text for testing.
     * 
     * @return the current mute label text
     */
    public String getMuteLabelTextForTests() { return muteLabel == null ? null : muteLabel.getText(); }

    private JPanel buildRoot() { // this is a main function because it is used in the App.java code
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(43, 45, 49));
        root.add(buildHeader(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(buildSidebar());
        splitPane.setRightComponent(buildMain());
        splitPane.setDividerLocation(220);
        splitPane.setBorder(null);
        splitPane.setDividerSize(1);
        root.add(splitPane, BorderLayout.CENTER);

        root.add(buildFooter(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(37, 39, 43));
        header.setBorder(new EmptyBorder(10, 20, 10, 20));

        channelTitleLabel = new JLabel("  Voice Channel: General", loadIcon("channel.png", 20, 20), JLabel.LEFT);
        channelTitleLabel.setForeground(Color.WHITE);
        channelTitleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));

        header.add(channelTitleLabel, BorderLayout.WEST);
        return header;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(new Color(37, 39, 43));
        sidebar.setBorder(new EmptyBorder(15, 10, 15, 10));

        JLabel serversTitle = new JLabel(" Servers", loadIcon("folder.png", 18, 18), JLabel.LEFT);
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
                if (e.getClickCount() == 1) {
                    connectToServer();
                }
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
        JPanel main = new JPanel(new BorderLayout(15, 15));
        main.setBackground(new Color(43, 45, 49));
        main.setBorder(new EmptyBorder(20, 20, 20, 20));

        JPanel participantsPanel = new RoundedPanel(12);
        participantsPanel.setBackground(new Color(50, 52, 56));
        participantsPanel.setLayout(new BorderLayout());
        participantsPanel.setBorder(new EmptyBorder(15, 20, 15, 20));

        JLabel participantsTitle = new JLabel(" Participants in Call:", loadIcon("group.png", 20, 20), JLabel.LEFT);
        participantsTitle.setForeground(Color.WHITE);
        participantsTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));

        userModel = new DefaultListModel<>();
        userList = new JList<>(userModel);
        userList.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        userList.setBackground(new Color(50, 52, 56));
        userList.setForeground(Color.WHITE);
        userList.setSelectionBackground(new Color(60, 63, 68));
        userList.setFixedCellHeight(36);
        userList.setCellRenderer(new UserCellRenderer());

        JScrollPane scroll = new JScrollPane(userList);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(new Color(50, 52, 56));

        participantsPanel.add(participantsTitle, BorderLayout.NORTH);
        participantsPanel.add(scroll, BorderLayout.CENTER);

        JPanel controlsPanel = new RoundedPanel(12);
        controlsPanel.setBackground(new Color(50, 52, 56));
        controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
        controlsPanel.setBorder(new EmptyBorder(15, 20, 15, 20));
        // ensure controlsPanel's children align to left by default
        controlsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Create a container for username section with proper left alignment
        JPanel usernamePanel = new JPanel();
        usernamePanel.setLayout(new BoxLayout(usernamePanel, BoxLayout.Y_AXIS));
        usernamePanel.setOpaque(false);
        usernamePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel userLabel = new JLabel("Enter your username:");
        userLabel.setForeground(Color.LIGHT_GRAY);
        userLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        // critical: force left alignment on the label
        userLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        usernameField = new JTextField("Guest");
        usernameField.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        usernameField.setPreferredSize(new Dimension(300, 38));
        usernameField.setMaximumSize(new Dimension(300, 38));
        usernameField.setBackground(new Color(60, 63, 68));
        usernameField.setForeground(Color.WHITE);
        usernameField.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80)));
        usernameField.setCaretColor(Color.WHITE);
        // critical: force left alignment on the text field
        usernameField.setAlignmentX(Component.LEFT_ALIGNMENT);

        usernamePanel.add(userLabel);
        usernamePanel.add(Box.createVerticalStrut(6));
        usernamePanel.add(usernameField);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttonPanel.setOpaque(false);
        // force left alignment so FlowLayout-left panel sits to the left in BoxLayout
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton joinBtn = styledButton("Join Call", new Color(59, 130, 246));
        joinBtn.setIcon(loadIcon("call.png", 20, 20));
        joinBtn.setHorizontalTextPosition(SwingConstants.RIGHT);
        joinBtn.addActionListener(e -> joinCall());
        joinBtn.setVisible(false);

        JButton leaveBtn = styledButton("Leave Call", new Color(220, 53, 69));
        leaveBtn.addActionListener(e -> leaveServer());
        leaveBtn.setVisible(false);

        buttonPanel.add(joinBtn);
        buttonPanel.add(leaveBtn);

        JPanel mutePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        mutePanel.setOpaque(false);
        // also force left alignment for mute panel
        mutePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

    JButton muteBtn = styledButton("Toggle Mute", new Color(75, 130, 246));
    this.muteButton = muteBtn;
    muteBtn.addActionListener(e -> toggleMute());

        muteLabel = new JLabel("", loadIcon("mic.png", 22, 22), JLabel.LEFT);
        muteLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        muteLabel.setBorder(new EmptyBorder(8, 0, 8, 0));

        mutePanel.add(muteBtn);
        mutePanel.add(muteLabel);

        controlsPanel.add(usernamePanel);
        controlsPanel.add(Box.createVerticalStrut(12));
        controlsPanel.add(buttonPanel);
        controlsPanel.add(Box.createVerticalStrut(15));
        controlsPanel.add(new JSeparator());
        controlsPanel.add(Box.createVerticalStrut(15));
        controlsPanel.add(mutePanel);
        controlsPanel.add(Box.createVerticalGlue());

        main.add(participantsPanel, BorderLayout.CENTER);
        main.add(controlsPanel, BorderLayout.SOUTH);

        this.joinButton = joinBtn;
        this.leaveButton = leaveBtn;
        updateMuteStatus();

        return main;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(37, 39, 43));
        footer.setBorder(new EmptyBorder(6, 20, 6, 20));

        JLabel left = new JLabel(" Connected to server | Audio Streaming Active");
        left.setForeground(Color.GRAY);
        left.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        JLabel right = new JLabel("© 2025 Voice App");
        right.setForeground(Color.GRAY);
        right.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private void connectToServer() {
        userModel.clear();
        userModel.addElement(" Connect");
        
        // Prompt user for server IP address
        String serverIp = javax.swing.JOptionPane.showInputDialog(
            frame,
            "Enter server IP address:",
            "Connect to Server",
            javax.swing.JOptionPane.QUESTION_MESSAGE
        );
        
        if (serverIp == null || serverIp.trim().isEmpty()) {
            userModel.clear();
            userModel.addElement(" Connection cancelled");
            return;
        }
        
        serverIp = serverIp.trim();
        
        // Create client instance and mark connected
        try {
            client = new VoiceChatClient(serverIp, 4444, 5555);
            // Ensure TCP control is connected (client created but TCP connect happens when joining)
            boolean ok = client.connectControl();
            if (!ok) {
                // Server not active or refused connection
                userModel.clear();
                userModel.addElement(" Server Offline");
                connected = false;
                // clean up client so UI actions don't try to use it
                try { client.disconnect(); } catch (Exception ignored) {}
                client = null;
                if (joinButton != null) joinButton.setVisible(false);
                if (leaveButton != null) leaveButton.setVisible(false);
                JOptionPane.showMessageDialog(frame, "Unable to connect: server appears to be offline.");
                return;
            }
            connected = true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Failed to create client: " + e.getMessage());
            connected = false;
            return;
        }
        if (joinButton != null) joinButton.setVisible(true);
        JOptionPane.showMessageDialog(frame, "Connected to server successfully!");

        // Automatically join the call after connecting
        SwingUtilities.invokeLater(() -> joinCall());
    }

    private void joinCall() {
        if (!connected || client == null) {
            JOptionPane.showMessageDialog(frame, "Please connect to a server first!");
            return;
        }

        String user = usernameField.getText().trim();
        if (user.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your username!");
            return;
        }

        if (joined) {
            JOptionPane.showMessageDialog(frame, "Already joined the call.");
            return;
        }

        // Update UI
        userModel.clear();
        userModel.addElement(user + " (You)");
        channelTitleLabel.setText("  Voice Channel: General Chat");

        // Ask the client to join the session and start audio
        // Defensive: check client is not null
        if (client == null) {
            JOptionPane.showMessageDialog(frame, "Client is not connected. Please reconnect.");
            return;
        }

        client.setUsername(user);

        // raw handler that performs the actual UI updates; we'll wrap this in a safe listener
        java.util.function.Consumer<String> rawUiServerHandler = (line) -> {
            if (line == null) return;
            // process PRESENCE messages and OK response
            if (line.startsWith("PRESENCE ADD ")) {
                String[] parts = line.split(" ", 4);
                System.out.println("[UI] - Received: " + line);
                if (parts.length >= 3) {
                    String idStr = parts[2];
                    int id = -1;
                    try { id = Integer.parseInt(idStr); } catch (NumberFormatException ignored) {}
                    String name = parts.length >= 4 ? parts[3] : ("User-" + idStr);
                    final String previousName;
                    if (id != -1) {
                        previousName = idToName.put(id, name);
                    } else {
                        previousName = null;
                    }
                    final String finalName = name;
                    final int finalId = id;
                    SwingUtilities.invokeLater(() -> {
                        if (!joined) {
                            System.out.println("[UI] - Ignored PRESENCE ADD after leaving: " + line);
                            return;
                        }
                        if (finalId == myAssignedId && myAssignedId > 0) {
                            System.out.println("[UI] - Skipping PRESENCE ADD for own ID: " + finalId + " (name: " + finalName + ")");
                            return;
                        }
                        if (previousName != null && !previousName.equals(finalName)) {
                            System.out.println("[UI] - ID " + finalId + " changed name from '" + previousName + "' to '" + finalName + "'");
                            for (int i = userModel.size() - 1; i >= 0; i--) {
                                String v = userModel.get(i);
                                if (v != null && (v.equals(previousName) || v.equals(previousName + " (You)"))) {
                                    userModel.remove(i);
                                }
                            }
                        }
                        boolean exists = false;
                        for (int i = 0; i < userModel.size(); i++) {
                            String v = userModel.get(i);
                            if (v != null && (v.equals(finalName) || v.equals(finalName + " (You)"))) { exists = true; break; }
                        }
                        if (!exists) {
                            userModel.addElement(finalName);
                            System.out.println("[UI] - Added '" + finalName + "' to participants list (ID: " + finalId + ")");
                        } else {
                            System.out.println("[UI] - PRESENCE ADD for '" + finalName + "' (ID: " + finalId + ") already in list");
                        }
                    });
                }
            } else if (line.startsWith("MUTE ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    int id = -1;
                    try { id = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
                    final int finalId = id;
                    SwingUtilities.invokeLater(() -> {
                        if (!joined) return;
                        if (finalId == myAssignedId && myAssignedId > 0) {
                            muted = true;
                            awaitingMuteAck = false;
                            updateMuteStatus();
                            if (muteButton != null) muteButton.setEnabled(true);
                        }
                    });
                }
            } else if (line.startsWith("UNMUTE ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 2) {
                    int id = -1;
                    try { id = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
                    final int finalId = id;
                    SwingUtilities.invokeLater(() -> {
                        if (!joined) return;
                        if (finalId == myAssignedId && myAssignedId > 0) {
                            muted = false;
                            awaitingMuteAck = false;
                            updateMuteStatus();
                            if (muteButton != null) muteButton.setEnabled(true);
                        }
                    });
                }
            } else if (line.startsWith("PRESENCE REMOVE ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    String idStr = parts[2];
                    int id = -1;
                    try { id = Integer.parseInt(idStr); } catch (NumberFormatException ignored) {}
                    final String name = id != -1 ? idToName.remove(id) : null;
                    final int finalId = id;
                    SwingUtilities.invokeLater(() -> {
                        if (!joined) return; // ignore late events after leaving
                        
                        if (name == null && finalId > 0) {
                            System.out.println("[UI] - Received PRESENCE REMOVE for unknown ID: " + finalId);
                            return;
                        }
                        
                        if (name != null) {
                            for (int i = userModel.size() - 1; i >= 0; i--) {
                                String v = userModel.get(i);
                                if (v != null && (v.equals(name) || v.equals(name + " (You)"))) {
                                    userModel.remove(i);
                                    System.out.println("[UI] - Removed '" + v + "' from participants list (ID: " + finalId + ")");
                                }
                            }
                        }
                    });
                }
            } else if (line.startsWith("OK ")) {
                SwingUtilities.invokeLater(() -> {
                    if (!joined) return; // ignore OK if we've already left
                    // When we get OK <id>, map the id to our username and ensure '(You)' display
                    try {
                        String[] p = line.split(" ");
                        if (p.length >= 2) {
                            int myId = Integer.parseInt(p[1]);
                            myAssignedId = myId; // Store our assigned ID
                            String myName = usernameField.getText().trim();
                            idToName.put(myId, myName);
                            // replace any plain name entry with '(You)'
                            for (int i = userModel.size() - 1; i >= 0; i--) {
                                String v = userModel.get(i);
                                if (v != null && v.equals(myName)) {
                                    userModel.remove(i);
                                }
                            }
                            if (!userModel.contains(myName + " (You)")) userModel.addElement(myName + " (You)");
                        }
                    } catch (Exception ignored) {}
                });
            }
        };

        // Create a safe listener that delegates to the raw handler. Capture the raw handler
        // in a final variable to avoid accidental self-recursion.
        final java.util.function.Consumer<String> rawHandler = rawUiServerHandler;
        java.util.function.Consumer<String> safeListener = (line) -> {
            try {
                if (line == null) return;
                rawHandler.accept(line);
            } catch (Throwable t) {
                // Log and swallow to avoid killing the EDT from unexpected races
                System.err.println("[UI] - Exception in server listener: " + t);
            }
        };
        client.addServerMessageListener(safeListener);
        uiServerListener = safeListener;

        client.joinSession();
        joined = true;
        
        // Show Leave button, hide Join button
        if (joinButton != null) joinButton.setVisible(false);
        if (leaveButton != null) {
            leaveButton.setEnabled(true);  // Ensure it's enabled
            leaveButton.setVisible(true);
        }
    }

    private void leaveServer() {
        // Full disconnect: leave call and disconnect from server
        // Disable controls early to prevent double actions
        if (leaveButton != null) leaveButton.setVisible(false);
        if (joinButton != null) joinButton.setVisible(false);
        
        try {
            joined = false;
            
            // Copy reference to avoid races
            VoiceChatClient current = this.client;
            if (current == null) {
                JOptionPane.showMessageDialog(frame, "Client is not connected.");
                return;
            }
            
            // Remove server listener immediately after setting joined=false
            try { 
                if (uiServerListener != null) current.removeServerMessageListener(uiServerListener); 
            } catch (Exception ignored) {}
            uiServerListener = null;
            
            userModel.clear();
            userModel.addElement(" Disconnected");
            connected = false;
            
            idToName.clear();
            myAssignedId = -1;
            
            // Perform full disconnect on background thread
            client = null;
            new Thread(() -> { 
                try { 
                    current.disconnect(); 
                } catch (Exception ignored) {} 
            }, "ui-disconnect-thread").start();

            muted = true;
            updateMuteStatus();

            JOptionPane.showMessageDialog(frame, "Disconnected from server.");
        } catch (RuntimeException re) {
            // show an error message 
            System.err.println("[UI] - Runtime error during disconnect: " + re.getMessage());
            JOptionPane.showMessageDialog(frame, "An error occurred while disconnecting: " + re.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void toggleMute() {
        if (client == null) {
            JOptionPane.showMessageDialog(frame, "Client is not connected. Please reconnect.");
            return;
        }
        if (awaitingMuteAck) return; // prevent double taps
        awaitingMuteAck = true;
        if (muteButton != null) muteButton.setEnabled(false);
        // Do not flip UI state yet; wait for server MUTE/UNMUTE to arrive
        client.toggleMute();
    }

    private void updateMuteStatus() {
        if (muted) {
            muteLabel.setIcon(loadIcon("mute.png", 22, 22));
            muteLabel.setText("Mic: Muted");
            muteLabel.setForeground(Color.RED);
        } else {
            muteLabel.setIcon(loadIcon("mic.png", 22, 22));
            muteLabel.setText("Mic: Live");
            muteLabel.setForeground(new Color(0, 200, 0));
        }
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
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setBackground(color.brighter());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                btn.setBackground(color);
            }
        });
        return btn;
    }

    private static ImageIcon loadIcon(String name, int w, int h) {
        try {
            Image img = new ImageIcon(AudioStreamingUI.class.getResource("/icons/" + name)).getImage();
            return new ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            return null;
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

            if ("🎮 My Server".equals(value)) {
                label.setIcon(loadIcon("server.png", 18, 18));
                label.setText(" My Server");
                label.setHorizontalTextPosition(SwingConstants.RIGHT);
            }
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

            if (" Connect".equals(value)) {
                label.setForeground(new Color(0, 200, 0));
                label.setIcon(loadIcon("status_check.png", 18, 18));
            } else if (" Disconnected".equals(value)) {
                label.setForeground(Color.RED);
                label.setIcon(loadIcon("status_cross.png", 18, 18));
            }
            return label;
        }
    }

    private static class RoundedPanel extends JPanel {
        private final int radius;
        public RoundedPanel(int radius) {
            this.radius = radius;
            setOpaque(false);
        }

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
}
