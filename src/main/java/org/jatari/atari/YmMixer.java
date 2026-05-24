package org.jatari.atari;

import org.jaust.Context;
import org.jaust.Signal;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;
import static org.jaust.Signal.Type.INT;

public class YmMixer implements DefaultProcessor {
    private final Context context;
    
    /** Hatari-like unsigned 3-voice mixing table normalized to signed range. */
    static final int[] HATARI_MIX_TABLE = buildHatariMixTable();
    
    public YmMixer(Context context) {
        this.context = context;
    }
    public Context context() {
        return context;
    }
    public Signal.Type[] inType() {
        return new Signal.Type[] {INT, INT, INT};
    }
    public Signal.Type[] outType() {
        return new Signal.Type[] {INT};
    }
    public SignalArray apply(SignalArray signal) {
        var a = signal.at(0);
        var b = signal.at(1);
        var c = signal.at(2);
        
        var s = new IntSignal() {
            public Context context() { return YmMixer.this.context; }
            public int intAt(long t) {
                int ci = a.intAt(t);
                int bi = b.intAt(t);
                int ai = c.intAt(t);
                return HATARI_MIX_TABLE[(ci << 10) | (bi << 5) | ai];
            }
        };
        return DefaultArray.a(s);
    }
    
    static int[] buildHatariMixTable() {
        int[] table = new int[32 * 32 * 32];
        double[] conductance = new double[32];
        double current = 1.0;
        double fourthRootTwo = 1.19;
        double warp = 1.6666666666666667;
        
        for (int i = 31; i >= 1; i--) {
            conductance[i] = current / 2.0;
            current = 1.0 / (1.0 - 1.0 / fourthRootTwo / (1.0 / current + 1.0))
                - 1.0;
        }
        conductance[0] = 1.0e-8;
        
        double max = modelMixValue(conductance[31], conductance[31],
            conductance[31], warp);
        for (int c = 0; c < 32; c++) {
            for (int b = 0; b < 32; b++) {
                for (int a = 0; a < 32; a++) {
                    double value = modelMixValue(conductance[c], conductance[b],
                        conductance[a], warp);
                    int normalized = (int) (value * 32767.0 / max);
                    table[(c << 10) | (b << 5) | a] =
                        (short) Math.max(0, Math.min(32767, normalized));
                }
            }
        }
        return table;
    }

    
    private static double modelMixValue(double c, double b, double a, double warp) {
        return (65535.0 * warp) / (1.0 + 1.0 / (c + b + a));
    }
}
