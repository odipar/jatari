package org.jatari.main;

import org.jm2149.vhdl.indexed.Ym2149AudioIndexed;

public class Start {
    public static void main(String[] args) {
        var ym2149 = new Ym2149AudioIndexed();
        ym2149.applyReset();
        ym2149.setTonePeriod(0, 0x123);
        ym2149.setTonePeriod(1, 0x456);
        ym2149.setTonePeriod(2, 0x789);
        ym2149.setVolume(0, 15);
        ym2149.setVolume(1, 7);
        ym2149.setVolume(2, 3);
        ym2149.setMixer(true, true, true, false, false, false);
        
        for (int i = 0; i < 10000; i++) {
            ym2149.risingEdge(true, false, true, false, false, 0);
            if ((i % 100) == 0) {
                System.out.printf("Sample %d: chA=%d chB=%d chC=%d %n",
                        i, ym2149.getChAIndexO(), ym2149.getChBIndexO(), ym2149.getChCIndexO());
            }
        }
    }
}
