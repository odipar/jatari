package org.jatari.player;

import org.jatari.player.YmPlayer.LpfOption;
import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Java Swing application for playing YM2149 music files at 44.1 kHz.
 *
 * <p>Launch via {@link #main(String[])} or by running the {@code exec:java}
 * Maven goal (configured to call {@code org.jatari.main.Start}, which
 * delegates here).
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Click a file in the left-hand file list to load and play it directly,
 *       or click <em>Open…</em> to navigate to any {@code .ym} file.</li>
 *   <li>Click <em>Play</em> to start playback; the button label changes to
 *       <em>Stop</em>.</li>
 *   <li>Click <em>Stop</em> (or <em>Play</em> again) to halt playback.</li>
 *   <li>Select a cutoff in the <em>Filter</em> combo-box to enable the IIR
 *       low-pass filter on the next playback.</li>
 *   <li>Click <em>Export WAV…</em> to save the current song as a WAV file.</li>
 * </ol>
 */
public class YmPlayerApp {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String APP_TITLE = "YM2149 44.1 kHz Player";

    /** Default starting directory for the file chooser and the file list. */
    private static final Path DEFAULT_DIR = Paths.get("data/ym_format");

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final YmPlayer player = new YmPlayer();

    private Path    selectedPath;
    private YmFile  selectedYm;
    /** The directory currently shown in the file list. */
    private Path    currentDir;

    // -----------------------------------------------------------------------
    // Swing components
    // -----------------------------------------------------------------------

    private JFrame     frame;
    private JLabel     lblTitle;
    private JLabel     lblAuthor;
    private JLabel     lblComment;
    private JLabel     lblFile;
    private JLabel     lblTime;
    private JProgressBar progressBar;
    private JButton    btnOpen;
    private JButton    btnPlay;
    private JButton    btnExport;
    private JLabel     lblStatus;
    private JComboBox<LpfOption>     cmbFilter;
    private JList<Path>              fileList;
    private DefaultListModel<Path>   fileListModel;

    // Timer that refreshes the progress display ~10 times per second
    private Timer      uiTimer;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new YmPlayerApp().buildAndShow());
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
        frame.setMinimumSize(new Dimension(620, 320));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // Polling timer – updates the progress bar from the player state
        uiTimer = new Timer(100, e -> updateProgress());
        uiTimer.start();

        // Player callbacks (called from player thread → dispatch to EDT)
        player.setListener(new YmPlayer.Listener() {
            @Override
            public void onProgress(double positionSeconds, double durationSeconds) {
                // Progress is updated by the polling uiTimer; nothing extra needed.
            }
            @Override
            public void onStopped() {
                SwingUtilities.invokeLater(() -> onPlaybackStopped());
            }
        });

        // Populate file list with the default directory
        currentDir = Files.isDirectory(DEFAULT_DIR) ? DEFAULT_DIR : Paths.get(".");
        refreshFileList(currentDir);
    }

    /** Left-hand panel: scrollable list of .ym files in the current directory. */
    private JPanel buildFileListPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Files"));
        panel.setPreferredSize(new Dimension(200, 0));

        fileListModel = new DefaultListModel<>();
        fileList      = new JList<>(fileListModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Path p) setText(p.getFileName().toString());
                return this;
            }
        });

        // Single-click: load and play the selected file
        fileList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            Path selected = fileList.getSelectedValue();
            if (selected != null) loadAndPlay(selected);
        });

        JScrollPane scroll = new JScrollPane(fileList);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** Right-hand main panel: song info + progress + controls. */
    private JPanel buildMainPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(buildInfoPanel(),     BorderLayout.NORTH);
        panel.add(buildProgressPanel(), BorderLayout.CENTER);
        panel.add(buildControlPanel(),  BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildInfoPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 1, 2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Song Info"));

        lblTitle   = makeInfoLabel("Title:   —");
        lblAuthor  = makeInfoLabel("Author:  —");
        lblComment = makeInfoLabel("Comment: —");
        lblFile    = makeInfoLabel("File:    —");

        panel.add(lblTitle);
        panel.add(lblAuthor);
        panel.add(lblComment);
        panel.add(lblFile);
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
        filterRow.add(new JLabel("Filter:"));
        cmbFilter = new JComboBox<>(LpfOption.values());
        cmbFilter.setSelectedItem(LpfOption.OFF);
        cmbFilter.addActionListener(e ->
                player.setLpfOption((LpfOption) cmbFilter.getSelectedItem()));
        filterRow.add(cmbFilter);

        // ---- Buttons row ------------------------------------------------
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));

        btnOpen = new JButton("Open…");
        btnPlay = new JButton("Play");
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

        // ---- Listeners --------------------------------------------------
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
        chooser.setDialogTitle("Select a YM file");
        chooser.setFileFilter(new FileNameExtensionFilter("YM music files (*.ym)", "ym"));
        chooser.setCurrentDirectory(currentDir.toFile());

        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        Path path = chooser.getSelectedFile().toPath();

        // If the user navigated to a different directory, refresh the file list
        Path parentDir = path.getParent();
        if (parentDir != null && !parentDir.equals(currentDir)) {
            currentDir = parentDir;
            refreshFileList(currentDir);
        }

        loadAndPlay(path);
    }

    /** Loads the YM file and immediately starts playback. */
    private void loadAndPlay(Path path) {
        stopPlayback();
        try {
            selectedYm   = YmFileParser.parse(path);
            selectedPath = path;
            populateSongInfo();
            btnPlay.setEnabled(true);
            btnExport.setEnabled(true);
            lblStatus.setText("Loaded: " + path.getFileName());
            startPlayback();
        } catch (IOException ex) {
            selectedYm   = null;
            selectedPath = null;
            btnPlay.setEnabled(false);
            btnExport.setEnabled(false);
            JOptionPane.showMessageDialog(frame,
                    "Could not load YM file:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            lblStatus.setText("Error loading file.");
        }
    }

    private void togglePlayback() {
        if (player.isPlaying()) {
            stopPlayback();
        } else {
            startPlayback();
        }
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
        if (selectedYm == null || selectedPath == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save WAV file");
        chooser.setFileFilter(new FileNameExtensionFilter("WAV audio files (*.wav)", "wav"));
        String suggested = selectedPath.getFileName().toString()
                .replaceAll("(?i)\\.ym$", "") + ".wav";
        chooser.setSelectedFile(new java.io.File(currentDir.toFile(), suggested));

        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        Path wavPath = chooser.getSelectedFile().toPath();
        // Ensure .wav extension
        if (!wavPath.getFileName().toString().toLowerCase().endsWith(".wav")) {
            wavPath = wavPath.resolveSibling(wavPath.getFileName() + ".wav");
        }

        final Path finalWavPath = wavPath;
        final YmFile ymToExport = selectedYm;

        btnExport.setEnabled(false);
        lblStatus.setText("Exporting: " + finalWavPath.getFileName() + "…");

        Thread exportThread = new Thread(() -> {
            try {
                player.exportWav(ymToExport, finalWavPath);
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
        }, "ym-wav-export");
        exportThread.setDaemon(true);
        exportThread.start();
    }

    // -----------------------------------------------------------------------
    // File list helpers
    // -----------------------------------------------------------------------

    /**
     * Repopulates the file list with {@code .ym} files found in {@code dir}.
     * Files are listed in alphabetical order.
     */
    private void refreshFileList(Path dir) {
        fileListModel.clear();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ym"))
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
        if (!player.isPlaying() || selectedYm == null) return;

        double dur = durationSeconds();
        double pos = player.getPositionSeconds();
        if (dur > 0) {
            int pct = (int) Math.min(1000, pos / dur * 1000);
            progressBar.setValue(pct);
        }
        updateTimeLabel(pos, dur);
    }

    private void updateTimeLabel(double pos, double dur) {
        lblTime.setText(formatTime(pos) + " / " + formatTime(dur));
    }

    private double durationSeconds() {
        return selectedYm == null ? 0.0
                : (double) selectedYm.numFrames() / selectedYm.frameRate();
    }

    private void populateSongInfo() {
        if (selectedYm == null) return;
        lblTitle.setText("Title:   " + orDash(selectedYm.songName()));
        lblAuthor.setText("Author:  " + orDash(selectedYm.authorName()));
        lblComment.setText("Comment: " + orDash(selectedYm.comment()));
        lblFile.setText("File:    " + selectedPath.getFileName());
        updateTimeLabel(0, durationSeconds());
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

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    private static String formatTime(double seconds) {
        int s = (int) seconds;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
