package com.example;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class AudioStreamingUI {

    private JLabel channelTitleLabel;
    private JFrame frame;
    private JTextField usernameField;
    private JLabel muteLabel;
    private DefaultListModel<String> userModel;
    private JList<String> userList;
    private boolean muted = true;
    private boolean connected = false;

    public void showUI() {
        frame = new JFrame("Discord-like Voice Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(buildRoot());
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

        JLabel userLabel = new JLabel("Enter your username:");
        userLabel.setForeground(Color.LIGHT_GRAY);
        userLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        usernameField = new JTextField("Guest");
        usernameField.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        usernameField.setBackground(new Color(60, 63, 68));
        usernameField.setForeground(Color.WHITE);
        usernameField.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80)));
        usernameField.setCaretColor(Color.WHITE);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttonPanel.setOpaque(false);

        JButton joinBtn = styledButton("Join Call", new Color(59, 130, 246));
        joinBtn.setIcon(loadIcon("call.png", 20, 20));
        joinBtn.setHorizontalTextPosition(SwingConstants.RIGHT);
        joinBtn.addActionListener(e -> joinCall());

        JButton leaveBtn = styledButton("Leave Server", new Color(220, 53, 69));
        leaveBtn.addActionListener(e -> leaveServer());
        leaveBtn.setEnabled(false);

        buttonPanel.add(joinBtn);
        buttonPanel.add(leaveBtn);

        JPanel mutePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        mutePanel.setOpaque(false);

        JButton muteBtn = styledButton("Toggle Mute", new Color(75, 130, 246));
        muteBtn.addActionListener(e -> toggleMute());

        muteLabel = new JLabel("", loadIcon("mic.png", 22, 22), JLabel.LEFT);
        muteLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        muteLabel.setBorder(new EmptyBorder(8, 0, 8, 0));

        mutePanel.add(muteBtn);
        mutePanel.add(muteLabel);

        controlsPanel.add(userLabel);
        controlsPanel.add(Box.createVerticalStrut(6));
        controlsPanel.add(usernameField);
        controlsPanel.add(Box.createVerticalStrut(12));
        controlsPanel.add(buttonPanel);
        controlsPanel.add(Box.createVerticalStrut(15));
        controlsPanel.add(new JSeparator());
        controlsPanel.add(Box.createVerticalStrut(15));
        controlsPanel.add(mutePanel);
        controlsPanel.add(Box.createVerticalGlue());

        main.add(participantsPanel, BorderLayout.CENTER);
        main.add(controlsPanel, BorderLayout.SOUTH);

        this.leaveButton = leaveBtn;
        updateMuteStatus();

        return main;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(37, 39, 43));
        footer.setBorder(new EmptyBorder(6, 20, 6, 20));

        JLabel left = new JLabel(" Connected to server | 3 users online");
        left.setForeground(Color.GRAY);
        left.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        JLabel right = new JLabel("© 2025 Discord-like Voice App");
        right.setForeground(Color.GRAY);
        right.setFont(new Font("Segoe UI", Font.PLAIN, 12));

        footer.add(left, BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private JButton leaveButton;

    private void connectToServer() {
        userModel.clear();
        userModel.addElement(" Connect");
        connected = true;
        if (leaveButton != null) {
            leaveButton.setEnabled(true);
        }
        JOptionPane.showMessageDialog(frame, "Connected to server successfully!");
    }

    private void joinCall() {
        if (!connected) {
            JOptionPane.showMessageDialog(frame, "Please connect to a server first!");
            return;
        }

        String user = usernameField.getText().trim();
        if (user.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please enter your username!");
            return;
        }

        userModel.clear();
        userModel.addElement(user + " (You)");
        channelTitleLabel.setText("  Voice Channel: General Chat");
    }

    private void leaveServer() {
        userModel.clear();
        userModel.addElement(" Disconnected");
        connected = false;
        if (leaveButton != null) {
            leaveButton.setEnabled(false);
        }

        muted = true;
        updateMuteStatus();

        JOptionPane.showMessageDialog(frame, "Disconnected from server.");
    }

    private void toggleMute() {
        muted = !muted;
        updateMuteStatus();
    }

    private void updateMuteStatus() {
        if (muted) {
            muteLabel.setIcon(loadIcon("mute.png", 22, 22));
            muteLabel.setText("Microphone: OFF");
            muteLabel.setForeground(Color.RED);
        } else {
            muteLabel.setIcon(loadIcon("mic.png", 22, 22));
            muteLabel.setText("Microphone: ON");
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
        // Vì là static, nên dùng AudioStreamingUI.class thay vì getClass()
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
            if (isSelected) {
                label.setBackground(new Color(60, 63, 68));
            } else {
                label.setBackground(new Color(37, 39, 43));
            }

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
            if (isSelected) {
                label.setBackground(new Color(60, 63, 68));
            } else {
                label.setBackground(new Color(50, 52, 56));
            }

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
}