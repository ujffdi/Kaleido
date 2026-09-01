# Compatibility and performance gates

## Exact matrix

The MVP candidate has two mandatory rows; both require Linux x86_64, JDK 17,
Build Tools 36.0.0, compileSdk 36, and AGP built-in Kotlin:

| Row | AGP | Gradle | Status meaning |
| --- | --- | --- | --- |
| A3 | 9.2.0 | 9.4.1 | Mandatory only after all seven compatibility inputs and device gates pass |
| A4 | 9.3.2 | 9.5.0 | Mandatory only after all seven compatibility inputs and device gates pass |

The seven compatibility inputs are the Java Safe, Kotlin Safe, and Full+Compose
Release Fixtures; the Safe and Full+Compose Sample Apps; migrated Sana; and exhaustive
boundary/error coverage. A row is supported only when the exact plugin/marker bytes
pass the full Linux and controlled-device record.
Experiments on another host or a future AGP/Gradle are nonblocking forward
evidence, never a supported row. At present no public version is declared
supported because the mandatory Linux/Sana records have not been produced.

## Performance and size

Dedicated Linux x86_64 workers perform two warmups and five samples; the median
is compared with the same Consumer Project without Kaleido. Limits are:

| Measurement | Maximum overhead/growth |
| --- | --- |
| Safe clean | larger of 20% or 45 s |
| Full + default Compose clean | larger of 30% or 90 s |
| Warm no-clean | larger of 10% or 15 s |
| Peak memory | larger of 25% or 512 MiB |
| Sana Safe / Full / Compose 512 | larger of 1%/1 MiB, 2%/2 MiB, 5%/5 MiB |
| Sample Safe / Full | 2 MiB / 4 MiB |
| Plugin JAR / new resolved dependencies | 10 MiB / 50 MiB |

`PerformanceGateCli` binds raw measurements, environment, candidate digest,
baselines, thresholds, complexity result, and verdict. Threshold failures require
a new candidate and cannot be waived.
