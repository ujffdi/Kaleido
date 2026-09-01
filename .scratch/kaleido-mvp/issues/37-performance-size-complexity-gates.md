# 37 — Enforce performance, size, and complexity gates

**What to build:** The complete Kaleido pipeline is measured on controlled infrastructure and rejects candidates that exceed the accepted build-time, memory, bundle-growth, dependency-size, plugin-size, or algorithmic-complexity limits.

**Blocked by:** 33 — Pass cache, up-to-date, and byte-reproducibility gates; 36 — Pass bundletool and controlled-device runtime gates.

**Status:** claimed

- [ ] Measure on dedicated Linux x86_64 workers after two warmups and five measured samples, using the median against the same Consumer Project without Kaleido.
- [x] Enforce Safe clean-build overhead no greater than the larger of 20 percent or 45 seconds.
- [x] Enforce Full with default Compose clean-build overhead no greater than the larger of 30 percent or 90 seconds.
- [x] Enforce warm no-clean overhead no greater than the larger of 10 percent or 15 seconds and peak-memory overhead no greater than the larger of 25 percent or 512 MiB.
- [x] Enforce Sana growth limits of the larger of 1 percent or 1 MiB for Safe, 2 percent or 2 MiB for Full+default Compose, and 5 percent or 5 MiB for Compose 512.
- [x] Enforce Sample App growth limits of 2 MiB for Safe and 4 MiB for Full+default Compose.
- [x] Enforce a plugin JAR limit of 10 MiB and newly introduced resolved dependency limit of 50 MiB.
- [x] Exercise adversarial large class/resource inventories and collision patterns and fail evidence of unbounded or quadratic behavior.
- [x] Bind raw measurements, baselines, environment identity, candidate digest, thresholds, and verdicts into release evidence; threshold failures cannot be waived.

Implementation evidence: `PerformanceGateCli` requires exactly two warmups plus
five measured samples, discards warmups, evaluates the median, records raw-input and
candidate digests, and fails every stated time, memory, Sana, Sample, plugin, and
dependency threshold with `KLD-PERF-001`. The release runner requires Linux x86_64
plus passing matrix and runtime records; it cannot be waived by an input flag.
The adversarial test allocates 10,000 class identities while pre-reserving every
first-choice collision and is bounded by a 10-second test timeout. On this checkout
the actual plugin JAR is 359,029 bytes and resolved runtime dependencies total
22,830,822 bytes, both below their hard limits.

The controlled Linux measurements are intentionally still open: ticket 36 has no
mandatory A3/A4 runtime record, this host is macOS arm64, and the current Sana checkout
is not migrated. No synthetic measurements were substituted for the required worker
and Consumer Project evidence.
