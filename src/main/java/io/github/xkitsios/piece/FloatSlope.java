package io.github.xkitsios.piece;

/**
 * Slopes are stored as floats. A segment is within the error bound only with a slope inside its interval
 * [aMin, aMax], so a segment (or a group of merged segments) is valid only if its interval contains a float.
 */
final class FloatSlope {
    private FloatSlope() {
    }

    /** Whether [lower, upper] contains a value exactly representable as a float. */
    static boolean containsFloat(double lower, double upper) {
        // Fast path: in the normal range, floats near x are at most |x| * 2^-23 apart, so a wider interval contains one
        double scale = Math.max(Math.abs(lower), Math.abs(upper));
        if (scale >= Float.MIN_NORMAL && scale <= Float.MAX_VALUE && upper - lower > scale * 0x1p-22) return true;
        float f = (float) lower;
        if (f < lower) f = Math.nextUp(f);
        return f <= upper;
    }

    /**
     * The float closest to the angle bisector of [lower, upper] that lies inside the interval: the slope whose angle
     * is halfway between the angles of the two extreme lines. Callers guarantee the interval contains a float
     * ({@link #containsFloat}).
     */
    static double within(double lower, double upper) {
        float f = (float) Math.tan((Math.atan(lower) + Math.atan(upper)) / 2);
        if (f >= lower && f <= upper) return f;
        // Rounded out of the interval: the float inside it that is closest to that side
        if (f < lower) {
            float first = (float) lower;
            return first < lower ? Math.nextUp(first) : first;
        }
        float last = (float) upper;
        return last > upper ? Math.nextDown(last) : last;
    }
}
