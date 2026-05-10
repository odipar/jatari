package org.jatari.main;

import org.jaust.Processor;
import org.jatari.ym.format.YmFile;
import org.jatari.ym.format.YmFileParser;
import org.jatari.ym.format.YmFileProcessor;
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

        Processor fileProc = YmFileProcessor.of(ym);
        System.out.printf("YmFileProcessor: %d outputs (14 INT registers + 1 BOOL write-enable), context frequency=%d Hz%n",
                fileProc.outType().length, fileProc.context().frequency());

        // Print first 3 frames (registers R0–R13)
        var signals = fileProc.apply();
        for (int frame = 0; frame < Math.min(3, ym.numFrames()); frame++) {
            System.out.print("Frame " + frame + ": ");
            for (int r = 0; r < 14; r++) {
                System.out.printf("R%d=%3d ", r, signals.at(r).intAt(frame));
            }
            System.out.printf("we=%b%n", signals.at(14).boolAt(frame));
        }

        // --- YM2149 processor demo (15-signal pipeline) ---
        Processor ymProc = Ym2149Processor.of(fileProc);
        System.out.printf("%nYm2149Processor: %d INT outputs, context frequency=%d Hz%n",
                ymProc.outType().length, ymProc.context().frequency());

        var ymOut = ymProc.apply();
        int samplesToShow = 3;
        long ratio = Ym2149Processor.YM_CLOCK / ym.frameRate(); // samples per frame
        for (int frame = 0; frame < samplesToShow; frame++) {
            long t = frame * ratio;
            System.out.printf("YM sample t=%6d (frame %d): chA=%2d chB=%2d chC=%2d%n",
                    t, frame,
                    ymOut.at(0).intAt(t),
                    ymOut.at(1).intAt(t),
                    ymOut.at(2).intAt(t));
        }

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

