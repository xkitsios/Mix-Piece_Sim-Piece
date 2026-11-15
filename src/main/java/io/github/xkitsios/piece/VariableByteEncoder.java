package io.github.xkitsios.piece;

/**
 * Variable-byte encoding of a long: 7 bits per byte, least significant first; the high bit marks the last byte.
 * Non-negative values take 1 byte up to 127 and at most 10 bytes; signed values use zigzag encoding (small negative
 * values stay small).
 */
class VariableByteEncoder {
    /** Writes a non-negative {@code number} at {@code pos} and returns the position after it. */
    static int write(long number, byte[] buffer, int pos) {
        while ((number & ~0x7FL) != 0) {
            buffer[pos++] = (byte) (number & 0x7F);
            number >>>= 7;
        }
        buffer[pos++] = (byte) (number | 0x80);

        return pos;
    }

    /** Reads a non-negative value at {@code offsetHolder[0]} and advances it. */
    static long read(byte[] buffer, int[] offsetHolder) {
        long result = 0;
        for (int shift = 0; ; shift += 7) {
            if (shift > 63) throw new IllegalArgumentException("Variable-byte value longer than 10 bytes");
            byte b = buffer[offsetHolder[0]++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) != 0) return result;
        }
    }

    /** Writes a possibly negative {@code number} (zigzag) and returns the position after it. */
    static int writeSigned(long number, byte[] buffer, int pos) {
        return write((number << 1) ^ (number >> 63), buffer, pos);
    }

    /** Reads a value written by {@link #writeSigned}. */
    static long readSigned(byte[] buffer, int[] offsetHolder) {
        long zigzag = read(buffer, offsetHolder);
        return (zigzag >>> 1) ^ -(zigzag & 1);
    }

    /** Reads a count: a non-negative value that must fit in an int. */
    static int readCount(byte[] buffer, int[] offsetHolder) {
        long count = read(buffer, offsetHolder);
        if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid count " + count);
        return (int) count;
    }
}
