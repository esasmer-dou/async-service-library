# Example Benchmark Reports

This folder stores operator-facing benchmark outputs for the ASL control-plane workflow.

Included examples:

- [Idle baseline](E:\ReactorRepository\async-service-library\reports\example-control-plane-benchmark-idle.md)
- [Backlog / attention snapshot](E:\ReactorRepository\async-service-library\reports\example-control-plane-benchmark-backlog.md)

Use them for:

- validating the expected Markdown shape
- aligning on what "good" versus "needs attention" looks like
- comparing real benchmark runs against a known reference format

Real sample outputs and gate tooling:

- generated real outputs: [real-sample](E:\ReactorRepository\async-service-library\reports\real-sample)
- validator script: [assert-control-plane-benchmark.ps1](E:\ReactorRepository\async-service-library\scripts\assert-control-plane-benchmark.ps1)
- gate runner: [run-control-plane-benchmark-gate.ps1](E:\ReactorRepository\async-service-library\scripts\run-control-plane-benchmark-gate.ps1)
- threshold profiles: [control-plane-benchmark-thresholds.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-thresholds.json)
- threshold overlay example: [control-plane-benchmark-thresholds.override.example.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-thresholds.override.example.json)
- profile resolution rules: [control-plane-benchmark-profile-resolution.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-profile-resolution.json)

The gate can now resolve thresholds from:

- base profile JSON
- one or more overlay JSON files
- `ASL_BENCHMARK_*` environment variables
- CI workflow inputs, repository variables, and secrets

The gate also writes a machine-readable artifact summary:

- [control-plane-benchmark-gate-summary.json](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-summary.json)

It also generates operator-facing Markdown artifacts:

- [control-plane-benchmark-gate-summary.md](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-summary.md)
- [control-plane-benchmark-gate-release-note.md](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-release-note.md)
- [control-plane-benchmark-gate-trend.md](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-trend.md)
- history archive folder: [history](E:\ReactorRepository\async-service-library\reports\real-sample\history)

MapDB abuse outputs:

- destructive suite output root: [reports/mapdb-abuse](E:\ReactorRepository\async-service-library\reports\mapdb-abuse)
- runner script: [run-mapdb-abuse-suite.ps1](E:\ReactorRepository\async-service-library\scripts\run-mapdb-abuse-suite.ps1)
- nightly CI workflow: [mapdb-abuse-nightly.yml](E:\ReactorRepository\async-service-library\.github\workflows\mapdb-abuse-nightly.yml)
