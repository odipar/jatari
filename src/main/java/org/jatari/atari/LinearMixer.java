package org.jatari.atari;

import org.jaust.Context;
import org.jaust.Signal;
import org.jaust.processor.DefaultProcessor;
import org.jaust.signal.IntSignal;
import org.jaust.signal.SignalArray;
import org.jaust.signal.array.DefaultArray;
import static org.jaust.Signal.Type.INT;

public class LinearMixer implements DefaultProcessor {
    private static final int[] DACROM = {
        0x000, 0x017, 0x01B, 0x021, 0x027, 0x02E, 0x037, 0x041,
        0x04D, 0x05C, 0x06D, 0x081, 0x09A, 0x0B7, 0x0D9, 0x102,
        0x133, 0x16D, 0x1B2, 0x204, 0x265, 0x2D8, 0x361, 0x405,
        0x4C7, 0x5AD, 0x6BF, 0x804, 0x987, 0xB53, 0xD76, 0xFFF
    };
    
    private final Context context;
    public LinearMixer(Context context) {
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
            public Context context() { return LinearMixer.this.context; }
            public int intAt(long t) {
                int ci = DACROM[a.intAt(t)];
                int bi = DACROM[b.intAt(t)];
                int ai = DACROM[c.intAt(t)];
                
                return ((ci + bi + ai)<<3) / 3;
            }
        };
        return DefaultArray.a(s);
    }
}
