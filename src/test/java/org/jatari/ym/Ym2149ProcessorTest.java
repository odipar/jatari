package org.jatari.ym;

import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;
import org.jatari.ym.format.YmFileProcessor;
import org.jaust.Signal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Ym2149Processor}.
 */
class Ym2149ProcessorTest {

    private static final Path DATA_DIR = Path.of("data/ym_format");

    /** Number of YM clock samples to check in range-validation tests. */
    private static final int SAMPLES_TO_TEST = 10_000_000;

    // -----------------------------------------------------------------------
    // Structure / metadata
    // -----------------------------------------------------------------------

    @Test
    void processor_hasCorrectOutputTypes() throws IOException {
        var source = fileProcessor("capture.ym");
        var proc = Ym2149Processor.of(source);

        // Must have zero inputs
        assertEquals(0, proc.inType().length, "zero inputs");

        // Must have exactly 3 INT outputs
        assertEquals(3, proc.outType().length, "3 output signals");
        for (var t : proc.outType()) {
            assertEquals(Signal.Type.INT, t, "all outputs must be INT");
        }
    }

    @Test
    void processor_contextFrequencyIsYmClock() throws IOException {
        var source = fileProcessor("capture.ym");
        var proc = Ym2149Processor.of(source);

        assertEquals(Ym2149Processor.YM_CLOCK, proc.context().frequency(),
                "context frequency must equal YM_CLOCK (PAL default)");
    }

    @Test
    void processor_ntscClock_contextFrequencyIsNtscMasterClock() throws IOException {
        var source = fileProcessor("capture.ym");
        var proc = Ym2149Processor.of(source, Ym2149Processor.YM2149_F_MASTER_NTSC);

        assertEquals((long) Ym2149Processor.YM2149_F_MASTER_NTSC, proc.context().frequency(),
                "context frequency must equal (long) YM2149_F_MASTER_NTSC");
    }

    @Test
    void systemCrystalConstants_deriveCorrectMasterClocks() {
        assertEquals(32_084_988.0, Ym2149Processor.SYSTEM_CRYSTAL_PAL,  0.0, "PAL crystal");
        assertEquals(32_042_440.0, Ym2149Processor.SYSTEM_CRYSTAL_NTSC, 0.0, "NTSC crystal");
        assertEquals(Ym2149Processor.SYSTEM_CRYSTAL_PAL  / 16, Ym2149Processor.YM2149_F_MASTER_PAL,  0.0, "PAL master clock");
        assertEquals(Ym2149Processor.SYSTEM_CRYSTAL_NTSC / 16, Ym2149Processor.YM2149_F_MASTER_NTSC, 0.0, "NTSC master clock");
        // YM_CLOCK is the truncated integer PAL master clock (PAL default)
        assertEquals((long) Ym2149Processor.YM2149_F_MASTER_PAL, Ym2149Processor.YM_CLOCK,
                "YM_CLOCK must be (long) YM2149_F_MASTER_PAL");
    }

    // -----------------------------------------------------------------------
    // Output range
    // -----------------------------------------------------------------------

    @Test
    void processor_channelOutputsInValidRange() throws IOException {
        assertChannelOutputsInRange(fileProcessor("capture.ym"));
    }

    @Test
    void processor_lzhFile_channelOutputsInValidRange() throws IOException {
        assertChannelOutputsInRange(fileProcessor("5th Gear 1 title.ym"));
    }

    /**
     * Drives the given source through {@link Ym2149Processor} and asserts
     * that all three channel outputs are in the valid 5-bit DAC index range
     * [0, 31] for the first {@value SAMPLES_TO_TEST} YM clock samples.
     */
    private static void assertChannelOutputsInRange(org.jaust.Processor source) {
        var signals = Ym2149Processor.of(source).apply();
        for (long t = 0; t < SAMPLES_TO_TEST; t++) {
            int a = signals.at(0).intAt(t);
            int b = signals.at(1).intAt(t);
            int c = signals.at(2).intAt(t);
            assertTrue(a >= 0 && a <= 31, "chA out of range at t=" + t + ": " + a);
            assertTrue(b >= 0 && b <= 31, "chB out of range at t=" + t + ": " + b);
            assertTrue(c >= 0 && c <= 31, "chC out of range at t=" + t + ": " + c);
        }
    }

    // -----------------------------------------------------------------------
    // Upsampling / write-enable signal behaviour
    // -----------------------------------------------------------------------

    @Test
    void writeEnable_upsampled_trueOnlyAtFrameBoundaries() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        var source = YmFileProcessor.of(ym);

        // Upsample the source using the YM2149 context
        var ymCtx = new org.jaust.context.DefaultContext(Ym2149Processor.YM_CLOCK);
        var upsampled = ymCtx.resample(source);
        var signals = upsampled.apply();

        // Signal 14 is the write-enable BOOL.
        // jaust's UpProcessor places source sample i at the canonical output time
        //   canonical(i) = (i * tgtFreq + halfSrc) / srcFreq
        // where halfSrc = srcFreq / 2 (half-step rounding).
        long ratio = Ym2149Processor.YM_CLOCK / ym.frameRate();

        // Check first 3 frame boundaries and surrounding samples
        for (int frame = 0; frame < 3; frame++) {
            // Canonical output time for this frame (mirrors jaust UpProcessor rounding)
            long boundary = ((long) frame * Ym2149Processor.YM_CLOCK + ym.frameRate() / 2)
                    / ym.frameRate();
            assertTrue(signals.at(14).boolAt(boundary),
                    "write-enable must be true at frame boundary t=" + boundary);
            // Samples immediately after the boundary must be false
            for (long t = boundary + 1; t < boundary + ratio && t < boundary + 10; t++) {
                assertFalse(signals.at(14).boolAt(t),
                        "write-enable must be false between frames at t=" + t);
            }
        }
    }

    @Test
    void writeEnable_upsampled_exactlyOnePerFrame() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        var source = YmFileProcessor.of(ym);

        var ymCtx = new org.jaust.context.DefaultContext(Ym2149Processor.YM_CLOCK);
        var signals = ymCtx.resample(source).apply();

        long ratio = Ym2149Processor.YM_CLOCK / ym.frameRate();
        int framesToCheck = 5;

        for (int frame = 0; frame < framesToCheck; frame++) {
            long start = frame * ratio;
            long end   = start + ratio;
            long trueCount = 0;
            for (long t = start; t < end; t++) {
                if (signals.at(14).boolAt(t)) trueCount++;
            }
            assertEquals(1, trueCount,
                    "write-enable must fire exactly once per frame (frame %d)".formatted(frame));
        }
    }

    // -----------------------------------------------------------------------
    // Determinism / repeatability
    // -----------------------------------------------------------------------

    @Test
    void processor_sameResultOnRepeatedApply() throws IOException {
        var source = fileProcessor("capture.ym");

        // Each apply() creates an independent simulation — results must be equal
        var signals1 = Ym2149Processor.of(source).apply();
        var signals2 = Ym2149Processor.of(source).apply();

        for (long t = 0; t < SAMPLES_TO_TEST; t++) {
            assertEquals(signals1.at(0).intAt(t), signals2.at(0).intAt(t),
                    "chA must be deterministic at t=" + t);
            assertEquals(signals1.at(1).intAt(t), signals2.at(1).intAt(t),
                    "chB must be deterministic at t=" + t);
            assertEquals(signals1.at(2).intAt(t), signals2.at(2).intAt(t),
                    "chC must be deterministic at t=" + t);
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static org.jaust.Processor fileProcessor(String name) throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve(name));
        return YmFileProcessor.of(ym);
    }
}
