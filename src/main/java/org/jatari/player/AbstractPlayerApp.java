package org.jatari.player;

import javax.sound.sampled.LineUnavailableException;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * Abstract base for Swing player applications ({@link YmPlayerApp}, {@link PsgPlayerApp}).
 *
 * <p>Provides the shared UI scaffolding: file list panel, progress bar, filter
 * combo-boxes, play/stop/export buttons, and all the plumbing that connects them
 * to an {@link AbstractPlayer}.  Subclasses supply the format-specific parts:
 * file parsing, info-panel layout, WAV export dispatch, etc.
 *
 * @param <P> concrete player type (e.g. {@link YmPlayer} or {@link PsgPlayer})
 */
abstract class AbstractPlayerApp<P extends AbstractPlayer<?>> {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    protected final P    player;
    protected Path       selectedPath;
    protected Path       currentDir;

    // -----------------------------------------------------------------------
    // Swing components
    // -----------------------------------------------------------------------

    protected JFrame                              frame;
    protected JLabel                              lblTime;
    protected JProgressBar                        progressBar;
    protected JButton                             btnOpen;
    protected JButton                             btnPlay;
    protected JButton                             btnExport;
    protected JLabel                              lblStatus;
    protected JComboBox<AbstractPlayer.LpfOption> cmbLpf;
    protected JComboBox<AbstractPlayer.HpfOption> cmbHpf;
    protected JList<Path>                         fileList;
    protected DefaultListModel<Path>              fileListModel;
    protected Timer                               uiTimer;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    protected AbstractPlayerApp() {
        this.player = createPlayer();
    }

    // -----------------------------------------------------------------------
    // Abstract extension points
    // -----------------------------------------------------------------------

    /** Creates the format-specific player instance. */
    protected abstract P createPlayer();

    /** Window title. */
    protected abstract String appTitle();

    /** Default directory shown in the file list on start-up. */
    protected abstract Path defaultDir();

    /** File extension used to populate the file list, e.g. {@code "ym"}. */
    protected abstract String fileExtension();

    /** Border title for the file-list panel, e.g. {@code "Files"}. */
    protected abstract String fileListTitle();

    /** Description shown in the open-file filter, e.g. {@code "YM music files (*.ym)"}. */
    protected abstract String fileFilterDescription();

    /** Title for the open-file dialog. */
    protected abstract String openDialogTitle();

    /** Builds the info panel shown above the progress bar (format-specific). */
    protected abstract JPanel buildInfoPanel();

    /**
     * Called just before {@link #doLoad(Path)} is invoked; can be used to
     * show a "Loading…" status message.  Default is a no-op.
     */
    protected void onBeforeLoad(Path path) { /* no-op */ }

    /**
     * Parses the file at {@code path}, stores it internally, and populates
     * the info panel labels.
     *
     * @throws IOException if the file cannot be read or is unsupported
     */
    protected abstract void doLoad(Path path) throws IOException;

    /**
     * Clears the internally stored file reference after a failed load.
     */
    protected abstract void onLoadFailed();

    /** Returns the current track duration in seconds (0 if nothing is loaded). */
    protected abstract double durationSeconds();

    /** Returns {@code true} if a file is currently loaded and ready to play. */
    protected abstract boolean hasSelectedFile();

    /**
     * Returns the suggested WAV file name (without path) for the
     * currently selected file, e.g. {@code "mysong.wav"}.
     */
    protected abstract String wavSuggestion();

    /**
     * Performs the actual WAV export to {@code wavPath} on a background thread.
     * Called with the selected file already loaded.
     */
    protected abstract void doExportWav(Path wavPath) throws IOException;

    /** Thread name for the WAV export background thread. */
    protected abstract String exportThreadName();

    // -----------------------------------------------------------------------
    // UI construction
    // -----------------------------------------------------------------------

    protected void buildAndShow() {
        frame = new JFrame(appTitle());
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

        player.setListener(new AbstractPlayer.Listener() {
            @Override public void onProgress(double pos, double dur) { /* polling timer handles it */ }
            @Override public void onStopped() {
                SwingUtilities.invokeLater(AbstractPlayerApp.this::onPlaybackStopped);
            }
        });

        Path def = defaultDir();
        currentDir = Files.isDirectory(def) ? def : Paths.get(".");
        refreshFileList(currentDir);
    }

    private JPanel buildFileListPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(fileListTitle()));
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

        JScrollPane scroll = new JScrollPane(fileList);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildMainPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(buildInfoPanel(),     BorderLayout.NORTH);
        panel.add(buildProgressPanel(), BorderLayout.CENTER);
        panel.add(buildControlPanel(),  BorderLayout.SOUTH);
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

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filterRow.add(new JLabel("Low Pass:"));
        cmbLpf = new JComboBox<>(AbstractPlayer.LpfOption.values());
        cmbLpf.setSelectedItem(AbstractPlayer.LpfOption.OFF);
        cmbLpf.addActionListener(e ->
                player.setLpfOption((AbstractPlayer.LpfOption) cmbLpf.getSelectedItem()));
        filterRow.add(cmbLpf);

        filterRow.add(new JLabel("  High Pass:"));
        cmbHpf = new JComboBox<>(AbstractPlayer.HpfOption.values());
        cmbHpf.setSelectedItem(AbstractPlayer.HpfOption.OFF);
        cmbHpf.addActionListener(e ->
                player.setHpfOption((AbstractPlayer.HpfOption) cmbHpf.getSelectedItem()));
        filterRow.add(cmbHpf);

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
        chooser.setDialogTitle(openDialogTitle());
        chooser.setFileFilter(new FileNameExtensionFilter(
                fileFilterDescription(), fileExtension()));
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

    protected void loadAndPlay(Path path) {
        stopPlayback();
        onBeforeLoad(path);
        try {
            doLoad(path);
            selectedPath = path;
            btnPlay.setEnabled(true);
            btnExport.setEnabled(true);
            lblStatus.setText("Loaded: " + path.getFileName());
            startPlayback();
        } catch (IOException ex) {
            selectedPath = null;
            onLoadFailed();
            btnPlay.setEnabled(false);
            btnExport.setEnabled(false);
            JOptionPane.showMessageDialog(frame,
                    "Could not load file:\n" + ex.getMessage(),
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
        if (!hasSelectedFile() || selectedPath == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save WAV file");
        chooser.setFileFilter(new FileNameExtensionFilter("WAV audio files (*.wav)", "wav"));
        chooser.setSelectedFile(new java.io.File(currentDir.toFile(), wavSuggestion()));

        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        Path wavPath = chooser.getSelectedFile().toPath();
        if (!wavPath.getFileName().toString().toLowerCase().endsWith(".wav")) {
            wavPath = wavPath.resolveSibling(wavPath.getFileName() + ".wav");
        }

        final Path finalWavPath = wavPath;
        btnExport.setEnabled(false);
        lblStatus.setText("Exporting: " + finalWavPath.getFileName() + "…");

        Thread t = new Thread(() -> {
            try {
                doExportWav(finalWavPath);
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
        }, exportThreadName());
        t.setDaemon(true);
        t.start();
    }

    // -----------------------------------------------------------------------
    // File list
    // -----------------------------------------------------------------------

    protected void refreshFileList(Path dir) {
        fileListModel.clear();
        if (!Files.isDirectory(dir)) return;
        String ext = "." + fileExtension();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(ext))
                  .sorted()
                  .forEach(fileListModel::addElement);
        } catch (IOException ex) {
            lblStatus.setText("Cannot list directory: " + ex.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Progress helpers
    // -----------------------------------------------------------------------

    private void updateProgress() {
        if (!player.isPlaying() || !hasSelectedFile()) return;
        double dur = durationSeconds();
        double pos = player.getPositionSeconds();
        if (dur > 0) progressBar.setValue((int) Math.min(1000, pos / dur * 1000));
        updateTimeLabel(pos, dur);
    }

    protected void updateTimeLabel(double pos, double dur) {
        lblTime.setText(formatTime(pos) + " / " + formatTime(dur));
    }

    // -----------------------------------------------------------------------
    // Static utilities
    // -----------------------------------------------------------------------

    protected static JLabel makeInfoLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.PLAIN));
        return l;
    }

    protected static String formatTime(double seconds) {
        int s = (int) seconds;
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
