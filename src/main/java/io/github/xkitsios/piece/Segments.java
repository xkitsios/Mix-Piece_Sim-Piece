package io.github.xkitsios.piece;

import java.util.*;

/**
 * The steps that Sim-Piece and Mix-Piece share: building segments, merging segments with the same starting value,
 * evaluating the decompressed series, and the binary section of the merged segments.
 */
final class Segments {
    private Segments() {
    }

    /** By starting value, then by slope (a plain comparator: sorting is the main cost of merging). */
    private static final Comparator<Segment> BY_B_THEN_A = (x, y) -> {
        int byB = Double.compare(x.getB(), y.getB());
        return byB != 0 ? byB : Double.compare(x.getA(), y.getA());
    };

    /**
     * Builds the longest segment that starts at point {@code startIdx} with value {@code b} (already quantized) and
     * keeps every point within epsilon with a float slope. Adds it to {@code segments} and returns the index of the
     * first point it does not cover.
     */
    static int create(int startIdx, long[] timestamps, double[] values, List<Segment> segments, double b, double epsilon) {
        long initTimestamp = timestamps[startIdx];
        if (startIdx + 1 == timestamps.length) {
            segments.add(new Segment(initTimestamp, -Double.MAX_VALUE, Double.MAX_VALUE, b));
            return startIdx + 1;
        }
        double aMax = ((values[startIdx + 1] + epsilon) - b) / (timestamps[startIdx + 1] - initTimestamp);
        double aMin = ((values[startIdx + 1] - epsilon) - b) / (timestamps[startIdx + 1] - initTimestamp);
        if (!FloatSlope.containsFloat(aMin, aMax)) { // no float slope fits both points: a segment of one point
            segments.add(new Segment(initTimestamp, -Double.MAX_VALUE, Double.MAX_VALUE, b));
            return startIdx + 1;
        }
        if (startIdx + 2 == timestamps.length) {
            segments.add(new Segment(initTimestamp, aMin, aMax, b));
            return startIdx + 2;
        }

        for (int idx = startIdx + 2; idx < timestamps.length; idx++) {
            double upValue = values[idx] + epsilon;
            double downValue = values[idx] - epsilon;

            double upLim = aMax * (timestamps[idx] - initTimestamp) + b;
            double downLim = aMin * (timestamps[idx] - initTimestamp) + b;
            if ((downValue > upLim || upValue < downLim)) {
                segments.add(new Segment(initTimestamp, aMin, aMax, b));
                return idx;
            }

            double newAMax = aMax, newAMin = aMin;
            if (upValue < upLim)
                newAMax = Math.max((upValue - b) / (timestamps[idx] - initTimestamp), aMin);
            if (downValue > downLim)
                newAMin = Math.min((downValue - b) / (timestamps[idx] - initTimestamp), newAMax);
            if (!FloatSlope.containsFloat(newAMin, newAMax)) { // the point would leave no float slope: end here
                segments.add(new Segment(initTimestamp, aMin, aMax, b));
                return idx;
            }
            aMax = newAMax;
            aMin = newAMin;
        }
        segments.add(new Segment(initTimestamp, aMin, aMax, b));

        return timestamps.length;
    }

    /**
     * Merges segments with the same starting value b whose slope intervals share a float slope. Groups of two or
     * more segments go to {@code merged}, segments left alone to {@code unmerged} (the same list is allowed).
     * Sorts {@code segments} in place.
     */
    static void mergePerB(List<Segment> segments, List<Segment> merged, List<Segment> unmerged) {
        double aMinTemp = -Double.MAX_VALUE;
        double aMaxTemp = Double.MAX_VALUE;
        double b = Double.NaN;
        ArrayList<Long> timestamps = new ArrayList<>();

        segments.sort(BY_B_THEN_A);
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (b != segment.getB()) {
                close(timestamps, aMinTemp, aMaxTemp, b, merged, unmerged);
                timestamps.clear();
                timestamps.add(segment.getInitTimestamp());
                aMinTemp = segment.getAMin();
                aMaxTemp = segment.getAMax();
                b = segment.getB();
                continue;
            }
            if (segment.getAMin() <= aMaxTemp && segment.getAMax() >= aMinTemp
                    && FloatSlope.containsFloat(Math.max(aMinTemp, segment.getAMin()), Math.min(aMaxTemp, segment.getAMax()))) {
                timestamps.add(segment.getInitTimestamp());
                aMinTemp = Math.max(aMinTemp, segment.getAMin());
                aMaxTemp = Math.min(aMaxTemp, segment.getAMax());
            } else {
                if (timestamps.size() == 1) unmerged.add(segments.get(i - 1));
                else close(timestamps, aMinTemp, aMaxTemp, b, merged, unmerged);
                timestamps.clear();
                timestamps.add(segment.getInitTimestamp());
                aMinTemp = segment.getAMin();
                aMaxTemp = segment.getAMax();
            }
        }
        close(timestamps, aMinTemp, aMaxTemp, b, merged, unmerged);
    }

    /** Adds the current group: one segment per timestamp, sharing [aMin, aMax] and b. */
    private static void close(List<Long> timestamps, double aMin, double aMax, double b, List<Segment> merged, List<Segment> unmerged) {
        if (timestamps.size() == 1) unmerged.add(new Segment(timestamps.get(0), aMin, aMax, b));
        else for (long timestamp : timestamps) merged.add(new Segment(timestamp, aMin, aMax, b));
    }

    /** Same as {@code valuesAt} of MixPiece and SimPiece. */
    static double[] valuesAt(DecompressedTimeSeries decompressed, long[] timestamps) {
        List<Segment> segments = decompressed.segments();
        double[] values = new double[timestamps.length];
        int idx = 0;
        for (int i = 0; i < timestamps.length; i++) {
            long t = timestamps[i];
            if (i > 0 && t < timestamps[i - 1])
                throw new IllegalArgumentException("Timestamps must be ascending, got " + t + " after " + timestamps[i - 1]);
            while (idx + 1 < segments.size() && segments.get(idx + 1).getInitTimestamp() <= t) idx++;
            values[i] = segments.get(idx).valueAt(t);
        }

        return values;
    }

    /** Same as {@code evaluateAt} of MixPiece and SimPiece. */
    static List<Point> evaluateAt(DecompressedTimeSeries decompressed, long[] timestamps) {
        double[] values = valuesAt(decompressed, timestamps);
        List<Point> result = new ArrayList<>(timestamps.length);
        for (int i = 0; i < timestamps.length; i++) result.add(new Point(timestamps[i], values[i]));

        return result;
    }

    /** Same as {@code pointAt} of MixPiece and SimPiece: binary search for the segment. */
    static Point pointAt(DecompressedTimeSeries decompressed, long t) {
        List<Segment> segments = decompressed.segments();
        int lo = 0, hi = segments.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (segments.get(mid).getInitTimestamp() <= t) lo = mid;
            else hi = mid - 1;
        }
        return new Point(t, segments.get(lo).valueAt(t));
    }

    /**
     * Same as {@code reconstructTimeSeries} of MixPiece and SimPiece: vertices at the first and last timestamp, at every
     * segment start s and at s - 1 (timestamps are integers, so no time lies strictly between them).
     */
    static List<Point> reconstruct(DecompressedTimeSeries decompressed) {
        List<Segment> segments = decompressed.segments();
        long first = decompressed.firstTimestamp(), last = decompressed.lastTimestamp();
        long[] times = new long[2 * segments.size() + 2];
        int n = 0;
        times[n++] = first;
        times[n++] = last;
        for (Segment segment : segments) {
            long start = segment.getInitTimestamp();
            times[n++] = start;
            if (start > first) times[n++] = start - 1;
        }
        Arrays.sort(times, 0, n);
        int unique = 0;
        for (int i = 0; i < n; i++)
            if (unique == 0 || times[i] != times[unique - 1]) times[unique++] = times[i];

        return evaluateAt(decompressed, Arrays.copyOf(times, unique));
    }

    /** How a starting value b is stored: as a multiple of epsilon, relative to {@code minB}. */
    static long storedB(Segment segment, double epsilon, long minB) {
        return Math.round(segment.getB() / epsilon) - minB;
    }

    /**
     * Writes segments grouped by starting value b (stored as {@link #storedB}) and then by slope, both in increasing
     * order; per slope, the start timestamps in increasing order, the first relative to {@code firstTimestamp} and
     * the others as differences. All numbers are variable-byte. Returns the position after the section.
     */
    static int writePerB(List<Segment> segments, double epsilon, long minB, long firstTimestamp, byte[] buffer, int pos) {
        TreeMap<Long, TreeMap<Double, ArrayList<Long>>> input = new TreeMap<>();
        for (Segment segment : segments)
            input.computeIfAbsent(storedB(segment, epsilon, minB), k -> new TreeMap<>())
                    .computeIfAbsent(segment.getA(), k -> new ArrayList<>()).add(segment.getInitTimestamp());

        pos = VariableByteEncoder.write(input.size(), buffer, pos);
        if (input.isEmpty()) return pos;
        long previousB = input.firstKey();
        pos = VariableByteEncoder.writeSigned(previousB, buffer, pos); // negative for Sim-Piece (minB = 0)
        for (Map.Entry<Long, TreeMap<Double, ArrayList<Long>>> bSegments : input.entrySet()) {
            pos = VariableByteEncoder.write(bSegments.getKey() - previousB, buffer, pos);
            previousB = bSegments.getKey();
            pos = VariableByteEncoder.write(bSegments.getValue().size(), buffer, pos);
            for (Map.Entry<Double, ArrayList<Long>> aSegment : bSegments.getValue().entrySet()) {
                pos = FloatEncoder.write(aSegment.getKey().floatValue(), buffer, pos);
                Collections.sort(aSegment.getValue());
                pos = VariableByteEncoder.write(aSegment.getValue().size(), buffer, pos);
                long previousTS = firstTimestamp;
                for (long timestamp : aSegment.getValue()) {
                    pos = VariableByteEncoder.write(timestamp - previousTS, buffer, pos);
                    previousTS = timestamp;
                }
            }
        }

        return pos;
    }

    /** Reads what {@link #writePerB} wrote. */
    static ArrayList<Segment> readPerB(byte[] buffer, int[] pos, double epsilon, long minB, long firstTimestamp) {
        ArrayList<Segment> segments = new ArrayList<>();
        int numB = VariableByteEncoder.readCount(buffer, pos);
        if (numB == 0) return segments;
        long previousB = VariableByteEncoder.readSigned(buffer, pos);
        for (int i = 0; i < numB; i++) {
            long b = VariableByteEncoder.read(buffer, pos) + previousB;
            previousB = b;
            int numA = VariableByteEncoder.readCount(buffer, pos);
            for (int j = 0; j < numA; j++) {
                float a = FloatEncoder.read(buffer, pos);
                int numTimestamps = VariableByteEncoder.readCount(buffer, pos);
                long timestamp = firstTimestamp;
                for (int k = 0; k < numTimestamps; k++) {
                    timestamp += VariableByteEncoder.read(buffer, pos);
                    segments.add(new Segment(timestamp, a, (b + minB) * epsilon));
                }
            }
        }

        return segments;
    }

    /** Writes epsilon as the 8 bytes of a double, little-endian. Returns the position after it. */
    static int writeEpsilon(double epsilon, byte[] buffer, int pos) {
        long bits = Double.doubleToLongBits(epsilon);
        for (int i = 0; i < 8; i++)
            buffer[pos++] = (byte) (bits >>> (i * 8));
        return pos;
    }

    /** Reads what {@link #writeEpsilon} wrote. */
    static double readEpsilon(byte[] buffer, int[] pos) {
        long bits = 0;
        for (int i = 0; i < 8; i++)
            bits |= (long) (buffer[pos[0]++] & 0xFF) << (i * 8);
        return Double.longBitsToDouble(bits);
    }
}
