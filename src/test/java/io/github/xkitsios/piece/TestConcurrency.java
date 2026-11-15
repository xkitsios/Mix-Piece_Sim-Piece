package io.github.xkitsios.piece;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestConcurrency {
    private interface Codec {
        byte[] compress(List<Point> points, double epsilon) throws Exception;

        DecompressedTimeSeries decompress(byte[] binary) throws Exception;
    }

    private static final Codec SIM_PIECE = new Codec() {
        public byte[] compress(List<Point> points, double epsilon) throws Exception {
            return SimPiece.compress(points, epsilon);
        }

        public DecompressedTimeSeries decompress(byte[] binary) throws Exception {
            return SimPiece.decompress(binary);
        }
    };

    private static final Codec MIX_PIECE = new Codec() {
        public byte[] compress(List<Point> points, double epsilon) throws Exception {
            return MixPiece.compress(points, epsilon);
        }

        public DecompressedTimeSeries decompress(byte[] binary) throws Exception {
            return MixPiece.decompress(binary);
        }
    };

    private static List<Point> series(int seed) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) points.add(new Point(i, (seed % 2 == 0 ? -50 : 100) + 5 * Math.sin(i / (3.0 + seed))));
        return points;
    }

    /** Compressions and decompressions on many threads give the same results as one after the other. */
    private static void assertThreadSafe(Codec codec) throws Exception {
        double epsilon = 0.01;
        List<List<Point>> inputs = new ArrayList<>();
        List<byte[]> expected = new ArrayList<>();
        for (int seed = 0; seed < 8; seed++) {
            inputs.add(series(seed));
            expected.add(codec.compress(inputs.get(seed), epsilon));
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int k = 0; k < 400; k++) {
                int seed = k % inputs.size();
                results.add(executor.submit(() -> {
                    byte[] binary = codec.compress(inputs.get(seed), epsilon);
                    DecompressedTimeSeries decompressed = codec.decompress(binary);
                    return Arrays.equals(binary, expected.get(seed))
                            && decompressed.lastTimestamp() == inputs.get(seed).get(inputs.get(seed).size() - 1).timestamp();
                }));
            }
            for (Future<Boolean> result : results) assertTrue(result.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void simPieceIsThreadSafe() throws Exception {
        assertThreadSafe(SIM_PIECE);
    }

    @Test
    public void mixPieceIsThreadSafe() throws Exception {
        assertThreadSafe(MIX_PIECE);
    }

    /** The output depends only on the input, not on earlier calls (Mix-Piece kept its minimum b between calls). */
    @Test
    public void outputDoesNotDependOnEarlierCalls() throws Exception {
        for (Codec codec : List.of(SIM_PIECE, MIX_PIECE)) {
            byte[] first = codec.compress(series(1), 0.01);
            codec.decompress(codec.compress(series(0), 0.01)); // negative values
            assertArrayEquals(first, codec.compress(series(1), 0.01));
            assertEquals(series(1).size() - 1, codec.decompress(first).lastTimestamp());
        }
    }
}
