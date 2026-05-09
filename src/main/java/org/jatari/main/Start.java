package org.jatari.main;

import org.jaust.Processor;
import org.jatari.dsp.Downsampler;
import org.jatari.dsp.Upsampler;
import org.jatari.ym.YmFile;
import org.jatari.ym.YmFileParser;
import org.jatari.ym.YmFileProcessor;
import org.jatari.ym.Ym2149Processor;
import org.jm2149.vhdl.indexed.Ym2149AudioIndexed;

import java.nio.file.Path;

public class Start {
    public static void main(String[] args) throws Exception {
        // --- YM file processor demo ---
        Path ymPath = Path.of("data/ym_format/capture.ym");
        YmFile ym = YmFileParser.parse(ymPath);
        System.out.printf("Loaded: %s (%d frames @ %d Hz, clock=%d Hz)%n",
                ymPath.getFileName(), ym.numFrames(), ym.frameRate(), ym.ymClock());

        Processor proc = YmFileProcessor.of(ym);
        System.out.printf("YmFileProcessor: %d outputs, context frequency=%d Hz%n",
                proc.outType().length, proc.context().frequency());

        // Print first 3 frames (registers R0–R13 + isWriting flag)
        var signals = proc.apply();
        for (int frame = 0; frame < Math.min(3, ym.numFrames()); frame++) {
            System.out.print("Frame " + frame + ": ");
            for (int r = 0; r < 14; r++) {
                System.out.printf("R%d=%3d ", r, signals.at(r).intAt(frame));
            }
            System.out.printf("isWriting=%b%n", signals.at(14).boolAt(frame));
        }

        // --- Upsampler demo: 50 Hz → 250 kHz ---
        Processor upsampled = Upsampler.of(proc, 250_000L);
        System.out.printf("%nUpsampler: %d outputs, context frequency=%d Hz%n",
                upsampled.outType().length, upsampled.context().frequency());
        var upSignals = upsampled.apply();
        System.out.println("isWriting at first 10 upsampled ticks (true only at tick 0):");
        for (int t = 0; t < 10; t++) {
            System.out.printf("  t=%d → isWriting=%b%n", t, upSignals.at(14).boolAt(t));
        }

        // --- Ym2149Processor demo ---
        Processor audio = Ym2149Processor.of(proc);
        System.out.printf("%nYm2149Processor: %d outputs, context frequency=%d Hz%n",
                audio.outType().length, audio.context().frequency());
        var audioSignals = audio.apply();
        System.out.println("First 5 audio samples (chA, chB, chC DAC indices):");
        for (int t = 0; t < 5; t++) {
            System.out.printf("  t=%d → chA=%d, chB=%d, chC=%d%n",
                    t,
                    audioSignals.at(0).intAt(t),
                    audioSignals.at(1).intAt(t),
                    audioSignals.at(2).intAt(t));
        }

        // --- Downsampler demo: 250 kHz → 44.1 kHz ---
        Processor downsampled = Downsampler.of(audio, 44_100L);
        System.out.printf("%nDownsampler: %d outputs, context frequency=%d Hz%n",
                downsampled.outType().length, downsampled.context().frequency());

        // --- Original YM2149 hardware demo ---
        var ym2149 = new Ym2149AudioIndexed();
        ym2149.applyReset();
        ym2149.setNoisePeriod(5);
        ym2149.setVolume(0, 15);
        ym2149.setMixer(false, false, false, true, true, true);

        for (int i = 0; i < 1_000_000_000; i++) {
            ym2149.risingEdge(true, false, true, false, false, 0);
            if ((i % 100_000_000) == 0) {
                System.out.println(i);
                System.out.printf("Sample %d: chA=%d chB=%d chC=%d %n",
                        i, ym2149.getChAIndexO(), ym2149.getChBIndexO(), ym2149.getChCIndexO());
            }
        }
    }
}

