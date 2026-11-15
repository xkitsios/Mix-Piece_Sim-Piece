package io.github.xkitsios.piece;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestEdgeCases {
    /**
     * Every original point is decompressed within epsilon. Points exactly on the bound may exceed it by double
     * rounding (relative 1e-15), hence the 1e-9 relative slack.
     */
    private static void assertWithinBound(List<Point> original, DecompressedTimeSeries decompressed, double epsilon) {
        long[] timestamps = original.stream().mapToLong(Point::timestamp).toArray();
        List<Point> values = Segments.evaluateAt(decompressed, timestamps);
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).timestamp(), values.get(i).timestamp());
            assertEquals(original.get(i).value(), values.get(i).value(), epsilon * (1 + 1e-9), "timestamp " + timestamps[i]);
            assertEquals(values.get(i), Segments.pointAt(decompressed, timestamps[i])); // same as evaluateAt
        }
        assertEquals(original.get(0).timestamp(), decompressed.firstTimestamp());
        assertEquals(original.get(original.size() - 1).timestamp(), decompressed.lastTimestamp());
    }

    /**
     * A series that compresses very well: the encoded binary is many times larger than its Zstandard output, so the
     * decompressed size cannot be guessed from the compressed one.
     */
    @Test
    public void highlyCompressibleSeries() throws Exception {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) points.add(new Point(i, (i / 1000) % 2));
        double epsilon = 0.1;

        assertWithinBound(points, SimPiece.decompress(SimPiece.compress(points, epsilon)), epsilon);
        assertWithinBound(points, MixPiece.decompress(MixPiece.compress(points, epsilon)), epsilon);
    }

    /** The bound holds for both algorithms, with and without Zstandard. */
    private static void assertBoundHolds(List<Point> points, double epsilon) throws Exception {
        for (boolean zstd : new boolean[]{true, false}) {
            assertWithinBound(points, SimPiece.decompress(SimPiece.compress(points, epsilon, zstd)), epsilon);
            assertWithinBound(points, MixPiece.decompress(MixPiece.compress(points, epsilon, zstd)), epsilon);
        }
    }

    /** valuesAt gives the values of evaluateAt; equal decompressions are equal; toString names the time range. */
    @Test
    public void valuesAtEqualsAndToString() throws Exception {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) points.add(new Point(50L + 2L * i, Math.sin(i / 12.0)));
        long[] timestamps = points.stream().mapToLong(Point::timestamp).toArray();
        byte[] binary = MixPiece.compress(points, 0.01);
        DecompressedTimeSeries a = MixPiece.decompress(binary), b = MixPiece.decompress(binary.clone());
        double[] values = MixPiece.valuesAt(a, timestamps);
        List<Point> evaluated = MixPiece.evaluateAt(a, timestamps);
        for (int i = 0; i < timestamps.length; i++) assertEquals(evaluated.get(i).value(), values[i]);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, MixPiece.decompress(MixPiece.compress(points, 0.02)));
        assertNotEquals(a, SimPiece.decompress(SimPiece.compress(points, 0.01)));
        assertEquals("DecompressedTimeSeries[firstTimestamp=50, lastTimestamp=2048, segments=" + a.segmentCount() + "]", a.toString());
    }

    /** Arrays give the same binary as a list of points; invalid arrays are rejected. */
    @Test
    public void arrays() throws Exception {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) points.add(new Point(1_000L + 3L * i, Math.cos(i / 25.0)));
        long[] timestamps = points.stream().mapToLong(Point::timestamp).toArray();
        double[] values = points.stream().mapToDouble(Point::value).toArray();
        for (boolean zstd : new boolean[]{true, false}) {
            assertArrayEquals(SimPiece.compress(points, 0.01, zstd), SimPiece.compress(timestamps, values, 0.01, zstd));
            assertArrayEquals(MixPiece.compress(points, 0.01, zstd), MixPiece.compress(timestamps, values, 0.01, zstd));
        }
        assertArrayEquals(MixPiece.compress(points, 0.01), MixPiece.compress(timestamps, values, 0.01));
        for (long[] t : new long[][]{null, {1, 2}}) {
            double[] v = t == null ? new double[]{1, 2, 3} : new double[]{1, 2, 3};
            assertThrows(IllegalArgumentException.class, () -> SimPiece.compress(t, v, 0.1));
            assertThrows(IllegalArgumentException.class, () -> MixPiece.compress(t, v, 0.1));
        }
        assertThrows(IllegalArgumentException.class, () -> MixPiece.compress(new long[0], new double[0], 0.1));
        assertThrows(IllegalArgumentException.class, () -> MixPiece.compress(new long[]{1}, null, 0.1));
    }

    /** Without Zstandard: the same segments, a larger binary; any List works, also one without random access. */
    @Test
    public void withoutZstdAndAnyList() throws Exception {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) points.add(new Point(i, Math.sin(i / 30.0) + 0.01 * (i % 7)));
        long[] timestamps = points.stream().mapToLong(Point::timestamp).toArray();
        for (int k = 0; k < 2; k++) {
            boolean mix = k == 1;
            byte[] zstd = mix ? MixPiece.compress(points, 0.01) : SimPiece.compress(points, 0.01);
            byte[] raw = mix ? MixPiece.compress(points, 0.01, false) : SimPiece.compress(points, 0.01, false);
            byte[] linked = mix ? MixPiece.compress(new java.util.LinkedList<>(points), 0.01) : SimPiece.compress(new java.util.LinkedList<>(points), 0.01);
            assertTrue(raw.length > zstd.length);
            assertArrayEquals(zstd, linked);
            DecompressedTimeSeries a = mix ? MixPiece.decompress(zstd) : SimPiece.decompress(zstd);
            DecompressedTimeSeries b = mix ? MixPiece.decompress(raw) : SimPiece.decompress(raw);
            assertEquals(MixPiece.evaluateAt(a, timestamps), MixPiece.evaluateAt(b, timestamps));
        }
    }

    @Test
    public void invalidInputIsRejected() {
        List<List<Point>> invalid = new ArrayList<>();
        invalid.add(new ArrayList<>());                                                       // empty
        invalid.add(new ArrayList<>(Arrays.asList(new Point(0, 1), null)));                   // null point
        invalid.add(Arrays.asList(new Point(0, 1), new Point(1, 2), new Point(1, 3)));        // repeated timestamp
        invalid.add(Arrays.asList(new Point(0, 1), new Point(2, 2), new Point(1, 3)));        // decreasing timestamp
        invalid.add(Arrays.asList(new Point(0, 1), new Point(1, Double.NaN)));                // NaN value
        invalid.add(Arrays.asList(new Point(0, 1), new Point(1, Double.POSITIVE_INFINITY)));  // infinite value
        invalid.add(Arrays.asList(new Point(Long.MIN_VALUE, 1), new Point(Long.MAX_VALUE, 2))); // span beyond a long
        invalid.add(Arrays.asList(new Point(0, 1e16), new Point(1, 1e16)));                   // value / epsilon >= 2^51
        for (List<Point> points : invalid) {
            assertThrows(IllegalArgumentException.class, () -> SimPiece.compress(points, 0.1));
            assertThrows(IllegalArgumentException.class, () -> MixPiece.compress(points, 0.1));
        }
        List<Point> valid = Arrays.asList(new Point(0, 1), new Point(1, 2));
        for (double error : new double[]{0, -0.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> SimPiece.compress(valid, error));
            assertThrows(IllegalArgumentException.class, () -> MixPiece.compress(valid, error));
        }
    }

    private interface Decompressor {
        void decompress(byte[] binary) throws Exception;
    }

    /**
     * A binary that is not valid fails with an IOException, never with a wrong result, null or another exception:
     * random bytes, a truncated binary, and every truncation of the content inside a valid Zstandard frame.
     */
    @Test
    public void invalidBinaryFails() throws Exception {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) points.add(new Point(i, Math.sin(i / 10.0)));
        byte[][] valid = {SimPiece.compress(points, 0.01), MixPiece.compress(points, 0.01)};
        Decompressor[] decompressors = {SimPiece::decompress, MixPiece::decompress};
        for (int k = 0; k < 2; k++) {
            Decompressor decompressor = decompressors[k];
            List<byte[]> invalid = new ArrayList<>(Arrays.asList(new byte[0], new byte[]{1, 2, 3},
                    Arrays.copyOf(valid[k], valid[k].length / 2)));
            byte[] content = Envelope.unwrap(valid[k]);
            for (boolean zstd : new boolean[]{true, false}) {
                for (int length = 0; length < content.length; length++)
                    invalid.add(Envelope.wrap(Arrays.copyOf(content, length), zstd));
                invalid.add(Envelope.wrap(Arrays.copyOf(content, content.length + 1), zstd)); // data after the end
            }
            for (byte[] binary : invalid)
                assertThrows(IOException.class, () -> decompressor.decompress(binary), "length " + binary.length);
        }
    }

    /** Timestamps anywhere in the range of a long: negative, beyond 2^31 and 2^32 (epoch milliseconds). */
    @Test
    public void anyTimestamps() throws Exception {
        for (long start : new long[]{-10, 0, 3_000_000_000L, 1_700_000_000_000L, Long.MIN_VALUE / 2}) {
            List<Point> points = new ArrayList<>();
            for (int i = 0; i < 200; i++) points.add(new Point(start + i, Math.sin(i / 10.0)));
            assertBoundHolds(points, 0.01);
        }
    }

    @Test
    public void formatVersionIsWrittenAndChecked() throws Exception {
        List<Point> points = Arrays.asList(new Point(0, 1), new Point(1, 2), new Point(2, 4));
        for (byte[] binary : new byte[][]{SimPiece.compress(points, 0.1), MixPiece.compress(points, 0.1)}) {
            assertEquals(Envelope.FORMAT_VERSION, binary[0]);
            byte[] otherVersion = binary.clone();
            otherVersion[0] = (byte) (Envelope.FORMAT_VERSION + 1);
            assertThrows(IOException.class, () -> SimPiece.decompress(otherVersion));
            assertThrows(IOException.class, () -> MixPiece.decompress(otherVersion));
        }
    }

    /** The reconstructed vertices, interpolated linearly, give the decompressed series at every timestamp. */
    @Test
    public void reconstructedSeriesMatchesEvaluation() throws Exception {
        Random random = new Random(3);
        List<Point> points = new ArrayList<>();
        long t = 100;
        for (int i = 0; i < 500; i++) {
            t += 1 + random.nextInt(20);
            points.add(new Point(t, Math.sin(i / 15.0) + 0.05 * random.nextGaussian()));
        }
        for (DecompressedTimeSeries decompressed : new DecompressedTimeSeries[]{
                SimPiece.decompress(SimPiece.compress(points, 0.05)), MixPiece.decompress(MixPiece.compress(points, 0.05))}) {
            List<Point> vertices = MixPiece.reconstructTimeSeries(decompressed);
            assertEquals(decompressed.firstTimestamp(), vertices.get(0).timestamp());
            assertEquals(decompressed.lastTimestamp(), vertices.get(vertices.size() - 1).timestamp());
            long[] all = new long[(int) (decompressed.lastTimestamp() - decompressed.firstTimestamp() + 1)];
            for (int i = 0; i < all.length; i++) all[i] = decompressed.firstTimestamp() + i;
            List<Point> expected = MixPiece.evaluateAt(decompressed, all);
            int v = 0;
            for (Point point : expected) {
                while (vertices.get(v + 1).timestamp() < point.timestamp()) v++;
                Point a = vertices.get(v), b = vertices.get(v + 1);
                double interpolated = point.timestamp() == a.timestamp() ? a.value()
                        : a.value() + (b.value() - a.value()) * (point.timestamp() - a.timestamp()) / (b.timestamp() - a.timestamp());
                assertEquals(point.value(), interpolated, 1e-9);
            }
        }
    }

    /** Values much larger than epsilon times 2^31: the starting values are stored as longs. */
    @Test
    public void valuesBeyondIntTimesEpsilon() throws Exception {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) points.add(new Point(i, 1e6 + Math.sin(i / 10.0)));
        assertBoundHolds(points, 1e-4);
        List<Point> negative = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) negative.add(new Point(i, -1e6 + Math.sin(i / 10.0)));
        assertBoundHolds(negative, 1e-4);
    }

    /** Values much larger than epsilon: the starting values must not be rounded to float. */
    @Test
    public void largeValuesSmallEpsilon() throws Exception {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) points.add(new Point(i, 50_000 + 0.01 * Math.sin(i / 50.0)));
        assertBoundHolds(points, 0.001);
        assertBoundHolds(points, 0.0003);
    }

    /** Long segments with a narrow slope interval: the stored float slope must lie inside the interval. */
    @Test
    public void longTrends() throws Exception {
        Random random = new Random(1);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) points.add(new Point(i * 1_000L, 7.123456789e-4 * i + 1e-6 * random.nextGaussian()));
        assertBoundHolds(points, 1e-5);
        assertBoundHolds(points, 1e-4);
    }
}
