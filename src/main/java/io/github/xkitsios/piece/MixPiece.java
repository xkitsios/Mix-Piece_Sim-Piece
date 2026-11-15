package io.github.xkitsios.piece;

import java.io.IOException;
import java.util.*;

/**
 * Mix-Piece: lossy compression of a time series with a maximum absolute error epsilon.
 * <p>
 * Like {@link SimPiece}, the series is split into disjoint linear segments that start at a multiple of epsilon. Each
 * segment tries both the multiple above and the one below its first value and keeps the longer. The segments are then
 * merged in two stages: first those with the same starting value (as in Sim-Piece), then, among the rest, those that
 * can share a slope even with different starting values. Segments that cannot be merged are stored alone. The result is
 * compressed with Zstandard.
 */
public final class MixPiece {
    // State of one compression or decompression: every call works on its own instance, so calls are thread-safe
    private ArrayList<Segment> perBSegments; // merged by starting value b (and slope)
    private ArrayList<Segment> perASegments; // merged by slope only, with different starting values
    private ArrayList<Segment> restSegments; // not merged

    private double epsilon;
    private long globalMinB = Long.MAX_VALUE; // smallest starting value / epsilon: starting values are stored relative to it
    private long firstTimeStamp;
    private long lastTimeStamp;

    private MixPiece() {
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

        MixPiece mixPiece = new MixPiece();
        mixPiece.epsilon = epsilon;
        mixPiece.firstTimeStamp = timestamps[0];
        mixPiece.lastTimeStamp = timestamps[timestamps.length - 1];
        mixPiece.merge(mixPiece.compress(timestamps, values));
        return mixPiece.toByteArray(zstd);
    }

    /**
     * Decompresses a binary produced by {@code compress}.
     *
     * @param binary a binary produced by {@code compress}
     * @return the decompressed series
     * @throws IOException if the binary is not a valid binary of this format version
     */
    public static DecompressedTimeSeries decompress(byte[] binary) throws IOException {
        MixPiece mixPiece = new MixPiece();
        mixPiece.readByteArray(binary);
        return mixPiece.decompressed();
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

    /** The value rounded up to a multiple of epsilon. */
    private double roundUp(double value) {
        return (long) Math.ceil(value / epsilon) * epsilon;
    }

    /** The value rounded down to a multiple of epsilon. */
    private double roundDown(double value) {
        return (long) Math.floor(value / epsilon) * epsilon;
    }

    /** How a starting value b is stored: as a multiple of epsilon, relative to {@link #globalMinB}. */
    private long storedB(Segment segment) {
        return Segments.storedB(segment, epsilon, globalMinB);
    }

    /**
     * Builds the segments: from each point, one segment starting at the multiple of epsilon above it and one below it;
     * the one that covers more points is kept (on a tie, the one starting at the nearest multiple).
     */
    private ArrayList<Segment> compress(long[] timestamps, double[] values) {
        ArrayList<Segment> segments = new ArrayList<>();
        int currentIdx = 0;
        while (currentIdx < timestamps.length) {
            double value = values[currentIdx];
            int currentCeilIdx = Segments.create(currentIdx, timestamps, values, segments, roundUp(value), epsilon);
            int currentFloorIdx = Segments.create(currentIdx, timestamps, values, segments, roundDown(value), epsilon);
            if (currentCeilIdx > currentFloorIdx) {
                segments.remove(segments.size() - 1);
                currentIdx = currentCeilIdx;
            } else if (currentCeilIdx < currentFloorIdx) {
                segments.remove(segments.size() - 2);
                currentIdx = currentFloorIdx;
            } else {
                double firstValue = values[currentIdx];
                if (Math.round(firstValue / epsilon) == Math.ceil(firstValue / epsilon))
                    segments.remove(segments.size() - 1);
                else segments.remove(segments.size() - 2);
                currentIdx = currentFloorIdx;
            }
            globalMinB = Math.min(globalMinB, Math.round(segments.get(segments.size() - 1).getB() / epsilon));
        }

        return segments;
    }

    /**
     * Merges segments whose slope intervals share a float slope, whatever their starting values: segments are sorted
     * by the lower end of their interval and grouped greedily. Groups go to {@code mergedSegments}, segments left alone
     * to {@code unmergedSegments}.
     */
    private void mergeAll(ArrayList<Segment> segments, ArrayList<Segment> mergedSegments, ArrayList<Segment> unmergedSegments) {
        double aMinTemp = -Double.MAX_VALUE;
        double aMaxTemp = Double.MAX_VALUE;
        ArrayList<Double> bValues = new ArrayList<>();
        ArrayList<Long> timestamps = new ArrayList<>();

        segments.sort(Comparator.comparingDouble(Segment::getAMin));
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).getAMin() <= aMaxTemp && segments.get(i).getAMax() >= aMinTemp
                    && FloatSlope.containsFloat(Math.max(aMinTemp, segments.get(i).getAMin()), Math.min(aMaxTemp, segments.get(i).getAMax()))) {
                timestamps.add(segments.get(i).getInitTimestamp());
                aMinTemp = Math.max(aMinTemp, segments.get(i).getAMin());
                aMaxTemp = Math.min(aMaxTemp, segments.get(i).getAMax());
                bValues.add(segments.get(i).getB());
            } else {
                if (timestamps.size() == 1) unmergedSegments.add(segments.get(i - 1));
                else {
                    for (int j = 0; j < timestamps.size(); j++)
                        mergedSegments.add(new Segment(timestamps.get(j), aMinTemp, aMaxTemp, bValues.get(j)));
                }
                timestamps.clear();
                timestamps.add(segments.get(i).getInitTimestamp());
                aMinTemp = segments.get(i).getAMin();
                aMaxTemp = segments.get(i).getAMax();
                bValues.clear();
                bValues.add(segments.get(i).getB());
            }
        }
        if (!timestamps.isEmpty()) {
            if (timestamps.size() == 1)
                unmergedSegments.add(new Segment(timestamps.get(0), aMinTemp, aMaxTemp, bValues.get(0)));
            else {
                for (int i = 0; i < timestamps.size(); i++)
                    mergedSegments.add(new Segment(timestamps.get(i), aMinTemp, aMaxTemp, bValues.get(i)));
            }
        }
    }

    /** The two merging stages: by starting value, then by slope among the segments left alone. */
    private void merge(ArrayList<Segment> segments) {
        perBSegments = new ArrayList<>();
        perASegments = new ArrayList<>();
        restSegments = new ArrayList<>();
        ArrayList<Segment> temp = new ArrayList<>();

        Segments.mergePerB(segments, perBSegments, temp);
        if (!temp.isEmpty()) {
            mergeAll(temp, perASegments, restSegments);
        }
    }

    private DecompressedTimeSeries decompressed() {
        List<Segment> segments = new ArrayList<>();
        segments.addAll(perBSegments);
        segments.addAll(perASegments);
        segments.addAll(restSegments);
        return new DecompressedTimeSeries(segments, firstTimeStamp, lastTimeStamp);
    }

    /**
     * Per shared slope (in increasing order): the slope, the number of segments, and per segment (sorted by starting
     * value) the starting value as a difference and the start timestamp relative to the first timestamp.
     */
    private int toByteArrayPerASegments(ArrayList<Segment> segments, byte[] buffer, int pos) {
        TreeMap<Double, ArrayList<Segment>> input = new TreeMap<>();
        for (Segment segment : segments)
            input.computeIfAbsent(segment.getA(), k -> new ArrayList<>()).add(segment);

        pos = VariableByteEncoder.write(input.size(), buffer, pos);
        for (Map.Entry<Double, ArrayList<Segment>> aSegments : input.entrySet()) {
            pos = FloatEncoder.write(aSegments.getKey().floatValue(), buffer, pos);
            pos = VariableByteEncoder.write(aSegments.getValue().size(), buffer, pos);
            aSegments.getValue().sort(Comparator.comparingDouble(Segment::getB));
            long previousB = storedB(aSegments.getValue().get(0));
            pos = VariableByteEncoder.write(previousB, buffer, pos);
            for (Segment segment : aSegments.getValue()) {
                pos = VariableByteEncoder.write(storedB(segment) - previousB, buffer, pos);
                previousB = storedB(segment);
                pos = VariableByteEncoder.write(segment.getInitTimestamp() - firstTimeStamp, buffer, pos);
            }
        }

        return pos;
    }

    /**
     * Segments left alone, sorted by starting value: the starting value as a difference, the slope, and the start
     * timestamp relative to the first timestamp.
     */
    private int toByteArrayRestSegments(ArrayList<Segment> segments, byte[] buffer, int pos) {
        pos = VariableByteEncoder.write(segments.size(), buffer, pos);
        if (segments.isEmpty()) return pos;
        segments.sort(Comparator.comparingDouble(Segment::getB));
        long previousB = storedB(segments.get(0));
        pos = VariableByteEncoder.write(previousB, buffer, pos);
        for (Segment segment : segments) {
            pos = VariableByteEncoder.write(storedB(segment) - previousB, buffer, pos);
            previousB = storedB(segment);
            pos = FloatEncoder.write((float) segment.getA(), buffer, pos);
            pos = VariableByteEncoder.write(segment.getInitTimestamp() - firstTimeStamp, buffer, pos);
        }

        return pos;
    }

    /**
     * Binary: epsilon (8 bytes), the first timestamp, the time span, globalMinB, and the three groups of segments
     * (merged by starting value, merged by slope, alone); all numbers but epsilon and the slopes are variable-byte.
     * Compressed with Zstandard, unless {@code zstd} is false.
     */
    private byte[] toByteArray(boolean zstd) {
        int segments = perBSegments.size() + perASegments.size() + restSegments.size();
        byte[] buffer = new byte[64 + 64 * segments]; // per segment: at most a float and four variable-byte numbers
        int pos = Segments.writeEpsilon(epsilon, buffer, 0);
        pos = VariableByteEncoder.writeSigned(firstTimeStamp, buffer, pos);
        pos = VariableByteEncoder.write(lastTimeStamp - firstTimeStamp, buffer, pos);
        pos = VariableByteEncoder.writeSigned(globalMinB, buffer, pos);
        pos = Segments.writePerB(perBSegments, epsilon, globalMinB, firstTimeStamp, buffer, pos);
        pos = toByteArrayPerASegments(perASegments, buffer, pos);
        pos = toByteArrayRestSegments(restSegments, buffer, pos);

        return Envelope.wrap(Arrays.copyOf(buffer, pos), zstd);
    }

    /** Reads what {@link #toByteArrayPerASegments} wrote. */
    private ArrayList<Segment> readMergedPerASegments(byte[] buffer, int[] pos) {
        ArrayList<Segment> segments = new ArrayList<>();
        int numA = VariableByteEncoder.readCount(buffer, pos);
        for (int i = 0; i < numA; i++) {
            float a = FloatEncoder.read(buffer, pos);
            int numBT = VariableByteEncoder.readCount(buffer, pos);
            long previousB = VariableByteEncoder.read(buffer, pos);
            for (int j = 0; j < numBT; j++) {
                long b = VariableByteEncoder.read(buffer, pos) + previousB;
                previousB = b;
                long timestamp = firstTimeStamp + VariableByteEncoder.read(buffer, pos);
                segments.add(new Segment(timestamp, a, (b + globalMinB) * epsilon));
            }
        }

        return segments;
    }

    /** Reads what {@link #toByteArrayRestSegments} wrote. */
    private ArrayList<Segment> readUnmerged(byte[] buffer, int[] pos) {
        ArrayList<Segment> segments = new ArrayList<>();
        int num = VariableByteEncoder.readCount(buffer, pos);
        if (num == 0) return segments;
        long previousB = VariableByteEncoder.read(buffer, pos);
        for (int i = 0; i < num; i++) {
            long b = VariableByteEncoder.read(buffer, pos) + previousB;
            previousB = b;
            float a = FloatEncoder.read(buffer, pos);
            long timestamp = firstTimeStamp + VariableByteEncoder.read(buffer, pos);
            segments.add(new Segment(timestamp, a, (b + globalMinB) * epsilon));
        }

        return segments;
    }

    /** Reads what {@link #toByteArray} wrote. */
    private void readByteArray(byte[] input) throws IOException {
        byte[] binary = Envelope.unwrap(input);
        int[] pos = {0};
        try {
            epsilon = Segments.readEpsilon(binary, pos);
            firstTimeStamp = VariableByteEncoder.readSigned(binary, pos);
            lastTimeStamp = firstTimeStamp + VariableByteEncoder.read(binary, pos);
            globalMinB = VariableByteEncoder.readSigned(binary, pos);
            perBSegments = Segments.readPerB(binary, pos, epsilon, globalMinB, firstTimeStamp);
            perASegments = readMergedPerASegments(binary, pos);
            restSegments = readUnmerged(binary, pos);
        } catch (RuntimeException e) { // reading past the end, impossible values, ...
            throw new IOException("Corrupted or truncated binary", e);
        }
        if (pos[0] != binary.length) throw new IOException("Corrupted binary: unexpected data after the end");
    }
}
