package org.jatari.player;

import org.jatari.psg.PsgCapture;
import org.jatari.psg.PsgCaptureParser;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Java Swing application for playing Hatari PSG register-capture files
 * ({@code *.csv.zip}) at 44.1 kHz.
 *
 * <p>Launch via {@link #main(String[])} or from {@code org.jatari.main.StartPsg}.
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Click a file in the left-hand list to load and play it, or click
 *       <em>Open…</em> to navigate to any {@code *.csv.zip} file.</li>
 *   <li>Click <em>Play</em> to start playback; the label changes to
 *       <em>Stop</em> while playing.</li>
 *   <li>Select a cutoff in the <em>Low Pass</em> / <em>High Pass</em>
 *       combo-boxes to enable the IIR filters on the fly.</li>
 *   <li>Click <em>Export WAV…</em> to save the capture as a WAV file.</li>
 * </ol>
 */
public class PsgPlayerApp {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String APP_TITLE  = "PSG Capture Player";
    private static final Path   DEFAULT_DIR = Paths.get("data/psg_capture");

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final PsgPlayer player = new PsgPlayer();

    private Path       selectedPath;
    private PsgCapture selectedCapture;
    private Path       currentDir;

    // -----------------------------------------------------------------------
    // Swing components
    // -----------------------------------------------------------------------

    private JFrame                 frame;
    private JLabel                 lblFile;
    private JLabel                 lblDuration;
    private JLabel                 lblTime;
    private JProgressBar           progressBar;
    private JButton                btnOpen;
    private JButton                btnPlay;
    private JButton                btnExport;
    private JLabel                 lblStatus;
    private JComboBox<YmPlayer.LpfOption> cmbLpf;
    private JComboBox<YmPlayer.HpfOption> cmbHpf;
    private JList<Path>            fileList;
    private DefaultListModel<Path> fileListModel;
    private Timer                  uiTimer;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PsgPlayerApp().buildAndShow());
    }

    // -----------------------------------------------------------------------
    // UI construction
    // -----------------------------------------------------------------------

    private void buildAndShow() {
        frame = new JFrame(APP_TITLE);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(true);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        root.add(buildFileListPanel(), BorderLayout.WEST);
        root.add(buildMainPanel(),     BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(580, 280));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        uiTimer = new Timer(100, e -> updateProgress());
        uiTimer.start();

        player.setListener(new PsgPlayer.Listener() {
            @Override public void onProgress(double pos, double dur) { /* polling timer handles it */ }
            @Override public void onStopped() {
                SwingUtilities.invokeLater(PsgPlayerApp.this::onPlaybackStopped);
            }
        });

        currentDir = Files.isDirectory(DEFAULT_DIR) ? DEFAULT_DIR : Paths.get(".");
        refreshFileList(currentDir);
    }

    private JPanel buildFileListPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Captures"));
        panel.setPreferredSize(new Dimension(200, 0));

        fileListModel = new DefaultListModel<>();
        fileList      = new JList<>(fileListModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Path p) setText(p.getFileName().toString());
                return this;
            }
        });
        fileList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Path sel = fileList.getSelectedValue();
            if (sel != null) loadAndPlay(sel);
        });

        panel.add(new JScrollPane(fileList), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildMainPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(buildInfoPanel(),     BorderLayout.NORTH);
        panel.add(buildProgressPanel(), BorderLayout.CENTER);
        panel.add(buildControlPanel(),  BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildInfoPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Capture Info"));

        lblFile     = makeInfoLabel("File:     —");
        lblDuration = makeInfoLabel("Duration: —");

        panel.add(lblFile);
        panel.add(lblDuration);
        return panel;
    }

    private JPanel buildProgressPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));

        progressBar = new JProgressBar(0, 1000);
        progressBar.setStringPainted(false);
        progressBar.setPreferredSize(new Dimension(0, 16));

        lblTime = new JLabel("0:00 / 0:00", SwingConstants.CENTER);

        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(lblTime,     BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildControlPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));

        // ---- Filter row -------------------------------------------------
        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filterRow.add(new JLabel("Low Pass:"));
        cmbLpf = new JComboBox<>(YmPlayer.LpfOption.values());
        cmbLpf.setSelectedItem(YmPlayer.LpfOption.OFF);
        cmbLpf.addActionListener(e ->
                player.setLpfOption((YmPlayer.LpfOption) cmbLpf.getSelectedItem()));
        filterRow.add(cmbLpf);

        filterRow.add(new JLabel("  High Pass:"));
        cmbHpf = new JComboBox<>(YmPlayer.HpfOption.values());
        cmbHpf.setSelectedItem(YmPlayer.HpfOption.OFF);
        cmbHpf.addActionListener(e ->
                player.setHpfOption((YmPlayer.HpfOption) cmbHpf.getSelectedItem()));
        filterRow.add(cmbHpf);

        // ---- Buttons row ------------------------------------------------
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));

        btnOpen   = new JButton("Open…");
        btnPlay   = new JButton("Play");
        btnPlay.setEnabled(false);
        btnPlay.setPreferredSize(new Dimension(80, btnPlay.getPreferredSize().height));
        btnExport = new JButton("Export WAV…");
        btnExport.setEnabled(false);

        buttons.add(btnOpen);
        buttons.add(btnPlay);
        buttons.add(btnExport);

        lblStatus = new JLabel(" ");
        lblStatus.setFont(lblStatus.getFont().deriveFont(Font.PLAIN, 11f));
        lblStatus.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));

        panel.add(filterRow, BorderLayout.NORTH);
        panel.add(buttons,   BorderLayout.CENTER);
        panel.add(lblStatus, BorderLayout.SOUTH);

        btnOpen.addActionListener(e -> openFile());
        btnPlay.addActionListener(e -> togglePlayback());
        btnExport.addActionListener(e -> exportWav());

        return panel;
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select a PSG capture file");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "PSG capture files (*.csv.zip)", "zip"));
        chooser.setCurrentDirectory(currentDir.toFile());

        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        Path path      = chooser.getSelectedFile().toPath();
        Path parentDir = path.getParent();
        if (parentDir != null && !parentDir.equals(currentDir)) {
            currentDir = parentDir;
            refreshFileList(currentDir);
        }
        loadAndPlay(path);
    }

    private void loadAndPlay(Path path) {
        stopPlayback();
        lblStatus.setText("Loading: " + path.getFileName() + "…");
        try {
            selectedCapture = PsgCaptureParser.parse(path);
            selectedPath    = path;
            populateCaptureInfo();
            btnPlay.setEnabled(true);
            btnExport.setEnabled(true);
            lblStatus.setText("Loaded: " + path.getFileName());
            startPlayback();
        } catch (IOException ex) {
            selectedCapture = null;
            selectedPath    = null;
            btnPlay.setEnabled(false);
            btnExport.setEnabled(false);
            JOptionPane.showMessageDialog(frame,
                    "Could not load capture:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            lblStatus.setText("Error loading file.");
        }
    }

    private void togglePlayback() {
        if (player.isPlaying()) stopPlayback();
        else startPlayback();
    }

    private void startPlayback() {
        if (selectedPath == null) return;
        try {
            player.play(selectedPath);
            btnPlay.setText("Stop");
            lblStatus.setText("Playing: " + selectedPath.getFileName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame,
                    "Playback error:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopPlayback() {
        player.stop();
        btnPlay.setText("Play");
        lblStatus.setText(selectedPath != null
                ? "Stopped: " + selectedPath.getFileName() : " ");
        progressBar.setValue(0);
        updateTimeLabel(0, durationSeconds());
    }

    private void onPlaybackStopped() {
        btnPlay.setText("Play");
        lblStatus.setText(selectedPath != null
                ? "Finished: " + selectedPath.getFileName() : " ");
    }

    private void exportWav() {
        if (selectedCapture == null || selectedPath == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save WAV file");
        chooser.setFileFilter(new FileNameExtensionFilter("WAV audio files (*.wav)", "wav"));
        String suggested = selectedPath.getFileName().toString()
                .replaceAll("(?i)\\.csv\\.zip$", "") + ".wav";
        chooser.setSelectedFile(new java.io.File(currentDir.toFile(), suggested));

        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        Path wavPath = chooser.getSelectedFile().toPath();
        if (!wavPath.getFileName().toString().toLowerCase().endsWith(".wav")) {
            wavPath = wavPath.resolveSibling(wavPath.getFileName() + ".wav");
        }

        final Path       finalWavPath = wavPath;
        final PsgCapture cap          = selectedCapture;

        btnExport.setEnabled(false);
        lblStatus.setText("Exporting: " + finalWavPath.getFileName() + "…");

        new Thread(() -> {
            try {
                player.exportWav(cap, finalWavPath);
                final String done = "Exported: " + finalWavPath.getFileName();
                SwingUtilities.invokeLater(() -> {
                    lblStatus.setText(done);
                    btnExport.setEnabled(true);
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(frame,
                            "Export failed:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    lblStatus.setText("Export failed.");
                    btnExport.setEnabled(true);
                });
            }
        }, "psg-wav-export").start();
    }

    // -----------------------------------------------------------------------
    // File list helpers
    // -----------------------------------------------------------------------

    private void refreshFileList(Path dir) {
        fileListModel.clear();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv.zip"))
                  .sorted()
                  .forEach(fileListModel::addElement);
        } catch (IOException ex) {
            lblStatus.setText("Cannot list directory: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Progress / info helpers
    // -----------------------------------------------------------------------

    private void updateProgress() {
        if (!player.isPlaying() || selectedCapture == null) return;
        double dur = durationSeconds();
        double pos = player.getPositionSeconds();
        if (dur > 0) progressBar.setValue((int) Math.min(1000, pos / dur * 1000));
        updateTimeLabel(pos, dur);
    }

    private void updateTimeLabel(double pos, double dur) {
        lblTime.setText(formatTime(pos) + " / " + formatTime(dur));
    }

    private double durationSeconds() {
        return selectedCapture == null ? 0.0 : selectedCapture.durationSeconds();
    }

    private void populateCaptureInfo() {
        if (selectedCapture == null) return;
        lblFile.setText("File:     " + selectedCapture.fileName());
        lblDuration.setText("Duration: " + formatTime(selectedCapture.durationSeconds()));
        updateTimeLabel(0, selectedCapture.durationSeconds());
        progressBar.setValue(0);
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static JLabel makeInfoLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.PLAIN));
        return l;
    }

    private static String formatTime(double seconds) {
        int s = (int) seconds;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
