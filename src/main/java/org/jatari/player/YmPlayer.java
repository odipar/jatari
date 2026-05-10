package org.jatari.player;

import org.jatari.atari.YmMixer;
import org.jatari.ym.Ym2149Processor;
import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;
import org.jatari.ym.format.YmFileProcessor;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.jaust.signal.SignalArray;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Plays YM files at 44.1 kHz using a cycle-accurate YM2149 simulation.
 *
 * <h2>Pipeline</h2>
 * <pre>
 * YmFileProcessor (frameRate Hz, 15 signals)
 *   → Ym2149Processor (250 kHz, 3 INT signals: chA / chB / chC)
 *     → YmMixer (250 kHz, 1 INT signal: mixed)
 *       → box-filter downsample → 44100 Hz, 16-bit signed mono PCM
 *         → javax.sound.sampled SourceDataLine
 * </pre>
 *
 * <p>Playback runs on a background daemon thread.  Call {@link #play(Path)} to
 * start and {@link #stop()} to end playback.  A {@link Listener} can be
 * registered to receive progress and stop events for UI updates.
 */
public class YmPlayer {

    /** Output sample rate in Hz. */
    public static final int SAMPLE_RATE = 44_100;

    /** Number of 16-bit output samples per audio-buffer write. */
    private static final int BUFFER_SAMPLES = 2048;

    private volatile boolean playing = false;
    private volatile boolean stopRequested = false;
    private volatile long positionSamples = 0;

    private volatile Thread playerThread;
    private volatile SourceDataLine audioLine;

    private Listener listener;

    // -----------------------------------------------------------------------
    // Listener interface
    // -----------------------------------------------------------------------

    /** Receives playback events from the player thread. */
    public interface Listener {
        /**
         * Called periodically during playback (approximately every 100 ms).
         *
         * @param positionSeconds  current playback position in seconds
         * @param durationSeconds  total song duration in seconds
         */
        void onProgress(double positionSeconds, double durationSeconds);

        /** Called when playback stops (song finished or {@link #stop()} called). */
        void onStopped();
    }

    /** Registers a listener that receives progress / stopped callbacks. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // -----------------------------------------------------------------------
    // Playback control
    // -----------------------------------------------------------------------

    /**
     * Parses the YM file at {@code ymPath} and starts playback on a background
     * thread.  Any currently running playback is stopped first.
     *
     * @param ymPath path to a {@code .ym} file
     * @throws IOException if the file cannot be read or has an unsupported format
     */
    public synchronized void play(Path ymPath) throws IOException {
        stop();
        YmFile ym = YmFileParser.parse(ymPath);
        playerThread = new Thread(() -> {
            try {
                runPlayback(ym);
            } catch (LineUnavailableException e) {
                System.err.println("Audio line unavailable: " + e.getMessage());
            }
        }, "ym-player");
        playerThread.setDaemon(true);
        playerThread.start();
    }

    /**
     * Stops playback and waits (up to 2 s) for the background thread to finish.
     */
    public synchronized void stop() {
        stopRequested = true;
        SourceDataLine l = this.audioLine;
        if (l != null) {
            l.stop();
            l.flush();
        }
        Thread t = playerThread;
        if (t != null && t.isAlive()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        playing = false;
    }

    /** Returns {@code true} if playback is currently running. */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * Returns the current playback position in seconds (updated every buffer
     * write, approximately every 46 ms at 44100 Hz / 2048 samples).
     */
    public double getPositionSeconds() {
        return positionSamples / (double) SAMPLE_RATE;
    }

    // -----------------------------------------------------------------------
    // Internal playback loop
    // -----------------------------------------------------------------------

    private void runPlayback(YmFile ym) throws LineUnavailableException {
        // ---- Build DSP pipeline ----------------------------------------
        Processor fileProc = YmFileProcessor.of(ym);
        Processor ymProc   = Ym2149Processor.of(fileProc);
        SignalArray ymOut  = ymProc.apply();

        YmMixer mixer    = new YmMixer(new DefaultContext(Ym2149Processor.YM_CLOCK));
        SignalArray mixOut = mixer.apply(ymOut);
        Signal mixSignal  = mixOut.at(0);   // single INT at 250 kHz

        // ---- Audio line -------------------------------------------------
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
            this.audioLine = line;
            line.open(format, BUFFER_SAMPLES * 2 * 2); // 2 bytes/sample × 2× buffer
            line.start();

            final long ymClock  = Ym2149Processor.YM_CLOCK;
            // Song duration expressed as number of 44.1 kHz output samples for
            // one complete pass through the file (loop point is handled by
            // YmFileProcessor's modulo wrap, so playback continues indefinitely).
            final long samplesPerLoop =
                    (long) ((double) ym.numFrames() * SAMPLE_RATE / ym.frameRate());
            final double durationSeconds = (double) ym.numFrames() / ym.frameRate();

            byte[] buffer = new byte[BUFFER_SAMPLES * 2]; // 16-bit LE
            int    bufIdx = 0;

            stopRequested = false;
            playing       = true;
            positionSamples = 0;

            // Notification interval: roughly every 100 ms
            final long notifyEvery = SAMPLE_RATE / 10;

            long t44k = 0;
            while (!stopRequested) {
                // ---- Box-filter downsample: average YM-clock samples --------
                long tymStart = t44k * ymClock / SAMPLE_RATE;
                long tymEnd   = (t44k + 1) * ymClock / SAMPLE_RATE;
                long sum   = 0;
                long count = tymEnd - tymStart;
                for (long tym = tymStart; tym < tymEnd; tym++) {
                    sum += mixSignal.intAt(tym);
                }
                int sample = (count > 0) ? (int) (sum / count) : 0;

                // 16-bit little-endian
                buffer[bufIdx++] = (byte)  (sample        & 0xFF);
                buffer[bufIdx++] = (byte) ((sample >> 8)  & 0xFF);

                if (bufIdx >= buffer.length) {
                    line.write(buffer, 0, bufIdx);
                    bufIdx = 0;
                }

                t44k++;
                positionSamples = t44k % samplesPerLoop;

                if (listener != null && (t44k % notifyEvery) == 0) {
                    final double pos = positionSamples / (double) SAMPLE_RATE;
                    listener.onProgress(pos, durationSeconds);
                }
            }

            // Flush remaining partial buffer
            if (bufIdx > 0) {
                line.write(buffer, 0, bufIdx);
            }
            line.drain();
            line.stop();
        }

        playing = false;
        this.audioLine = null;
        if (listener != null) {
            listener.onStopped();
        }
    }
}
