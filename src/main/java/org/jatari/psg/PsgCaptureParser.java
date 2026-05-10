package org.jatari.psg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * Parses Hatari PSG register-capture files ({@code *.csv.zip}).
 *
 * <h2>File format</h2>
 * <p>The CSV file inside the zip starts with optional comment lines prefixed
 * with {@code #}.  Data lines have the form:
 * <pre>clock,reg,value,pc</pre>
 * where {@code clock} is the Atari 8 MHz clock tick at which the YM2149
 * register write occurred, {@code reg} is the register index (0–13),
 * {@code value} is the byte written, and {@code pc} is the CPU program counter
 * (ignored by this parser).
 *
 * <h2>Normalisation</h2>
 * <p>Atari clock values are normalised so that the first event maps to YM tick 0:
 * <pre>ymTick = (atariClock − firstAtariClock) / 4</pre>
 * Division by 4 converts from the 8 MHz Atari clock to the 2 MHz YM2149 clock.
 */
public final class PsgCaptureParser {

    private PsgCaptureParser() {}

    /**
     * Parses the PSG capture file at {@code path}.
     *
     * @param path path to a {@code *.csv.zip} capture file
     * @return parsed {@link PsgCapture}
     * @throws IOException if the file cannot be read or contains no events
     */
    public static PsgCapture parse(Path path) throws IOException {
        Map<Long, int[]> writes = new HashMap<>();
        long firstAtariClock = -1;
        long lastAtariClock  = -1;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            if (zip.getNextEntry() == null) {
                throw new IOException("Empty zip file: " + path);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(zip));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;

                String[] parts = line.split(",", 4);
                if (parts.length < 3) continue;

                long atariClock = Long.parseLong(parts[0].trim());
                int  reg        = Integer.parseInt(parts[1].trim());
                int  value      = Integer.parseInt(parts[2].trim());

                if (firstAtariClock < 0) firstAtariClock = atariClock;
                lastAtariClock = atariClock;

                // Convert 8 MHz Atari clock to 2 MHz YM tick
                long ymTick = (atariClock - firstAtariClock) / 4;
                writes.put(ymTick, new int[]{reg, value});
            }
        }

        if (firstAtariClock < 0) {
            throw new IOException("No register-write events found in: " + path);
        }

        long lastYmTick      = (lastAtariClock - firstAtariClock) / 4;
        // Add 1 second of tail silence so the last note fully decays.
        long durationYmTicks = lastYmTick + PsgCapture.YM_CLOCK;

        return new PsgCapture(
                Collections.unmodifiableMap(writes),
                durationYmTicks,
                path.getFileName().toString());
    }
}
