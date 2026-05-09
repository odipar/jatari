package org.jatari.ym;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.lha.LhaArchiveInputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses YM music files (YM3!, YM5!, and YM6! formats) into a {@link YmFile}.
 *
 * <h2>Supported formats</h2>
 * <ul>
 *   <li><b>YM3!</b> – uncompressed, sequential frame layout (14 bytes / frame).
 *       Frame rate defaults to 50 Hz (PAL Atari ST).</li>
 *   <li><b>YM5! / YM6!</b> – may be stored verbatim or wrapped in a single-file
 *       LHA (-lh5-) archive. The header carries an explicit frame rate and YM
 *       master clock. Frame data may be interleaved (bit 0 of song attributes)
 *       or non-interleaved.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * YmFile ym = YmFileParser.parse(Path.of("music.ym"));
 * }</pre>
 */
public class YmFileParser {

    private static final int DEFAULT_FRAME_RATE = 50;
    private static final int DEFAULT_YM_CLOCK   = 2_000_000;

    // LHA level-0 archive detection: bytes 2..6 are '-lh?-' or '-lz?-'
    private static final byte[] LHA_MARKER = { '-', 'l', 'h' };

    private YmFileParser() {}

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    /**
     * Parses the YM file at {@code path}.
     *
     * @param path path to a {@code .ym} file
     * @return parsed {@link YmFile}
     * @throws IOException if the file cannot be read or has an unrecognised format
     */
    public static YmFile parse(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        return parse(raw);
    }

    /**
     * Parses raw YM file bytes.
     *
     * @param raw raw file bytes (may be an LHA archive wrapping a YM5!/YM6! file)
     * @return parsed {@link YmFile}
     * @throws IOException on format error
     */
    public static YmFile parse(byte[] raw) throws IOException {
        // Detect and unwrap LHA archive (level-0, method -lh5-)
        if (isLhaArchive(raw)) {
            raw = extractLha(raw);
        }
        return parseYm(raw);
    }

    // -----------------------------------------------------------------------
    // LHA unwrapping
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code data} looks like an LHA level-0 archive.
     *
     * <p>Detection: bytes 2..4 == '-lh' (covers -lh0-, -lh1-, ..., -lh7-).
     */
    private static boolean isLhaArchive(byte[] data) {
        if (data.length < 7) return false;
        return data[2] == LHA_MARKER[0]
            && data[3] == LHA_MARKER[1]
            && data[4] == LHA_MARKER[2];
    }

    /**
     * Extracts the first file from an LHA archive using Apache Commons Compress.
     */
    private static byte[] extractLha(byte[] data) throws IOException {
        try (LhaArchiveInputStream lhaIn = new LhaArchiveInputStream(new ByteArrayInputStream(data))) {
            ArchiveEntry entry = lhaIn.getNextEntry();
            if (entry == null) {
                throw new IOException("Empty LHA archive");
            }
            return lhaIn.readAllBytes();
        }
    }

    // -----------------------------------------------------------------------
    // YM format parsing
    // -----------------------------------------------------------------------

    private static YmFile parseYm(byte[] data) throws IOException {
        if (data.length < 4) throw new IOException("File too short to be a valid YM file");
        String magic = new String(data, 0, 4);
        return switch (magic) {
            case "YM3!", "YM4!" -> parseYm3(data);
            case "YM5!", "YM6!" -> parseYm5(data);
            default -> throw new IOException("Unrecognised YM format: " + magic);
        };
    }

    // -----------------------------------------------------------------------
    // YM3! / YM4! (sequential, uncompressed, no header metadata)
    // -----------------------------------------------------------------------

    /**
     * YM3! and YM4! layout:
     * <pre>
     *   [0..3]  magic ("YM3!" or "YM4!")
     *   [4..]   numFrames × 14 bytes, each frame has R0..R13 in order
     * </pre>
     * The frame rate is conventionally 50 Hz (PAL).
     */
    private static YmFile parseYm3(byte[] data) throws IOException {
        int bodyLen = data.length - 4;
        if (bodyLen <= 0 || bodyLen % 14 != 0) {
            throw new IOException("YM3/YM4 body length not a multiple of 14");
        }
        int numFrames = bodyLen / 14;

        byte[][] frames = new byte[numFrames][14];
        for (int f = 0; f < numFrames; f++) {
            System.arraycopy(data, 4 + f * 14, frames[f], 0, 14);
        }

        return new YmFile(numFrames, DEFAULT_FRAME_RATE, DEFAULT_YM_CLOCK,
                          /*loopFrame=*/0, /*song=*/"", /*author=*/"", /*comment=*/"", frames);
    }

    // -----------------------------------------------------------------------
    // YM5! / YM6! (may be interleaved; explicit header metadata)
    // -----------------------------------------------------------------------

    /**
     * YM5!/YM6! header layout (all multi-byte fields are big-endian):
     * <pre>
     *   [0..3]   magic ("YM5!" or "YM6!")
     *   [4..11]  "LeOnArD!" check string
     *   [12..15] numFrames (LWORD)
     *   [16..19] songAttributes (LWORD): bit 0 = interleaved
     *   [20..21] numDigidrums (WORD)
     *   [22..25] YM master clock in Hz (LWORD)
     *   [26..27] frame rate in Hz (WORD)
     *   [28..31] loopFrame (LWORD)
     *   [32..33] extra data size to skip (WORD)
     *   [34+]    optional extra bytes (size from above)
     *            then, for each digidrum: LWORD size + sample bytes
     *            then: song name (NUL-terminated)
     *            then: author name (NUL-terminated)
     *            then: comment (NUL-terminated)
     *            then: frame data
     * </pre>
     */
    private static YmFile parseYm5(byte[] data) throws IOException {
        if (data.length < 34) throw new IOException("YM5/YM6 header truncated");

        String check = new String(data, 4, 8);
        if (!"LeOnArD!".equals(check)) {
            throw new IOException("YM5/YM6 check string missing (got: " + check + ')');
        }

        int numFrames  = (int) readBe32(data, 12);
        int attrs      = (int) readBe32(data, 16);
        int numDigi    = readBe16(data, 20);
        long ymClock   = readBe32(data, 22);
        int frameRate  = readBe16(data, 26);
        int loopFrame  = (int) readBe32(data, 28);
        int extraSize  = readBe16(data, 32);

        boolean interleaved = (attrs & 1) != 0;

        int pos = 34 + extraSize;

        // Skip digidrum samples
        for (int d = 0; d < numDigi; d++) {
            if (pos + 4 > data.length) throw new IOException("YM5/YM6 digidrum header truncated");
            int sampleSize = (int) readBe32(data, pos);
            pos += 4 + sampleSize;
        }

        // Read NUL-terminated strings
        String[] strings = new String[3];
        for (int s = 0; s < 3; s++) {
            int end = pos;
            while (end < data.length && data[end] != 0) end++;
            strings[s] = new String(data, pos, end - pos);
            pos = end + 1;
        }

        // Read frame data
        int frameDataLen = numFrames * 14;
        if (pos + frameDataLen > data.length) {
            throw new IOException("YM5/YM6 frame data truncated (need " + frameDataLen
                    + " bytes, have " + (data.length - pos) + ')');
        }

        byte[][] frames = new byte[numFrames][14];
        if (interleaved) {
            // Interleaved: data[reg * numFrames + frame] for all regs then all frames
            for (int r = 0; r < 14; r++) {
                for (int f = 0; f < numFrames; f++) {
                    frames[f][r] = data[pos + r * numFrames + f];
                }
            }
        } else {
            // Non-interleaved: sequential frames, 14 bytes each
            for (int f = 0; f < numFrames; f++) {
                System.arraycopy(data, pos + f * 14, frames[f], 0, 14);
            }
        }

        return new YmFile(numFrames, frameRate, ymClock, loopFrame,
                          strings[0], strings[1], strings[2], frames);
    }

    // -----------------------------------------------------------------------
    // Big-endian multi-byte readers
    // -----------------------------------------------------------------------

    private static long readBe32(byte[] data, int off) {
        return ((long) (data[off]     & 0xFF) << 24)
             | ((long) (data[off + 1] & 0xFF) << 16)
             | ((long) (data[off + 2] & 0xFF) <<  8)
             |  (long) (data[off + 3] & 0xFF);
    }

    private static int readBe16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }
}
