package org.jatari.ym;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link YmFileParser} and {@link YmFileProcessor}.
 */
class YmFileProcessorTest {

    private static final Path DATA_DIR = Path.of("data/ym_format");

    // -----------------------------------------------------------------------
    // YmFileParser – YM3! (no compression)
    // -----------------------------------------------------------------------

    @Test
    void parseYm3_captureYm_returnsCorrectMetadata() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));

        assertEquals(921,        ym.numFrames(),  "frame count");
        assertEquals(50,         ym.frameRate(),  "frame rate (PAL)");
        assertEquals(2_000_000L, ym.ymClock(),    "YM clock");
        assertEquals(0,          ym.loopFrame(),  "loop frame");
    }

    @Test
    void parseYm3_captureYm_has14RegistersPerFrame() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));

        // Sanity: all 14 registers are readable without exception
        for (int r = 0; r < 14; r++) {
            int v = ym.registerAt(0, r);
            assertTrue(v >= 0 && v <= 255,
                    "register R%d at frame 0 must be 0-255, got %d".formatted(r, v));
        }
    }

    // -----------------------------------------------------------------------
    // YmFileParser – LZH5-compressed YM5!/YM6!
    // -----------------------------------------------------------------------

    @Test
    void parseYm5_lzhCompressed_returnsCorrectMetadata() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("5th Gear 1 title.ym"));

        assertEquals(19500,       ym.numFrames(), "frame count");
        assertEquals(50,          ym.frameRate(), "frame rate");
        assertEquals(2_000_000L,  ym.ymClock(),   "YM clock");
        assertEquals("5th Gear Tune 1",  ym.songName(),   "song name");
        assertEquals("Jochen Hippel",    ym.authorName(), "author");
    }

    @Test
    void parseYm5_lzhCompressed_has14RegistersPerFrame() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("5th Gear 1 title.ym"));

        // Check a few frames
        for (int frame : new int[]{0, 1, 100, ym.numFrames() - 1}) {
            for (int r = 0; r < 14; r++) {
                int v = ym.registerAt(frame, r);
                assertTrue(v >= 0 && v <= 255,
                        "R%d at frame %d must be 0-255, got %d".formatted(r, frame, v));
            }
        }
    }

    // -----------------------------------------------------------------------
    // YmFileProcessor – signal output
    // -----------------------------------------------------------------------

    @Test
    void processor_captureYm_has15OutputsAtFrameRate() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        var proc = YmFileProcessor.of(ym);

        // Context frequency must equal the YM file's frame rate
        assertEquals(ym.frameRate(), proc.context().frequency(),
                "context frequency must equal frame rate");

        // Must have exactly 15 outputs: 14 INT registers + 1 BOOL write-enable
        assertEquals(15, proc.outType().length, "15 output signals");
        for (int i = 0; i < 14; i++) {
            assertEquals(org.jaust.Signal.Type.INT, proc.outType()[i],
                    "output %d must be INT".formatted(i));
        }
        assertEquals(org.jaust.Signal.Type.BOOL, proc.outType()[14],
                "output 14 must be BOOL (write-enable)");

        // Must have zero inputs
        assertEquals(0, proc.inType().length, "zero inputs");
    }

    @Test
    void processor_captureYm_writeEnableAlwaysTrueInSourceDomain() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        var signals = YmFileProcessor.of(ym).apply();

        for (int frame = 0; frame < Math.min(10, ym.numFrames()); frame++) {
            assertTrue(signals.at(14).boolAt(frame),
                    "write-enable must be true at frame %d".formatted(frame));
        }
    }

    @Test
    void processor_captureYm_signalsMatchFrameData() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        var proc = YmFileProcessor.of(ym);
        var signals = proc.apply();

        for (int frame = 0; frame < Math.min(10, ym.numFrames()); frame++) {
            for (int r = 0; r < 14; r++) {
                int expected = ym.registerAt(frame, r);
                int actual   = signals.at(r).intAt(frame);
                assertEquals(expected, actual,
                        "R%d at frame %d".formatted(r, frame));
            }
        }
    }

    @Test
    void processor_captureYm_wrapsAroundAfterLastFrame() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        var signals = YmFileProcessor.of(ym).apply();
        int n = ym.numFrames();

        // frame n should equal frame 0 (modular replay)
        for (int r = 0; r < 14; r++) {
            assertEquals(signals.at(r).intAt(0), signals.at(r).intAt(n),
                    "signal R%d must wrap at frame %d".formatted(r, n));
        }
    }

    @Test
    void processor_lzhFile_producesValidSignals() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("5th Gear 1 title.ym"));
        var proc = YmFileProcessor.of(ym);
        var signals = proc.apply();

        assertEquals(15, proc.outType().length);
        assertEquals(ym.frameRate(), proc.context().frequency());

        for (int r = 0; r < 14; r++) {
            int v = signals.at(r).intAt(0);
            assertTrue(v >= 0 && v <= 255, "R%d signal value out of range".formatted(r));
        }
        assertTrue(signals.at(14).boolAt(0), "write-enable must be true at frame 0");
    }
}
