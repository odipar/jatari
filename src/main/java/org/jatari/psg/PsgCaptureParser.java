package org.jatari.psg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Parses Hatari PSG register-capture files (zipped CSV format).
 *
 * <h2>File format</h2>
 * <p>Each {@code .csv.zip} archive contains a single {@code .csv} file.
 * The CSV file begins with one or more comment lines starting with {@code #}
 * and then contains data lines in the format:
 * <pre>
 *   clock,reg,value,pc
 * </pre>
 * where:
 * <ul>
 *   <li>{@code clock} — Atari 8 MHz master-clock tick at which the write occurred</li>
 *   <li>{@code reg}   — YM2149 register number (0–13)</li>
 *   <li>{@code value} — register value (0–255)</li>
 *   <li>{@code pc}    — program counter (ignored)</li>
 * </ul>
 * Lines are assumed to be sorted in ascending {@code clock} order.
 */
public final class PsgCaptureParser {

    private PsgCaptureParser() {}

    /**
     * Parses the PSG capture file at {@code zipPath}.
     *
     * @param zipPath path to a {@code .csv.zip} file
     * @return the parsed {@link PsgCaptureFile}
     * @throws IOException if the file cannot be read or contains no CSV entry
     */
    public static PsgCaptureFile parse(Path zipPath) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".csv")) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        return parseCsv(is);
                    }
                }
            }
        }
        throw new IOException("No .csv entry found in: " + zipPath);
    }

    // -----------------------------------------------------------------------
    // Internal CSV parser
    // -----------------------------------------------------------------------

    private static PsgCaptureFile parseCsv(InputStream is) throws IOException {
        // Pre-allocate with an initial capacity; resize as needed.
        int capacity = 1 << 16;
        long[] clocks = new long[capacity];
        int[]  regs   = new int[capacity];
        int[]  values = new int[capacity];
        int    size   = 0;

        try (var reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Skip blank lines and comment lines
                int len = line.length();
                if (len == 0 || line.charAt(0) == '#') continue;

                // Locate the first three comma-separated fields
                int c1 = line.indexOf(',');
                if (c1 < 0) continue;
                int c2 = line.indexOf(',', c1 + 1);
                if (c2 < 0) continue;
                int c3 = line.indexOf(',', c2 + 1);
                int end = c3 >= 0 ? c3 : len;

                long clock = Long.parseLong(line, 0,      c1,  10);
                int  reg   = Integer.parseInt(line, c1 + 1, c2,  10);
                int  value = Integer.parseInt(line, c2 + 1, end, 10);

                // Grow arrays if necessary
                if (size == capacity) {
                    capacity *= 2;
                    long[] nc = new long[capacity]; System.arraycopy(clocks, 0, nc, 0, size); clocks = nc;
                    int[]  nr = new int[capacity];  System.arraycopy(regs,   0, nr, 0, size); regs   = nr;
                    int[]  nv = new int[capacity];  System.arraycopy(values, 0, nv, 0, size); values = nv;
                }

                clocks[size] = clock;
                regs[size]   = reg;
                values[size] = value;
                size++;
            }
        }

        // Trim to exact size
        if (size < capacity) {
            long[] tc = new long[size]; System.arraycopy(clocks, 0, tc, 0, size); clocks = tc;
            int[]  tr = new int[size];  System.arraycopy(regs,   0, tr, 0, size); regs   = tr;
            int[]  tv = new int[size];  System.arraycopy(values, 0, tv, 0, size); values = tv;
        }

        return new PsgCaptureFile(clocks, regs, values);
    }
}
