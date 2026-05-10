package org.jatari.player;

import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Java Swing application for playing YM2149 music files at 44.1 kHz.
 *
 * <p>Launch via {@link #main(String[])} or by running {@code org.jatari.main.Start}.
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li>Click a file in the left-hand list to load and play it, or click
 *       <em>Open…</em> to navigate to any {@code .ym} file.</li>
 *   <li>Click <em>Play</em> / <em>Stop</em> to control playback.</li>
 *   <li>Select a cutoff in the <em>Low Pass</em> / <em>High Pass</em>
 *       combo-boxes to enable the IIR filters on the fly.</li>
 *   <li>Click <em>Export WAV…</em> to save the current song as a WAV file.</li>
 * </ol>
 */
public class YmPlayerApp extends AbstractPlayerApp<YmPlayer> {

    private static final String APP_TITLE  = "YM2149 44.1 kHz Player";
    private static final Path   DEFAULT_DIR = Paths.get("data/ym_format");

    // -----------------------------------------------------------------------
    // YM-specific state
    // -----------------------------------------------------------------------

    private YmFile selectedYm;

    // YM info labels
    private JLabel lblTitle;
    private JLabel lblAuthor;
    private JLabel lblComment;
    private JLabel lblFile;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new YmPlayerApp().buildAndShow());
    }

    // -----------------------------------------------------------------------
    // AbstractPlayerApp extension points
    // -----------------------------------------------------------------------

    @Override protected YmPlayer createPlayer()           { return new YmPlayer(); }
    @Override protected String   appTitle()               { return APP_TITLE; }
    @Override protected Path     defaultDir()             { return DEFAULT_DIR; }
    @Override protected String   fileExtension()          { return "ym"; }
    @Override protected String   fileListTitle()          { return "Files"; }
    @Override protected String   fileFilterDescription()  { return "YM music files (*.ym)"; }
    @Override protected String   openDialogTitle()        { return "Select a YM file"; }
    @Override protected String   exportThreadName()       { return "ym-wav-export"; }

    @Override
    protected boolean hasSelectedFile() { return selectedYm != null; }

    @Override
    protected double durationSeconds() {
        return selectedYm == null ? 0.0 : (double) selectedYm.numFrames() / selectedYm.frameRate();
    }

    @Override
    protected String wavSuggestion() {
        return selectedPath.getFileName().toString()
                .replaceAll("(?i)\\.ym$", "") + ".wav";
    }

    @Override
    protected JPanel buildInfoPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 1, 2, 2));
        panel.setBorder(javax.swing.BorderFactory.createTitledBorder("Song Info"));

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

    @Override
    protected void doLoad(Path path) throws IOException {
        selectedYm = YmFileParser.parse(path);
        populateSongInfo(path);
    }

    @Override
    protected void onLoadFailed() { selectedYm = null; }

    @Override
    protected void doExportWav(Path wavPath) throws IOException {
        player.exportWav(selectedYm, wavPath);
    }

    // -----------------------------------------------------------------------
    // Info panel population
    // -----------------------------------------------------------------------

    private void populateSongInfo(Path path) {
        lblTitle.setText("Title:   " + orDash(selectedYm.songName()));
        lblAuthor.setText("Author:  " + orDash(selectedYm.authorName()));
        lblComment.setText("Comment: " + orDash(selectedYm.comment()));
        lblFile.setText("File:    " + path.getFileName());
        updateTimeLabel(0, durationSeconds());
        progressBar.setValue(0);
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }
}

