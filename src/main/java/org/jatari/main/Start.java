package org.jatari.main;

import org.jm2149.vhdl.indexed.Ym2149AudioIndexed;

public class Start {
    public static void main(String[] args) {
        var ym2149 = new Ym2149AudioIndexed();
        ym2149.applyReset();
        ym2149.setNoisePeriod(5);
        ym2149.setVolume(0, 15);
        ym2149.setVolume(1, 7);
        ym2149.setVolume(2, 3);
        ym2149.setMixer(false, false, false, true, true, true);
        
        for (int i = 0; i < 1_000_000_000; i++) {
            ym2149.risingEdge(true, false, true, false, false, 0);
            if ((i % 100_000_000) == 0) {
                System.out.printf("Sample %d: chA=%d chB=%d chC=%d %n",
                        i, ym2149.getChAIndexO(), ym2149.getChBIndexO(), ym2149.getChCIndexO());
            }
        }
    }
}
