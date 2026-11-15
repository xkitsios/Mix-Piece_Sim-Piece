package io.github.xkitsios.piece;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A decompressed time series: a piecewise-linear function of time. Evaluate it with {@code evaluateAt} or
 * {@code pointAt}, or turn it into points with {@code reconstructTimeSeries} (of {@link MixPiece} or {@link SimPiece}).
 */
public final class DecompressedTimeSeries {
    private final List<Segment> segments;
    private final long firstTimestamp;
    private final long lastTimestamp;

    /** Sorts the segments by start time; a segment holds until the next one starts. */
    DecompressedTimeSeries(List<Segment> segments, long firstTimestamp, long lastTimestamp) {
        segments.sort(Comparator.comparingLong(Segment::getInitTimestamp));
        this.segments = Collections.unmodifiableList(segments);
        this.firstTimestamp = firstTimestamp;
        this.lastTimestamp = lastTimestamp;
    }

    List<Segment> segments() {
        return segments;
    }

    /**
     * Timestamp of the first point of the original series.
     *
     * @return the first timestamp
     */
    public long firstTimestamp() {
        return firstTimestamp;
    }

    /**
     * Timestamp of the last point of the original series.
     *
     * @return the last timestamp
     */
    public long lastTimestamp() {
        return lastTimestamp;
    }

    /**
     * Number of stored line segments.
     *
     * @return the number of segments
     */
    public int segmentCount() {
        return segments.size();
    }

    /** Equal if both have the same time range and the same segments (start, slope and starting value). */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DecompressedTimeSeries other)) return false;
        if (firstTimestamp != other.firstTimestamp || lastTimestamp != other.lastTimestamp
                || segments.size() != other.segments.size()) return false;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i), t = other.segments.get(i);
            if (s.getInitTimestamp() != t.getInitTimestamp() || Double.compare(s.getA(), t.getA()) != 0
                    || Double.compare(s.getB(), t.getB()) != 0) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = Objects.hash(firstTimestamp, lastTimestamp);
        for (Segment s : segments)
            hash = 31 * hash + Objects.hash(s.getInitTimestamp(), s.getA(), s.getB());
        return hash;
    }

    @Override
    public String toString() {
        return "DecompressedTimeSeries[firstTimestamp=" + firstTimestamp + ", lastTimestamp=" + lastTimestamp
                + ", segments=" + segments.size() + "]";
    }
}
