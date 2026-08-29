# Backend benchmark evidence

`tools/collect_backend_benchmark.py` collects one candidate result into a
digest-addressed JSON report. It is development tooling only; it never selects
a backend or activates a capability.

The supplied configuration declares the target device-profile revision, ABI,
candidate identity, schema version, and an argument-list command. The command
may invoke an authorized device runner, but must emit UTF-8 JSON with exactly
these evidence sections:

- `correctness`: explicit pass result from numerical/reference-vector checks;
- `latency`: non-negative cold and warm measurements;
- `memory`: non-negative peak bytes;
- `fallback`: explicit `silent_cpu_fallback_detected: false` result;
- `lifecycle`: explicit pass result through the required lifecycle probes; and
- `thermal`: explicit pass result from the declared sustained-run protocol.

The collector rejects any missing section, failed check, invalid measurement,
nonzero candidate exit, or silent fallback. It records configuration and result
digests, and refuses to overwrite an evidence report. Store reports outside Git
when they contain sensitive device details; record their digest and summary in
the issue and release evidence.

The native selector accepts only evidence digests for candidates that match the
active device profile and ABI. A report is not sufficient to select a backend
until the benchmark protocol, candidate artifact, reference vectors, and
release review satisfy `docs/MODEL_STRATEGY.md` and the delivery gates.
