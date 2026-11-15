package io.github.xkitsios.piece.benchmarks;

import io.github.xkitsios.piece.MixPiece;
import io.github.xkitsios.piece.Point;
import io.github.xkitsios.piece.SimPiece;
import io.github.xkitsios.piece.util.TimeSeries;
import io.github.xkitsios.piece.util.TimeSeriesReader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestPLA {
    private long SimPiece(List<Point> ts, double epsilon) throws Exception {
        byte[] binary = SimPiece.compress(ts, epsilon);
        long[] timestamps = ts.stream().mapToLong(Point::timestamp).toArray();
        List<Point> tsDecompressed = SimPiece.evaluateAt(SimPiece.decompress(binary), timestamps);
        for (int idx = 0; idx < ts.size(); idx++)
            assertEquals(ts.get(idx).value(), tsDecompressed.get(idx).value(), epsilon * (1 + 1e-9), "Value did not match for timestamp " + timestamps[idx]);

        return binary.length;
    }

    private long MixPiece(List<Point> ts, double epsilon) throws Exception {
        byte[] binary = MixPiece.compress(ts, epsilon);
        long[] timestamps = ts.stream().mapToLong(Point::timestamp).toArray();
        List<Point> tsDecompressed = MixPiece.evaluateAt(MixPiece.decompress(binary), timestamps);
        for (int idx = 0; idx < ts.size(); idx++)
            assertEquals(ts.get(idx).value(), tsDecompressed.get(idx).value(), epsilon * (1 + 1e-9), "Value did not match for timestamp " + timestamps[idx]);

        return binary.length;
    }


    private void run(String[] filenames, double epsilonStart, double epsilonStep, double epsilonEnd) throws Exception {
        for (String filename : filenames) {
            System.out.println(filename);
            String delimiter = ",";
            TimeSeries ts = TimeSeriesReader.getTimeSeries(getClass().getResourceAsStream(filename), delimiter, true);

            System.out.println("Mix-Piece");
            for (double epsilonPct = epsilonStart; epsilonPct <= epsilonEnd; epsilonPct += epsilonStep)
                System.out.printf("Epsilon: %.2f%%\tCompression Ratio (8 bytes per point): %.3f\n", epsilonPct * 100, (double) ts.size / MixPiece(ts.data, ts.range * epsilonPct));

            System.out.println("Sim-Piece");
            for (double epsilonPct = epsilonStart; epsilonPct <= epsilonEnd; epsilonPct += epsilonStep)
                System.out.printf("Epsilon: %.2f%%\tCompression Ratio (8 bytes per point): %.3f\n", epsilonPct * 100, (double) ts.size / SimPiece(ts.data, ts.range * epsilonPct));

            System.out.println();
        }
    }


    @Test
    public void TestCRAndTime() throws Exception {
        double epsilonStart = 0.005;
        double epsilonStep = 0.005;
        double epsilonEnd = 0.05;

        String[] filenames = {
                "/Cricket.csv.gz",
                "/FaceFour.csv.gz",
                "/Lightning.csv.gz",
                "/MoteStrain.csv.gz",
                "/Wafer.csv.gz",
                "/WindSpeed.csv.gz",
                "/WindDirection.csv.gz",
        };

        run(filenames, epsilonStart, epsilonStep, epsilonEnd);

        epsilonStart = 0.0005;
        epsilonStep = 0.0005;
        epsilonEnd = 0.0051;

        filenames = new String[]{
                "/Pressure.csv.gz",
                "/BTCUSD.csv.gz",
                "/ETHUSD.csv.gz",
                "/SPX.csv.gz",
                "/STOXX50E.csv.gz"
        };

        run(filenames, epsilonStart, epsilonStep, epsilonEnd);
    }
}
