package io.github.xkitsios.piece;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;

import java.io.IOException;
import java.util.Arrays;

/**
 * The outer layer of a binary: one byte with the format version, one byte that says whether the content is compressed
 * with Zstandard, then the encoded segments.
 */
class Envelope {
    /** First byte of every binary. Bump it whenever the layout changes; decompression rejects other versions. */
    static final byte FORMAT_VERSION = 1;
    private static final byte RAW = 0, ZSTD = 1;

    /** Wraps the encoded segments, compressing them with Zstandard if {@code zstd}. */
    static byte[] wrap(byte[] content, boolean zstd) {
        byte[] body = zstd ? Zstd.compress(content) : content;
        byte[] output = new byte[body.length + 2];
        output[0] = FORMAT_VERSION;
        output[1] = zstd ? ZSTD : RAW;
        System.arraycopy(body, 0, output, 2, body.length);
        return output;
    }

    /**
     * The encoded segments of a binary produced by {@link #wrap}. With Zstandard, the original size is read from the
     * frame header, which {@code Zstd.compress} always writes.
     *
     * @throws IOException if the format version is not {@link #FORMAT_VERSION} or the content is not valid
     */
    static byte[] unwrap(byte[] binary) throws IOException {
        if (binary.length == 0 || binary[0] != FORMAT_VERSION)
            throw new IOException("Unsupported binary format version: "
                    + (binary.length == 0 ? "empty input" : Byte.toUnsignedInt(binary[0])) + " (expected " + FORMAT_VERSION + ")");
        if (binary.length < 2 || (binary[1] != RAW && binary[1] != ZSTD))
            throw new IOException("Not a valid binary: unknown compression " + (binary.length < 2 ? "(missing)" : binary[1]));
        byte[] body = Arrays.copyOfRange(binary, 2, binary.length);
        if (binary[1] == RAW) return body;
        try {
            long size = Zstd.getFrameContentSize(body); // negative if unknown or not a Zstandard frame
            if (size <= 0 || size > Integer.MAX_VALUE)
                throw new IOException("Not a valid compressed binary (unknown decompressed size)");
            byte[] output = Zstd.decompress(body, (int) size);
            if (output.length != size) throw new IOException("Truncated compressed binary");
            return output;
        } catch (ZstdException | IndexOutOfBoundsException e) { // zstd-jni on malformed or empty input
            throw new IOException("Not a valid compressed binary", e);
        }
    }
}
