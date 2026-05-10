package org.jatari.player;

import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Java Swing application for playing YM2149 music files at 44.1 kHz.
 *
 * <p>Launch via {@link #main(String[])} or by running the {@code exec:java}
 * Maven goal (configured to call {@code org.jatari.main.Start}, which
 * delegates here).
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Click <em>Open…</em> to choose a {@code .ym} file.</li>
 *   <li>Click <em>Play</em> to start playback; the button label changes to
 *       <em>Stop</em>.</li>
 *   <li>Click <em>Stop</em> (or <em>Play</em> again) to halt playback.</li>
 * </ol>
 */
public class YmPlayerApp {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String APP_TITLE = "YM2149 44.1 kHz Player";

    /** Default starting directory for the file chooser. */
    private static final Path DEFAULT_DIR = Paths.get("data/ym_format");

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private final YmPlayer player = new YmPlayer();

    private Path    selectedPath;
    private YmFile  selectedYm;

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
    private JLabel     lblStatus;

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
        frame.setResizable(false);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        root.add(buildInfoPanel(),    BorderLayout.NORTH);
        root.add(buildProgressPanel(), BorderLayout.CENTER);
        root.add(buildControlPanel(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(480, frame.getHeight()));
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));

        btnOpen = new JButton("Open…");
        btnPlay = new JButton("Play");
        btnPlay.setEnabled(false);
        btnPlay.setPreferredSize(new Dimension(80, btnPlay.getPreferredSize().height));

        buttons.add(btnOpen);
        buttons.add(btnPlay);

        lblStatus = new JLabel(" ");
        lblStatus.setFont(lblStatus.getFont().deriveFont(Font.PLAIN, 11f));
        lblStatus.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));

        panel.add(buttons,   BorderLayout.CENTER);
        panel.add(lblStatus, BorderLayout.SOUTH);

        // ---- Listeners --------------------------------------------------
        btnOpen.addActionListener(e -> openFile());
        btnPlay.addActionListener(e -> togglePlayback());

        return panel;
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select a YM file");
        chooser.setFileFilter(new FileNameExtensionFilter("YM music files (*.ym)", "ym"));

        Path startDir = Files.isDirectory(DEFAULT_DIR) ? DEFAULT_DIR : Paths.get(".");
        chooser.setCurrentDirectory(startDir.toFile());

        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        stopPlayback();

        Path path = chooser.getSelectedFile().toPath();
        try {
            selectedYm   = YmFileParser.parse(path);
            selectedPath = path;
            populateSongInfo();
            btnPlay.setEnabled(true);
            lblStatus.setText("Loaded: " + path.getFileName());
        } catch (IOException ex) {
            selectedYm   = null;
            selectedPath = null;
            btnPlay.setEnabled(false);
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
