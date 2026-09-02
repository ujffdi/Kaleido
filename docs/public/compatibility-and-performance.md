# Compatibility and performance gates

## Exact matrix

The 0.1.0 candidate has one mandatory row on the release host. It requires
macOS arm64, JDK 17, Build Tools 36.0.0, compileSdk 36, and AGP built-in Kotlin:

| Row | AGP | Gradle | Status meaning |
| --- | --- | --- | --- |
| A3 | 9.2.0 | 9.4.1 | Supported after all five compatibility inputs and release gates pass on macOS arm64 |

The five compatibility inputs are the Java Safe, Kotlin Safe, and Full+Compose
Release Fixtures; the comprehensive Sample App; and exhaustive boundary/error
coverage. A row is supported only when the exact plugin/marker bytes pass the
packaged-marker build and static bundletool validation. Linux, Windows, AGP 9.3.2,
and device installation are nonblocking forward evidence and are not claimed
as supported by 0.1.0.

## Performance and size

The release macOS arm64 host performs two warmups and five samples; the median is
compared with the same Sample App source without Kaleido. Limits are:

| Measurement | Maximum overhead/growth |
| --- | --- |
| Safe clean | larger of 20% or 45 s |
| Full + default Compose clean | larger of 30% or 90 s |
| Warm no-clean | larger of 10% or 15 s |
| Peak memory | larger of 25% or 512 MiB |
| Sample Safe / Full | 2 MiB / 4 MiB |
| Plugin JAR / new resolved dependencies | 10 MiB / 50 MiB |

`PerformanceGateCli` binds raw measurements, environment, candidate digest,
baselines, thresholds, complexity result, and verdict. Threshold failures require
a new candidate and cannot be waived.
