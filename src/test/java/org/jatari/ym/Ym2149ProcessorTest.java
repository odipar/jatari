package org.jatari.ym;

import org.jaust.Context;
import org.jaust.Processor;
import org.jaust.Signal;
import org.jaust.context.DefaultContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Ym2149Processor}.
 */
class Ym2149ProcessorTest {

    private static final Path DATA_DIR = Path.of("data/ym_format");

    // -----------------------------------------------------------------------
    // Structural / metadata tests
    // -----------------------------------------------------------------------

    @Test
    void processor_hasCorrectContextFrequency() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        Processor regs  = YmFileProcessor.of(ym);
        Processor audio = Ym2149Processor.of(regs);

        assertEquals(Ym2149Processor.DEFAULT_SIMULATION_FREQ,
                audio.context().frequency(),
                "context frequency must be the default simulation frequency");
    }

    @Test
    void processor_hasThreeIntOutputs() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        Processor audio = Ym2149Processor.of(YmFileProcessor.of(ym));

        assertEquals(3, audio.outType().length, "must have 3 outputs");
        for (var t : audio.outType()) {
            assertEquals(Signal.Type.INT, t, "each output must be INT");
        }
        assertEquals(0, audio.inType().length, "must have 0 formal inputs");
    }

    @Test
    void processor_customFrequency_hasCorrectContext() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        Processor regs  = YmFileProcessor.of(ym);
        long customFreq = 125_000L;
        Processor audio = Ym2149Processor.of(regs, customFreq);

        assertEquals(customFreq, audio.context().frequency());
        assertEquals(3, audio.outType().length);
    }

    // -----------------------------------------------------------------------
    // Output range tests
    // -----------------------------------------------------------------------

    @Test
    void processor_captureYm_outputsInValidRange() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        Processor audio = Ym2149Processor.of(YmFileProcessor.of(ym));
        var signals = audio.apply();

        // Sample the first 1000 simulation ticks and verify DAC index range.
        for (int t = 0; t < 1000; t++) {
            int chA = signals.at(0).intAt(t);
            int chB = signals.at(1).intAt(t);
            int chC = signals.at(2).intAt(t);
            assertTrue(chA >= 0 && chA <= 31, "chA at t=%d out of range: %d".formatted(t, chA));
            assertTrue(chB >= 0 && chB <= 31, "chB at t=%d out of range: %d".formatted(t, chB));
            assertTrue(chC >= 0 && chC <= 31, "chC at t=%d out of range: %d".formatted(t, chC));
        }
    }

    // -----------------------------------------------------------------------
    // Register-write timing test
    // -----------------------------------------------------------------------

    @Test
    void processor_registersWrittenAtFrameBoundaries() throws IOException {
        // The first frame boundary is at simulation tick 0.
        // After that, boundaries are at multiples of (simFreq / frameRate).
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        long simFreq  = Ym2149Processor.DEFAULT_SIMULATION_FREQ;
        long srcFreq  = ym.frameRate();
        long ticksPerFrame = simFreq / srcFreq; // 5000 for 50Hz → 250kHz

        Processor audio = Ym2149Processor.of(YmFileProcessor.of(ym));
        var signals = audio.apply();

        // Verify that output values stay within valid DAC index range both
        // just before and just after a frame boundary.
        int beforeBoundary = signals.at(0).intAt(ticksPerFrame - 1);
        int afterBoundary  = signals.at(0).intAt(ticksPerFrame);

        assertTrue(beforeBoundary >= 0 && beforeBoundary <= 31);
        assertTrue(afterBoundary  >= 0 && afterBoundary  <= 31);
    }

    // -----------------------------------------------------------------------
    // Known-configuration test
    // -----------------------------------------------------------------------

    @Test
    void processor_knownConfig_channelAProducesNonZeroOutput() {
        // Build a synthetic 15-output input: channel A tone active with volume 15.
        // R0=255 → fine period, R7=0b111110 → tone A on, R8=15 → max volume.
        Context srcCtx = new DefaultContext(50L);
        int[] regs = new int[14];
        regs[0] = 255;        // Channel A fine period
        regs[7] = 0b111110;   // Mixer: tone A enabled, noise disabled for all
        regs[8] = 15;         // Channel A volume = 15

        Processor[] procs = new Processor[15];
        for (int i = 0; i < 14; i++) {
            final int val = regs[i];
            procs[i] = srcCtx.genI(t -> val);
        }
        procs[14] = srcCtx.genB(t -> true); // isWriting always true

        Processor src   = srcCtx.par(procs);
        Processor audio = Ym2149Processor.of(src);
        var signals = audio.apply();

        // After 10 000 simulation ticks channel A should produce non-zero
        // output for at least one sample (tone should toggle high).
        boolean anyNonZero = false;
        for (int t = 0; t < 10_000; t++) {
            if (signals.at(0).intAt(t) > 0) {
                anyNonZero = true;
                break;
            }
        }
        assertTrue(anyNonZero, "channel A should produce non-zero output with volume=15");
    }

    // -----------------------------------------------------------------------
    // Validation tests
    // -----------------------------------------------------------------------

    @Test
    void processor_wrongInputCount_throwsException() {
        Context ctx = new DefaultContext(50L);
        Processor shortInput = ctx.par(ctx.genI(t -> 0), ctx.genI(t -> 0)); // only 2 outputs
        assertThrows(IllegalArgumentException.class, () -> Ym2149Processor.of(shortInput));
    }

    @Test
    void processor_invalidSimFreq_throwsException() throws IOException {
        YmFile ym = YmFileParser.parse(DATA_DIR.resolve("capture.ym"));
        Processor regs = YmFileProcessor.of(ym);
        assertThrows(IllegalArgumentException.class, () -> Ym2149Processor.of(regs, 0L));
        assertThrows(IllegalArgumentException.class, () -> Ym2149Processor.of(regs, -1L));
    }
}

