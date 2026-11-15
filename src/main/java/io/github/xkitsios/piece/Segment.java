package io.github.xkitsios.piece;

/**
 * A segment: it starts at {@code initTimestamp} with value {@code b}, and every slope in [aMin, aMax] keeps it within
 * the error bound of its points. It holds until the next segment starts.
 */
class Segment {
    private final long initTimestamp;
    private final double aMin;
    private final double aMax;
    private final double a;
    private final double b;

    /** A segment with a single slope, as decoded from a binary: the slope is already a float. */
    Segment(long initTimestamp, float a, double b) {
        this.initTimestamp = initTimestamp;
        this.aMin = a;
        this.aMax = a;
        this.a = a;
        this.b = b;
    }

    Segment(long initTimestamp, double aMin, double aMax, double b) {
        this.initTimestamp = initTimestamp;
        this.aMin = aMin;
        this.aMax = aMax;
        this.a = FloatSlope.within(aMin, aMax); // the slope is stored as a float: one inside [aMin, aMax]
        this.b = b;
    }

    long getInitTimestamp() {
        return initTimestamp;
    }

    double getAMin() {
        return aMin;
    }

    double getAMax() {
        return aMax;
    }

    /** The slope that is stored: a float inside [aMin, aMax]. */
    double getA() {
        return a;
    }

    double getB() {
        return b;
    }

    /** Value of the segment's line at time t. */
    double valueAt(long t) {
        return a * (t - initTimestamp) + b;
    }
}
