package io.github.xkitsios.piece;

import java.util.List;

/**
 * Checks the input of compress. Invalid input would otherwise be compressed silently into a wrong binary: for example
 * a repeated timestamp divides by zero when the slopes are computed.
 */
final class InputCheck {
    private InputCheck() {
    }

    /**
     * The timestamps of the points; with {@link #values}, the input of compress as arrays.
     *
     * @throws IllegalArgumentException if the list is null or empty, or a point is null
     */
    static long[] timestamps(List<Point> points) {
        if (points == null || points.isEmpty())
            throw new IllegalArgumentException("The time series must contain at least one point");
        long[] timestamps = new long[points.size()];
        int i = 0;
        for (Point point : points) { // one pass, also for lists without random access
            if (point == null) throw new IllegalArgumentException("Points must not be null");
            timestamps[i++] = point.timestamp();
        }
        return timestamps;
    }

    /** The values of the points (checked by {@link #timestamps}). */
    static double[] values(List<Point> points) {
        double[] values = new double[points.size()];
        int i = 0;
        for (Point point : points) values[i++] = point.value();
        return values;
    }

    /**
     * Largest |value| / epsilon: starting values are stored as integer multiples of epsilon, and a double holds every
     * such multiple exactly only up to 2^53 (2^51 leaves a margin for the rounding of value / epsilon).
     */
    static final double MAX_VALUE_OVER_EPSILON = 0x1p51;

    /**
     * @throws IllegalArgumentException if there are no points, the arrays have different lengths, a value is NaN or
     *                                  infinite, timestamps are not strictly increasing or span more than a long,
     *                                  epsilon is not finite and &gt; 0, or epsilon is too small for the values
     *                                  (|value| / epsilon &ge; 2^51)
     */
    static void check(long[] timestamps, double[] values, double epsilon) {
        if (timestamps == null || values == null || timestamps.length == 0)
            throw new IllegalArgumentException("The time series must contain at least one point");
        if (timestamps.length != values.length)
            throw new IllegalArgumentException("Timestamps and values must have the same length, got "
                    + timestamps.length + " and " + values.length);
        if (!(epsilon > 0) || epsilon == Double.POSITIVE_INFINITY)
            throw new IllegalArgumentException("The error must be finite and > 0, got " + epsilon);
        for (int i = 0; i < timestamps.length; i++) {
            if (!Double.isFinite(values[i]))
                throw new IllegalArgumentException("Values must be finite, got " + values[i] + " at timestamp " + timestamps[i]);
            if (Math.abs(values[i]) / epsilon >= MAX_VALUE_OVER_EPSILON)
                throw new IllegalArgumentException("Epsilon " + epsilon + " is too small for the value " + values[i]
                        + " (|value| / epsilon must be below 2^51, the precision of a double)");
            if (i > 0 && timestamps[i] <= timestamps[i - 1])
                throw new IllegalArgumentException("Timestamps must be strictly increasing, got " + timestamps[i] + " after " + timestamps[i - 1]);
        }
        try {
            Math.subtractExact(timestamps[timestamps.length - 1], timestamps[0]);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The time span of the series does not fit in a long");
        }
    }
}
