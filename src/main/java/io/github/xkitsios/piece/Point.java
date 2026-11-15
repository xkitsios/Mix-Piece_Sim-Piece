package io.github.xkitsios.piece;

/**
 * A point of a time series: an integer timestamp (in any unit) and a value.
 *
 * @param timestamp timestamp, in any unit
 * @param value     value
 */
public record Point(long timestamp, double value) {
    /**
     * Same as {@link #timestamp()}.
     *
     * @return the timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Same as {@link #value()}.
     *
     * @return the value
     */
    public double getValue() {
        return value;
    }
}
