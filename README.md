# Mix-Piece / Sim-Piece

Mix-Piece and Sim-Piece are lossy compression algorithms for time series with a guaranteed maximum absolute error ε.

They approximate a time series with disjoint piecewise-linear segments. Each segment starts at a value rounded to a multiple of ε and keeps the range of slopes that fit all its points. Segments that can share a slope are then merged and stored once, and the result is compressed with [Zstandard](https://github.com/facebook/zstd) (through [zstd-jni](https://github.com/luben/zstd-jni)); this last step is optional and can be turned off. The representation is considerably smaller than an optimal piecewise-linear approximation with the same error.

Mix-Piece builds upon Sim-Piece: it handles time series with trends better, compresses faster and creates segments with smaller error. **For the best results, use Mix-Piece.** 

For the same space, Mix-Piece and Sim-Piece also have smaller mean absolute and root mean squared errors than other piecewise-linear techniques (see the papers).

## Usage

Requires Java 17+. With Maven:

```xml
<dependency>
    <groupId>io.github.xkitsios</groupId>
    <artifactId>mix-piece_sim-piece</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Mix-Piece

```java
import io.github.xkitsios.piece.*;
import java.nio.file.*;

// A time series from a CSV file with lines "t,value" (timestamps strictly increasing)
List<Point> points = Files.readAllLines(Path.of("series.csv")).stream()
        .map(line -> line.split(","))
        .map(f -> new Point(Long.parseLong(f[0]), Double.parseDouble(f[1])))
        .toList();

double epsilon = 0.1;  // maximum absolute error

// Compress and save
Files.write(Path.of("series.mixpiece"), MixPiece.compress(points, epsilon));

// Load and decompress
DecompressedTimeSeries decompressed = MixPiece.decompress(Files.readAllBytes(Path.of("series.mixpiece")));

// Values at given timestamps (for example the original ones)
List<Point> atSamples = MixPiece.evaluateAt(decompressed, points.stream().mapToLong(Point::timestamp).toArray());

// Value at a single time
Point at = MixPiece.pointAt(decompressed, points.get(0).timestamp());

// The series as a polyline, without needing the original timestamps
List<Point> vertices = MixPiece.reconstructTimeSeries(decompressed);
```

### Sim-Piece

The same methods, here with the time series as arrays:

```java
import io.github.xkitsios.piece.*;
import java.nio.file.*;

// A time series from a CSV file with lines "t,value" (timestamps strictly increasing)
List<String> lines = Files.readAllLines(Path.of("series.csv"));
long[] timestamps = lines.stream().mapToLong(line -> Long.parseLong(line.split(",")[0])).toArray();
double[] values = lines.stream().mapToDouble(line -> Double.parseDouble(line.split(",")[1])).toArray();

double epsilon = 0.1;  // maximum absolute error

// Compress and save
Files.write(Path.of("series.simpiece"), SimPiece.compress(timestamps, values, epsilon));

// Load, decompress, and get the values at the original timestamps as an array
DecompressedTimeSeries decompressed = SimPiece.decompress(Files.readAllBytes(Path.of("series.simpiece")));
double[] decompressedValues = SimPiece.valuesAt(decompressed, timestamps);
```

Timestamps are whole numbers (Java `long`) in any unit, e.g. seconds or milliseconds. Every decompressed value at an original timestamp is within ε of the original
one. Compression and decompression are thread-safe.

> `compress(points, epsilon, false)` skips the final Zstandard step, for a slightly larger binary without the native
> Zstandard library.

## Building

Requires Java 17+ and Maven.

```sh
mvn test      # runs the tests
mvn install   # installs the jar in the local Maven repository
```

## Citation

Mix-Piece and Sim-Piece are described in:

- **[Flexible Grouping of Linear Segments for Highly Accurate Lossy Compression of Time Series Data](https://xkitsios.github.io/assets/pdf/mixpiece-vldbj24.pdf)** (2024)  
  X. Kitsios, P. Liakos, K. Papakonstantinopoulou, Y. Kotidis  
  _The VLDB Journal_, Vol. 33, No. 5, pp. 1569-1589  
  DOI: [10.1007/s00778-024-00862-z](https://doi.org/10.1007/s00778-024-00862-z)

- **[Sim-Piece: Highly Accurate Piecewise Linear Approximation through Similar Segment Merging](https://xkitsios.github.io/assets/pdf/simpiece-pvldb23.pdf)** (2023)  
  X. Kitsios, P. Liakos, K. Papakonstantinopoulou, Y. Kotidis  
  _Proceedings of the VLDB Endowment_, Vol. 16, No. 8, pp. 1910-1922  
  DOI: [10.14778/3594512.3594521](https://doi.org/10.14778/3594512.3594521)

If you use Mix-Piece or Sim-Piece in your work, please cite them:

```bibtex
@article{mix-piece,
  author = {Kitsios, Xenophon and Liakos, Panagiotis and Papakonstantinopoulou, Katia and Kotidis, Yannis},
  title = {Flexible grouping of linear segments for highly accurate lossy compression of time series data},
  journal = {The VLDB Journal},
  year = {2024},
  month = sep,
  day = {01},
  volume = {33},
  number = {5},
  pages = {1569--1589},
  issn = {0949-877X},
  doi = {10.1007/s00778-024-00862-z},
  url = {https://doi.org/10.1007/s00778-024-00862-z},
}

@article{sim-piece,
  author = {Kitsios, Xenophon and Liakos, Panagiotis and Papakonstantinopoulou, Katia and Kotidis, Yannis},
  title = {Sim-Piece: Highly Accurate Piecewise Linear Approximation through Similar Segment Merging},
  journal = {Proc. VLDB Endow.},
  year = {2023},
  month = apr,
  volume = {16},
  number = {8},
  pages = {1910--1922},
  numpages = {13},
  issue_date = {April 2023},
  publisher = {VLDB Endowment},
  issn = {2150-8097},
  doi = {10.14778/3594512.3594521},
  url = {https://doi.org/10.14778/3594512.3594521},
}
```

## See also

- [DimWeaver](https://github.com/xkitsios/DimWeaver): lossy compression of trajectories with a maximum error.
- [CHIMP](https://github.com/panagiotisl/chimp): lossless compression of floating-point time series.

## Contact

- [Xenophon Kitsios](https://xkitsios.github.io/)
- [Panagiotis Liakos](https://cgi.di.uoa.gr/~p.liakos/)
- [Katia Papakonstantinopoulou](https://www2.aueb.gr/users/katia/)
- [Yannis Kotidis](https://pages.aueb.gr/users/kotidis/)

## License

Apache License 2.0, see [LICENSE](LICENSE).
