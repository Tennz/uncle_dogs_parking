package gui;

import service.ParkingLot;
import storage.ParkingStore;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Desktop administration window. Database work runs off Swing's event thread. */
public final class AdminWindow extends JFrame {
    private static final long serialVersionUID = 1L;
    private final transient ParkingLot lot;
    private final String baseUrl;
    private final int port;
    private final JPanel slotGrid = new JPanel(new GridLayout(0, 4, 10, 10));
    private final DefaultTableModel vehicleModel = new DefaultTableModel(
            new Object[]{"Slot", "Plate", "Vehicle", "Entry time", "Duration"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JLabel status = new JLabel("Starting dashboard…");
    private boolean refreshRunning;
    private boolean hasLoadedDashboard;
    private boolean skeletonPhase;
    private Timer skeletonTimer;

    private record DashboardData(List<Map<String,Object>> slots, List<ParkingStore.SessionData> active) {}

    public AdminWindow(ParkingLot lot, String baseUrl, int port) {
        super("Uncle Dogs Parking · Admin · v1.8.0");
        this.lot = lot;
        this.baseUrl = baseUrl;
        this.port = port;
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(1920, 1080);
        setMinimumSize(new Dimension(820, 620));
        setLocationRelativeTo(null);
        getContentPane().setBackground(new Color(244, 244, 246));
        setLayout(new BorderLayout(12, 12));
        add(buildHeader(), BorderLayout.NORTH);

        JLabel slotsTitle = new JLabel("Parking status");
        slotsTitle.setFont(slotsTitle.getFont().deriveFont(Font.BOLD, 19f));
        JPanel slotPanel = new JPanel(new BorderLayout(8, 8));
        slotPanel.setOpaque(false);
        slotPanel.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
        slotPanel.add(slotsTitle, BorderLayout.NORTH);
        slotGrid.setBackground(Color.WHITE);
        slotGrid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        slotPanel.add(new JScrollPane(slotGrid), BorderLayout.CENTER);

        JPanel vehiclePanel = new JPanel(new BorderLayout(8, 8));
        vehiclePanel.setOpaque(false);
        JLabel vehicleTitle = new JLabel("Vehicles parked now");
        vehicleTitle.setFont(vehicleTitle.getFont().deriveFont(Font.BOLD, 17f));
        vehiclePanel.add(vehicleTitle, BorderLayout.NORTH);
        JTable vehicleTable = new JTable(vehicleModel);
        vehicleTable.setRowHeight(26);
        vehiclePanel.add(new JScrollPane(vehicleTable), BorderLayout.CENTER);
        vehiclePanel.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
        vehiclePanel.setPreferredSize(new Dimension(950, 215));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, slotPanel, vehiclePanel);
        split.setResizeWeight(.67);
        split.setBorder(null);
        split.setOpaque(false);
        add(split, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(0, 14, 10, 14));
        status.setForeground(new Color(77, 77, 85));
        add(status, BorderLayout.SOUTH);
        refresh();
        Timer timer = new Timer(5000, e -> refresh());
        timer.start();
    }

    private JPanel buildHeader() {
        JPanel outer = new JPanel(new BorderLayout(12, 10));
        outer.setBackground(new Color(20, 20, 22));
        outer.setBorder(BorderFactory.createEmptyBorder(15, 18, 13, 18));
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 13, 0));
        brand.setOpaque(false);
        JLabel logoLabel = new JLabel();
        try {
            BufferedImage original = ImageIO.read(new File("assets/logo.jpg"));
            if (original != null) logoLabel.setIcon(new ImageIcon(original.getScaledInstance(68, 68, Image.SCALE_SMOOTH)));
            else logoLabel.setText("🐶");
        } catch (Exception ignored) { logoLabel.setText("🐶"); }
        brand.add(logoLabel);
        JPanel title = new JPanel(new GridLayout(0, 1, 0, 5));
        title.setOpaque(false);
        JLabel name = new JLabel("Uncle Dogs Parking");
        name.setForeground(Color.WHITE);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 23f));
        title.add(name);
        JLabel subtitle = new JLabel("DEPARTMENTS PARKING SLOT MANAGEMENT SYSTEM · VERSION 1.8.0");
        subtitle.setForeground(new Color(205, 205, 210));
        title.add(subtitle);
        JLabel url = new JLabel("Server: " + baseUrl + " · Port " + port);
        url.setForeground(new Color(170, 170, 180));
        title.add(url);
        brand.add(title);
        outer.add(brand, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        actions.setOpaque(false);
        JComboBox<String> type = new JComboBox<>(new String[]{"ANY", "CAR", "MOTORCYCLE"});
        JButton add = new JButton("+ Add Slot");
        add.setToolTipText("Create the next parking slot");
        add.addActionListener(e -> runBackground("Adding slot…", () -> {
            ParkingStore.SlotData slot = lot.addSlot((String) type.getSelectedItem());
            return "Slot " + slot.number() + " created. Its QR codes are available in the website Admin section.";
        }, true));
        JButton feedback = new JButton("Feedback Inbox");
        feedback.addActionListener(e -> openFeedbackInbox());
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refresh());
        JButton open = new JButton("Open Website");
        open.addActionListener(e -> openWebsite());
        JButton copy = new JButton("Copy URL");
        copy.addActionListener(e -> {
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(baseUrl), null); status.setText("Website URL copied: " + baseUrl); }
            catch (Exception ex) { showError(ex); }
        });
        actions.add(type); actions.add(add); actions.add(feedback); actions.add(refreshButton); actions.add(open); actions.add(copy);
        outer.add(actions, BorderLayout.SOUTH);
        return outer;
    }

    private void openWebsite() {
        try {
            if (!Desktop.isDesktopSupported()) throw new IllegalStateException("This desktop does not support opening a browser automatically. Open " + baseUrl + " manually.");
            Desktop.getDesktop().browse(java.net.URI.create(baseUrl));
        } catch (Exception ex) { showError(ex); }
    }

    private void refresh() {
        if (refreshRunning) return;
        refreshRunning = true;
        status.setText("Refreshing parking data…");
        if (!hasLoadedDashboard && slotGrid.getComponentCount() == 0) showSkeletonSlots();
        new SwingWorker<DashboardData, Void>() {
            @Override protected DashboardData doInBackground() throws Exception {
                return new DashboardData(lot.slotViews(), lot.getStorage().listActiveSessions());
            }
            @Override protected void done() {
                refreshRunning = false;
                try {
                    DashboardData data = get();
                    stopSkeletonAnimation();
                    renderSlots(data.slots());
                    hasLoadedDashboard = true;
                    vehicleModel.setRowCount(0);
                    DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault());
                    for (ParkingStore.SessionData row : data.active()) {
                        vehicleModel.addRow(new Object[]{row.slotNumber(), row.plate(), row.vehicleType(), format.format(row.entryTime()), duration(row.durationSeconds(Instant.now()))});
                    }
                    status.setText("Online · " + baseUrl + " · Updated " + java.time.LocalTime.now().withNano(0));
                } catch (Exception ex) {
                    stopSkeletonAnimation();
                    slotGrid.removeAll();
                    JLabel error = new JLabel("Could not load parking data. Use Refresh to try again.");
                    error.setForeground(new Color(150, 45, 50));
                    slotGrid.add(error);
                    slotGrid.revalidate(); slotGrid.repaint();
                    status.setText("Refresh failed · " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private void showSkeletonSlots() {
        slotGrid.removeAll();
        for (int i=0;i<8;i++) {
            JPanel card = new JPanel(new GridLayout(0,1,8,8));
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(224,224,228)), BorderFactory.createEmptyBorder(12,12,12,12)));
            card.setBackground(new Color(247,247,249));
            JLabel title = new JLabel("Loading slot…"); title.setForeground(new Color(155,155,162));
            JLabel bar1 = new JLabel("                         "); bar1.setOpaque(true); bar1.setBackground(new Color(231,231,235));
            JLabel bar2 = new JLabel("                    "); bar2.setOpaque(true); bar2.setBackground(new Color(236,236,239));
            card.add(title); card.add(bar1); card.add(bar2); slotGrid.add(card);
        }
        slotGrid.revalidate(); slotGrid.repaint();
        skeletonPhase = false;
        if (skeletonTimer != null) skeletonTimer.stop();
        skeletonTimer = new Timer(480, e -> {
            skeletonPhase = !skeletonPhase;
            Color a = skeletonPhase ? new Color(229,229,233) : new Color(242,242,245);
            Color b = skeletonPhase ? new Color(239,239,242) : new Color(231,231,235);
            for (java.awt.Component component : slotGrid.getComponents()) {
                if (component instanceof JPanel card) {
                    card.setBackground(a);
                    for (java.awt.Component child : card.getComponents()) if (child instanceof JLabel label && label.isOpaque()) label.setBackground(b);
                }
            }
        });
        skeletonTimer.start();
    }

    private void stopSkeletonAnimation() { if (skeletonTimer != null) { skeletonTimer.stop(); skeletonTimer = null; } }

    private void renderSlots(List<Map<String,Object>> slots) {
        slotGrid.removeAll();
        for (Map<String,Object> s : slots) {
            String state = String.valueOf(s.get("status"));
            int number = Integer.parseInt(String.valueOf(s.get("number")));
            JPanel card = new JPanel(new BorderLayout(4, 6));
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color(state), 2), BorderFactory.createEmptyBorder(10, 11, 10, 11)));
            card.setBackground(new Color(250, 250, 250));
            JLabel title = new JLabel("Slot " + number);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
            card.add(title, BorderLayout.NORTH);
            JPanel middle = new JPanel(new GridLayout(0, 1, 3, 3));
            middle.setOpaque(false);
            JLabel st = new JLabel(stateLabel(state));
            st.setForeground(color(state));
            st.setFont(st.getFont().deriveFont(Font.BOLD, 12f));
            middle.add(st);
            middle.add(new JLabel(String.valueOf(s.get("compatibility"))));
            if ("OCCUPIED".equals(state) && s.containsKey("plate")) middle.add(new JLabel("Plate: " + s.get("plate")));
            card.add(middle, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            actions.setOpaque(false);
            if ("OCCUPIED".equals(state)) {
                JButton checkout = new JButton("Manual check-out");
                checkout.addActionListener(e -> {
                    int ok = JOptionPane.showConfirmDialog(this, "Manually check out Slot " + number + "? Verify the vehicle first.", "Confirm checkout", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (ok == JOptionPane.YES_OPTION) runBackground("Checking out slot…", () -> { lot.adminCheckout(number); return "Slot " + number + " checked out."; }, true);
                });
                actions.add(checkout);
            } else {
                boolean enabled = Boolean.TRUE.equals(s.get("enabled"));
                JButton toggle = new JButton(enabled ? "Disable" : "Enable");
                toggle.addActionListener(e -> runBackground("Updating slot…", () -> { lot.setSlotEnabled(number, !enabled); return "Slot " + number + " updated."; }, true));
                actions.add(toggle);
                JButton delete = new JButton("Delete unused");
                delete.setToolTipText("Deletion is allowed only if no booking or visit history exists.");
                delete.addActionListener(e -> {
                    int ok = JOptionPane.showConfirmDialog(this, "Delete Slot " + number + " only if it has never been used? Historical records are protected.", "Delete slot", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (ok == JOptionPane.YES_OPTION) runBackground("Deleting slot…", () -> { lot.deleteSlot(number); return "Slot " + number + " deleted."; }, true);
                });
                actions.add(delete);
            }
            card.add(actions, BorderLayout.SOUTH);
            slotGrid.add(card);
        }
        slotGrid.revalidate();
        slotGrid.repaint();
    }

    private void runBackground(String loadingMessage, BackgroundAction action, boolean refreshAfter) {
        status.setText(loadingMessage);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return action.run(); }
            @Override protected void done() {
                try { status.setText(get()); if (refreshAfter) refresh(); }
                catch (Exception ex) { showError(ex); }
            }
        }.execute();
    }
    @FunctionalInterface private interface BackgroundAction { String run() throws Exception; }

    private void openFeedbackInbox() {
        JDialog dialog = new JDialog(this, "Feedback Inbox · Version 1.8.0", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(new Color(246, 246, 248));
        dialog.setSize(1000, 620);
        dialog.setMinimumSize(new Dimension(760, 460));
        dialog.setLocationRelativeTo(this);
        JLabel heading = new JLabel("User feedback · review and resolve submissions");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false); top.setBorder(BorderFactory.createEmptyBorder(15, 15, 2, 15)); top.add(heading, BorderLayout.NORTH);
        dialog.add(top, BorderLayout.NORTH);
        DefaultTableModel model = new DefaultTableModel(new Object[]{"ID", "Date", "User", "Email", "Category", "Rating", "Message", "Status"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model); table.setRowHeight(30); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(55); table.getColumnModel().getColumn(1).setPreferredWidth(135);
        table.getColumnModel().getColumn(2).setPreferredWidth(125); table.getColumnModel().getColumn(3).setPreferredWidth(190);
        table.getColumnModel().getColumn(4).setPreferredWidth(120); table.getColumnModel().getColumn(5).setPreferredWidth(55);
        table.getColumnModel().getColumn(6).setPreferredWidth(360); table.getColumnModel().getColumn(7).setPreferredWidth(100);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel editor = new JPanel(new BorderLayout(8, 8)); editor.setBorder(BorderFactory.createEmptyBorder(0, 15, 10, 15)); editor.setOpaque(false);
        JPanel editorTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); editorTop.setOpaque(false);
        JComboBox<String> statusBox = new JComboBox<>(new String[]{"NEW", "REVIEWING", "RESOLVED", "DISMISSED"});
        editorTop.add(new JLabel("Selected feedback status:")); editorTop.add(statusBox);
        editor.add(editorTop, BorderLayout.NORTH);
        JTextArea note = new JTextArea(3, 60); note.setLineWrap(true); note.setWrapStyleWord(true); note.setToolTipText("Private internal note visible only to administrators");
        JPanel notePanel = new JPanel(new BorderLayout(4, 4)); notePanel.setOpaque(false); notePanel.add(new JLabel("Private admin note"), BorderLayout.NORTH); notePanel.add(new JScrollPane(note), BorderLayout.CENTER);
        editor.add(notePanel, BorderLayout.CENTER);
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT)); foot.setOpaque(false);
        JLabel feedbackStatus = new JLabel("Choose a feedback row to update it.");
        JButton reload = new JButton("Refresh inbox"); JButton save = new JButton("Save update"); JButton close = new JButton("Close");
        foot.add(feedbackStatus); foot.add(reload); foot.add(save); foot.add(close); editor.add(foot, BorderLayout.SOUTH);
        dialog.add(editor, BorderLayout.SOUTH);
        List<ParkingStore.FeedbackData> rows = new ArrayList<>();
        Runnable load = () -> {
            reload.setEnabled(false); feedbackStatus.setText("Loading feedback…");
            new SwingWorker<List<ParkingStore.FeedbackData>, Void>() {
                @Override protected List<ParkingStore.FeedbackData> doInBackground() throws Exception { return lot.getStorage().listFeedback(200); }
                @Override protected void done() {
                    reload.setEnabled(true);
                    try {
                        rows.clear(); rows.addAll(get()); model.setRowCount(0);
                        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault());
                        for (ParkingStore.FeedbackData item : rows) model.addRow(new Object[]{item.id(), f.format(item.createdAt()), item.displayName(), item.email(), item.category(), item.rating()+"/5", item.message(), item.status()});
                        feedbackStatus.setText(rows.isEmpty() ? "No feedback has been submitted yet." : rows.size()+" feedback item(s)");
                    } catch (Exception ex) { feedbackStatus.setText("Could not load feedback: " + rootMessage(ex)); }
                }
            }.execute();
        };
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow(); if (row < 0) return;
            long id = Long.parseLong(String.valueOf(model.getValueAt(row, 0)));
            rows.stream().filter(f -> f.id() == id).findFirst().ifPresent(f -> { statusBox.setSelectedItem(f.status()); note.setText(f.adminNote()); feedbackStatus.setText("Editing feedback #"+f.id()); });
        });
        reload.addActionListener(e -> load.run());
        save.addActionListener(e -> {
            int row = table.getSelectedRow(); if (row < 0) { feedbackStatus.setText("Select a feedback row first."); return; }
            long id = Long.parseLong(String.valueOf(model.getValueAt(row, 0)));
            save.setEnabled(false); feedbackStatus.setText("Saving update…");
            new SwingWorker<ParkingStore.FeedbackData, Void>() {
                @Override protected ParkingStore.FeedbackData doInBackground() throws Exception { return lot.getStorage().updateFeedback(id, String.valueOf(statusBox.getSelectedItem()), note.getText(), Instant.now()); }
                @Override protected void done() { save.setEnabled(true); try { get(); feedbackStatus.setText("Feedback #"+id+" updated."); load.run(); } catch (Exception ex) { feedbackStatus.setText("Update failed: " + rootMessage(ex)); } }
            }.execute();
        });
        close.addActionListener(e -> dialog.dispose());
        load.run();
        dialog.setVisible(true);
    }

    private static String stateLabel(String state) { return switch (state) { case "AVAILABLE" -> "Available"; case "OCCUPIED" -> "Occupied"; case "RESERVED" -> "Reserved"; default -> "Unavailable"; }; }
    private static Color color(String state) { return switch (state) { case "AVAILABLE" -> new Color(23, 133, 73); case "OCCUPIED" -> new Color(192, 43, 51); case "RESERVED" -> new Color(166, 112, 0); default -> new Color(100, 100, 105); }; }
    private static String duration(long seconds) { long h = seconds / 3600, m = (seconds % 3600) / 60; return h > 0 ? h + "h " + m + "m" : m + "m"; }
    private void showError(Exception e) { JOptionPane.showMessageDialog(this, rootMessage(e), "Parking system", JOptionPane.ERROR_MESSAGE); }
    private static String rootMessage(Exception e) { Throwable t=e; while(t.getCause()!=null)t=t.getCause(); return t.getMessage()==null?e.getClass().getSimpleName():t.getMessage(); }
}
