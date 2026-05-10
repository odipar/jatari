package org.jatari.ym.format;

import java.io.*;

/**
 * Decompresses one -lh5- (LZH5) compressed data stream.
 *
 * <p>This is a self-contained, dependency-free Java port of the LHA -lh5-
 * algorithm (Haruyasu Yoshizaki, 1991) used to pack YM music files.
 *
 * <p>Algorithm overview:
 * <ul>
 *   <li>Sliding dictionary of 8 192 bytes (DICBIT = 13).</li>
 *   <li>Minimum match length: 3 bytes (THRESHOLD).</li>
 *   <li>Maximum match length: 256 bytes (MAXMATCH).</li>
 *   <li>Each compressed block carries its own Huffman tables (T, C, P).</li>
 * </ul>
 */
class Lzh5Decompressor {

    // --- Algorithm constants for -lh5- ---
    private static final int DICBIT    = 13;
    private static final int DICSIZ    = 1 << DICBIT;   // 8 192

    private static final int MAXMATCH  = 256;
    private static final int THRESHOLD = 3;

    /** Character / length codes: 256 literals + (MAXMATCH - THRESHOLD + 1) lengths = 510. */
    private static final int NC   = 510;
    private static final int CBIT = 9;    // bits to read the NC count

    /** T-codes (code-length codes that encode the C-table lengths). */
    private static final int NT   = 19;
    private static final int TBIT = 5;    // bits to read the NT count

    /** Position codes (DICBIT + 1 = 14). */
    private static final int NP   = 14;
    private static final int PBIT = 4;    // bits to read the NP count

    /** Direct-lookup table size for the C-table (2^12 = 4 096). */
    private static final int C_TABLEBITS = 12;
    private static final int C_TABLESIZE = 1 << C_TABLEBITS;

    // --- Source stream ---
    private final InputStream src;
    private long srcLeft;

    // --- Bit-buffer  (16-bit; bits consumed from the MSB) ---
    private int bitBuf;
    private int subBuf;
    private int subBits;   // valid bits remaining in subBuf

    // --- Sliding dictionary ---
    private final byte[] dict = new byte[DICSIZ];
    private int dictPos;

    // --- Huffman tables, reused per block ---
    // T-table (code-length codes; 2^TBIT = 32 slots)
    private final int[] tLen   = new int[NT];
    private final int[] tTable = new int[1 << TBIT];
    private final int[] tLeft  = new int[2 * NT];
    private final int[] tRight = new int[2 * NT];

    // C-table (character / length codes; 2^C_TABLEBITS = 4096 slots)
    private final int[] cLen   = new int[NC];
    private final int[] cTable = new int[C_TABLESIZE];
    private final int[] cLeft  = new int[2 * NC];
    private final int[] cRight = new int[2 * NC];

    // P-table (position codes; 2^PBIT = 16 slots)
    private final int[] pLen   = new int[NP];
    private final int[] pTable = new int[1 << PBIT];
    private final int[] pLeft  = new int[2 * NP];
    private final int[] pRight = new int[2 * NP];

    // -----------------------------------------------------------------------

    /**
     * @param source         stream positioned at the first byte of compressed data
     * @param compressedSize number of compressed bytes in {@code source}
     */
    Lzh5Decompressor(InputStream source, long compressedSize) {
        this.src     = source instanceof BufferedInputStream ? source : new BufferedInputStream(source);
        this.srcLeft = compressedSize;
        bitBuf  = 0;
        subBuf  = 0;
        subBits = 0;
        fillBuf(16);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Decompresses exactly {@code origSize} bytes, writing them to {@code out}.
     */
    void decompress(OutputStream out, long origSize) throws IOException {
        dictPos = 0;
        long written    = 0;
        int  blockSize  = 0;

        while (written < origSize) {
            if (blockSize == 0) {
                blockSize = getBits(16);
                if (blockSize == 0) break;
                readCTable();           // reads T-table then C-table
                readPTable();           // reads P-table
            }

            int c = decodeC();
            if (c < 256) {
                byte b = (byte) c;
                dict[dictPos] = b;
                dictPos = (dictPos + 1) & (DICSIZ - 1);
                out.write(b & 0xFF);
                written++;
            } else {
                int matchLen = c - 256 + THRESHOLD;
                int matchPos = (dictPos - decodeP() - 1) & (DICSIZ - 1);
                for (int i = 0; i < matchLen && written < origSize; i++, written++) {
                    byte b = dict[(matchPos + i) & (DICSIZ - 1)];
                    dict[dictPos] = b;
                    dictPos = (dictPos + 1) & (DICSIZ - 1);
                    out.write(b & 0xFF);
                }
            }
            blockSize--;
        }
    }

    // -----------------------------------------------------------------------
    // Bit I/O
    // -----------------------------------------------------------------------

    /** Consume {@code n} bits, refilling the buffer from the source as needed. */
    private void fillBuf(int n) {
        bitBuf = (bitBuf << n) & 0xFFFF;
        while (n > subBits) {
            bitBuf  |= (subBuf << (n -= subBits)) & 0xFFFF;
            subBuf   = readByte();
            subBits  = 8;
        }
        bitBuf |= (subBuf >> (subBits -= n)) & ((1 << n) - 1);
    }

    /** Peek at the top {@code n} bits of the buffer without consuming them. */
    private int peekBits(int n) {
        return bitBuf >> (16 - n);
    }

    /** Consume and return the top {@code n} bits. */
    private int getBits(int n) {
        int v = peekBits(n);
        fillBuf(n);
        return v;
    }

    private int readByte() {
        if (srcLeft <= 0) return 0;
        srcLeft--;
        try {
            int b = src.read();
            return (b >= 0) ? b : 0;
        } catch (IOException e) { return 0; }
    }

    // -----------------------------------------------------------------------
    // Huffman table construction
    // -----------------------------------------------------------------------

    /**
     * Reads {@code nn} code lengths from the bit stream and builds a
     * direct-lookup Huffman table.
     *
     * <p>Each code length is a 3-bit value; lengths &ge; 7 are extended by
     * consuming additional 1-bits until a 0-bit is found (unary extension).
     * At position {@code special} a 2-bit zero-run is inserted (pass -1 to
     * disable).
     *
     * <p>If the leading {@code nbit}-bit count is 0, all table entries are
     * set to a single symbol read next from the stream.
     *
     * @param nn       number of symbols in this alphabet
     * @param nbit     bits used to read the number of codes
     * @param special  index at which a 2-bit zero-run extension is inserted (-1 = none)
     * @param len      output: code length for each symbol (size &ge; nn)
     * @param tabBits  bits for the direct-lookup table index
     * @param table    output: direct-lookup table (size 2^tabBits)
     * @param left     output: binary-tree left-child array (size 2*nn)
     * @param right    output: binary-tree right-child array (size 2*nn)
     */
    private void readPtLen(int nn, int nbit, int special,
                           int[] len, int tabBits, int[] table,
                           int[] left, int[] right) throws IOException {
        int n = getBits(nbit);
        if (n == 0) {
            int c   = getBits(nbit);
            int tsz = 1 << tabBits;
            for (int i = 0; i < nn;  i++) len[i]   = 0;
            for (int i = 0; i < tsz; i++) table[i] = c;
            return;
        }

        int i = 0;
        while (i < n) {
            int v = peekBits(3);
            if (v < 7) {
                fillBuf(3);
            } else {
                fillBuf(3);
                while (peekBits(1) == 1) { v++; fillBuf(1); }
                fillBuf(1);    // consume terminating 0-bit
            }
            len[i++] = v;
            if (i - 1 == special) {
                int zeros = getBits(2);
                for (int z = 0; z < zeros && i < nn; z++) len[i++] = 0;
            }
        }
        while (i < nn) len[i++] = 0;
        buildTable(nn, len, tabBits, table, left, right);
    }

    /**
     * Builds a canonical-Huffman direct-lookup table.
     *
     * <p>Codes of length &le; {@code tabBits} fill one or more contiguous
     * slots in {@code table}.  Longer codes are resolved via a binary tree
     * stored in {@code left}/{@code right}; internal nodes use indices
     * &ge; {@code nLeaves}.
     */
    private void buildTable(int nLeaves, int[] len, int tabBits,
                            int[] table, int[] left, int[] right) throws IOException {
        int tblSz = 1 << tabBits;

        int[] count = new int[17];
        for (int i = 0; i < nLeaves; i++) {
            if (len[i] > 16) throw new IOException("Huffman code length > 16");
            count[len[i]]++;
        }

        // Canonical starting codes
        int[] startCode = new int[18];
        startCode[1] = 0;
        for (int i = 1; i <= 15; i++) startCode[i + 1] = (startCode[i] + count[i]) << 1;

        for (int i = 0; i < tblSz;           i++) table[i] = 0;
        for (int i = 0; i < 2 * nLeaves; i++) { left[i] = 0; right[i] = 0; }

        int avail = nLeaves;

        for (int sym = 0; sym < nLeaves; sym++) {
            int l = len[sym];
            if (l == 0) continue;

            int code = startCode[l]++;

            if (l <= tabBits) {
                int fill  = tblSz >> l;
                int base  = code << (tabBits - l);
                for (int k = 0; k < fill; k++) table[base + k] = sym;
            } else {
                int idx  = code >>> (l - tabBits);
                int node = table[idx];
                if (node == 0) { table[idx] = node = avail; left[avail] = right[avail] = 0; avail++; }
                for (int d = l - tabBits - 1; d > 0; d--) {
                    int bit = (code >> d) & 1;
                    if (bit == 0) {
                        if (left[node] == 0) { left[node] = avail; left[avail] = right[avail] = 0; avail++; }
                        node = left[node];
                    } else {
                        if (right[node] == 0) { right[node] = avail; left[avail] = right[avail] = 0; avail++; }
                        node = right[node];
                    }
                }
                if ((code & 1) == 0) left[node]  = sym;
                else                 right[node] = sym;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Block-header table reading
    // -----------------------------------------------------------------------

    /** Reads the T-table, then uses it to decode and build the C-table. */
    private void readCTable() throws IOException {
        readPtLen(NT, TBIT, /*special=*/2, tLen, TBIT, tTable, tLeft, tRight);

        int nc = getBits(CBIT);
        if (nc == 0) {
            int c = getBits(CBIT);
            for (int i = 0; i < NC;          i++) cLen[i]   = 0;
            for (int i = 0; i < C_TABLESIZE; i++) cTable[i] = c;
            return;
        }

        int i = 0;
        while (i < nc) {
            int c = decodeFrom(tTable, tLeft, tRight, TBIT, NT, tLen);
            if (c == 0) {
                cLen[i++] = 0;
            } else if (c == 1) {
                int zeros = getBits(4) + 3;
                for (int z = 0; z < zeros && i < NC; z++) cLen[i++] = 0;
            } else if (c == 2) {
                int zeros = getBits(CBIT) + 20;
                for (int z = 0; z < zeros && i < NC; z++) cLen[i++] = 0;
            } else {
                cLen[i++] = c - 2;   // symbols 3..18 → lengths 1..16
            }
        }
        while (i < NC) cLen[i++] = 0;
        buildTable(NC, cLen, C_TABLEBITS, cTable, cLeft, cRight);
    }

    /** Reads the P-table (position codes). */
    private void readPTable() throws IOException {
        readPtLen(NP, PBIT, /*special=*/-1, pLen, PBIT, pTable, pLeft, pRight);
    }

    // -----------------------------------------------------------------------
    // Symbol decoding
    // -----------------------------------------------------------------------

    private int decodeC() { return decodeFrom(cTable, cLeft, cRight, C_TABLEBITS, NC, cLen); }

    private int decodeP() {
        int j = decodeFrom(pTable, pLeft, pRight, PBIT, NP, pLen);
        if (j != 0) j = (1 << (j - 1)) | getBits(j - 1);
        return j;
    }

    /**
     * Decodes one leaf symbol from a direct-lookup Huffman table.
     *
     * <p>If the table yields a leaf (entry &lt; nLeaves), exactly
     * {@code len[leaf]} bits are consumed (the actual code length).
     * Otherwise, {@code tabBits} bits are consumed and the tree is traversed
     * one bit at a time until a leaf is reached.
     */
    private int decodeFrom(int[] table, int[] left, int[] right,
                           int tabBits, int nLeaves, int[] len) {
        int sym = table[peekBits(tabBits)];
        if (sym < nLeaves) {
            fillBuf(len[sym]);
        } else {
            fillBuf(tabBits);
            while (sym >= nLeaves) {
                sym = ((bitBuf & 0x8000) != 0) ? right[sym] : left[sym];
                fillBuf(1);
            }
        }
        return sym;
    }
}
