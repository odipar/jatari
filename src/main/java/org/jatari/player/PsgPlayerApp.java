package org.jatari.player;

import org.jatari.psg.PsgCapture;
import org.jatari.psg.PsgCaptureParser;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

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
 *   <li>Click <em>Play</em> / <em>Stop</em> to control playback.</li>
 *   <li>Select a cutoff in the <em>Low Pass</em> / <em>High Pass</em>
 *       combo-boxes to enable the IIR filters on the fly.</li>
 *   <li>Click <em>Export WAV…</em> to save the capture as a WAV file.</li>
 * </ol>
 */
public class PsgPlayerApp extends AbstractPlayerApp<PsgPlayer> {

    private static final String APP_TITLE   = "PSG Capture Player";
    private static final Path   DEFAULT_DIR = Paths.get("data/psg_capture");

    // -----------------------------------------------------------------------
    // PSG-specific state
    // -----------------------------------------------------------------------

    private PsgCapture selectedCapture;

    // PSG info labels
    private JLabel lblFile;
    private JLabel lblDuration;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new PsgPlayerApp().buildAndShow());
    }

    // -----------------------------------------------------------------------
    // AbstractPlayerApp extension points
    // -----------------------------------------------------------------------

    @Override protected PsgPlayer createPlayer()          { return new PsgPlayer(); }
    @Override protected String    appTitle()              { return APP_TITLE; }
    @Override protected Path      defaultDir()            { return DEFAULT_DIR; }
    @Override protected String    fileExtension()         { return "zip"; }
    @Override protected String    fileListTitle()         { return "Captures"; }
    @Override protected String    fileFilterDescription() { return "PSG capture files (*.csv.zip)"; }
    @Override protected String    openDialogTitle()       { return "Select a PSG capture file"; }
    @Override protected String    exportThreadName()      { return "psg-wav-export"; }

    @Override
    protected void onBeforeLoad(Path path) {
        lblStatus.setText("Loading: " + path.getFileName() + "…");
    }

    @Override
    protected boolean hasSelectedFile() { return selectedCapture != null; }

    @Override
    protected double durationSeconds() {
        return selectedCapture == null ? 0.0 : selectedCapture.durationSeconds();
    }

    @Override
    protected String wavSuggestion() {
        return selectedPath.getFileName().toString()
                .replaceAll("(?i)\\.csv\\.zip$", "") + ".wav";
    }

    @Override
    protected JPanel buildInfoPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 2, 2));
        panel.setBorder(javax.swing.BorderFactory.createTitledBorder("Capture Info"));

        lblFile     = makeInfoLabel("File:     —");
        lblDuration = makeInfoLabel("Duration: —");

        panel.add(lblFile);
        panel.add(lblDuration);
        return panel;
    }

    @Override
    protected void doLoad(Path path) throws IOException {
        selectedCapture = PsgCaptureParser.parse(path);
        populateCaptureInfo(path);
    }

    @Override
    protected void onLoadFailed() { selectedCapture = null; }

    @Override
    protected void doExportWav(Path wavPath) throws IOException {
        player.exportWav(selectedCapture, wavPath);
    }

    // -----------------------------------------------------------------------
    // Info panel population
    // -----------------------------------------------------------------------

    private void populateCaptureInfo(Path path) {
        lblFile.setText("File:     " + path.getFileName());
        lblDuration.setText("Duration: " + formatTime(selectedCapture.durationSeconds()));
        updateTimeLabel(0, selectedCapture.durationSeconds());
        progressBar.setValue(0);
    }
}
