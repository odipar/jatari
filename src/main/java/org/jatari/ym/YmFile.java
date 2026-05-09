package org.jatari.ym;

/**
 * Parsed YM file data (YM3!, YM5!, or YM6! format).
 * <p>
 * Frame data is stored as {@code frames[frameIndex][registerIndex]}, where
 * {@code registerIndex} runs from 0 to 13 (YM2149 registers R0–R13).
 */
public record YmFile(
        int numFrames,
        int frameRate,
        long ymClock,
        int loopFrame,
        String songName,
        String authorName,
        String comment,
        byte[][] frames
) {
    /**
     * Returns the value of register {@code reg} at frame {@code frameIndex}.
     *
     * @param frameIndex frame index (0-based)
     * @param reg        register index (0–13)
     * @return register value as an unsigned int (0–255)
     */
    public int registerAt(int frameIndex, int reg) {
        return frames[frameIndex][reg] & 0xFF;
    }
}
