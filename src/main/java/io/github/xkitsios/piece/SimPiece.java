package io.github.xkitsios.piece;

import java.io.IOException;
import java.util.*;

/**
 * Sim-Piece: lossy compression of a time series with a maximum absolute error epsilon.
 * <p>
 * The series is split into disjoint linear segments. Each segment starts at its first value rounded to a multiple of
 * epsilon, and keeps the interval of slopes that fit all its points. Segments with the same starting value whose
 * slope intervals overlap are merged and stored once, with one shared slope. The result is compressed with Zstandard.
 */
public final class SimPiece {
    // State of one compression or decompression: every call works on its own instance, so calls are thread-safe
    private List<Segment> segments;

    private double epsilon;
    private long firstTimeStamp;
    private long lastTimeStamp;

    private SimPiece() {
    }

    /**
     * Compresses a time series.
     *
     * @param points  the time series: at least one point, timestamps strictly increasing, values finite
     * @param epsilon the maximum absolute error (&gt; 0)
     * @return the binary
     * @throws IllegalArgumentException if there are no points, a point is null, a value is NaN or infinite, timestamps
     *                                  are not strictly increasing or span more than a long, epsilon is not finite and
     *                                  &gt; 0, or |value| / epsilon &ge; 2^51 (beyond the precision of a double)
     */
    public static byte[] compress(List<Point> points, double epsilon) {
        return compress(points, epsilon, true);
    }

    /**
     * Compresses a time series, optionally without the final Zstandard step: a little larger binary (about 7% on the test
     * datasets), but no use of the native Zstandard library.
     *
     * @param points  the time series: at least one point, timestamps strictly increasing, values finite
     * @param epsilon the maximum absolute error (&gt; 0)
     * @param zstd    whether to compress the binary further with Zstandard ({@link #compress(List, double)}: true)
     * @return the binary
     * @throws IllegalArgumentException as {@link #compress(List, double)}
     */
    public static byte[] compress(List<Point> points, double epsilon, boolean zstd) {
        long[] timestamps = InputCheck.timestamps(points);
        return compress(timestamps, InputCheck.values(points), epsilon, zstd);
    }

    /**
     * Compresses a time series given as arrays.
     *
     * @param timestamps the timestamps: at least one, strictly increasing
     * @param values     the values, finite, one per timestamp
     * @param epsilon    the maximum absolute error (&gt; 0)
     * @return the binary
     * @throws IllegalArgumentException as {@link #compress(List, double)}, or if the arrays have different lengths
     */
    public static byte[] compress(long[] timestamps, double[] values, double epsilon) {
        return compress(timestamps, values, epsilon, true);
    }

    /**
     * Compresses a time series given as arrays, optionally without the final Zstandard step (see
     * {@link #compress(List, double, boolean)}).
     *
     * @param timestamps timestamps in ascending order
     * @param values the values, finite, one per timestamp
     * @param epsilon the maximum absolute error (&gt; 0)
     * @param zstd whether to compress the binary further with Zstandard
     * @return the binary
     * @throws IllegalArgumentException as {@link #compress(long[], double[], double)}
     */
    public static byte[] compress(long[] timestamps, double[] values, double epsilon, boolean zstd) {
        InputCheck.check(timestamps, values, epsilon);

        SimPiece simPiece = new SimPiece();
        simPiece.epsilon = epsilon;
        simPiece.firstTimeStamp = timestamps[0];
        simPiece.lastTimeStamp = timestamps[timestamps.length - 1];
        simPiece.segments = simPiece.compress(timestamps, values);
        return simPiece.toByteArray(zstd);
    }

    /**
     * Decompresses a binary produced by {@code compress}.
     *
     * @param binary a binary produced by {@code compress}
     * @return the decompressed series
     * @throws IOException if the binary is not a valid binary of this format version
     */
    public static DecompressedTimeSeries decompress(byte[] binary) throws IOException {
        SimPiece simPiece = new SimPiece();
        simPiece.readByteArray(binary);
        return simPiece.decompressed();
    }

    /**
     * Evaluates the decompressed series at the given timestamps (ascending): each uses the last segment that starts at
     * or before it. At the original timestamps every value is within epsilon of the original one. Timestamps outside
     * [firstTimestamp, lastTimestamp] are extrapolated from the first or last segment, without guarantee.
     *
     * @param decompressed the decompressed series
     * @param timestamps timestamps in ascending order
     * @return one point per timestamp
     * @throws IllegalArgumentException if the timestamps are not ascending
     */
    public static List<Point> evaluateAt(DecompressedTimeSeries decompressed, long[] timestamps) {
        return Segments.evaluateAt(decompressed, timestamps);
    }

    /**
     * Same as {@link #evaluateAt}, but only the values, as an array: {@code values[i]} is the value at
     * {@code timestamps[i]}.
     *
     * @param decompressed the decompressed series
     * @param timestamps timestamps in ascending order
     * @return one value per timestamp
     * @throws IllegalArgumentException if the timestamps are not ascending
     */
    public static double[] valuesAt(DecompressedTimeSeries decompressed, long[] timestamps) {
        return Segments.valuesAt(decompressed, timestamps);
    }

    /**
     * Value of the decompressed series at time t (binary search for the segment). For many ascending timestamps,
     * {@link #evaluateAt} is faster.
     *
     * @param decompressed the decompressed series
     * @param t the time
     * @return the point at time t
     */
    public static Point pointAt(DecompressedTimeSeries decompressed, long t) {
        return Segments.pointAt(decompressed, t);
    }

    /**
     * Reconstructs the series without any external information, as the vertices of a piecewise-linear function:
     * at the first and last timestamp, and where segments start and end.
     *
     * @param decompressed the decompressed series
     * @return the vertices, in time order
     */
    public static List<Point> reconstructTimeSeries(DecompressedTimeSeries decompressed) {
        return Segments.reconstruct(decompressed);
    }

    /** The value rounded to the nearest multiple of epsilon: the starting value of a segment. */
    private double quantization(double value) {
        return Math.round(value / epsilon) * epsilon;
    }

    /** Builds the segments (each starting at its first value rounded to a multiple of epsilon) and merges them. */
    private List<Segment> compress(long[] timestamps, double[] values) {
        List<Segment> segments = new ArrayList<>();
        int currentIdx = 0;
        while (currentIdx < timestamps.length)
            currentIdx = Segments.create(currentIdx, timestamps, values, segments, quantization(values[currentIdx]), epsilon);

        List<Segment> merged = new ArrayList<>();
        Segments.mergePerB(segments, merged, merged); // Sim-Piece keeps merged and single segments together
        return merged;
    }

    private DecompressedTimeSeries decompressed() {
        return new DecompressedTimeSeries(segments, firstTimeStamp, lastTimeStamp);
    }

    /**
     * Binary: epsilon (8 bytes), the first timestamp, the time span, and the merged segments (see
     * {@link Segments#writePerB}); all numbers but epsilon and the slopes are variable-byte. Compressed with Zstandard,
     * unless {@code zstd} is false.
     */
    private byte[] toByteArray(boolean zstd) {
        byte[] buffer = new byte[64 + 64 * segments.size()]; // per segment: at most a float and four variable-byte numbers
        int pos = Segments.writeEpsilon(epsilon, buffer, 0);
        pos = VariableByteEncoder.writeSigned(firstTimeStamp, buffer, pos);
        pos = VariableByteEncoder.write(lastTimeStamp - firstTimeStamp, buffer, pos);
        pos = Segments.writePerB(segments, epsilon, 0, firstTimeStamp, buffer, pos);

        return Envelope.wrap(Arrays.copyOf(buffer, pos), zstd);
    }

    /** Reads what {@link #toByteArray} wrote. */
    private void readByteArray(byte[] input) throws IOException {
        byte[] binary = Envelope.unwrap(input);
        int[] pos = {0};
        try {
            epsilon = Segments.readEpsilon(binary, pos);
            firstTimeStamp = VariableByteEncoder.readSigned(binary, pos);
            lastTimeStamp = firstTimeStamp + VariableByteEncoder.read(binary, pos);
            segments = Segments.readPerB(binary, pos, epsilon, 0, firstTimeStamp);
        } catch (RuntimeException e) { // reading past the end, impossible values, ...
            throw new IOException("Corrupted or truncated binary", e);
        }
        if (pos[0] != binary.length) throw new IOException("Corrupted binary: unexpected data after the end");
    }
}
