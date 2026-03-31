# Benchmark Runbook

## Goal

This runbook standardizes lightweight ASL control-plane benchmarks and report generation.

It is not meant to prove maximum throughput. It is meant to produce repeatable operator-facing reports for:

- admin UI responsiveness
- admin REST responsiveness
- summary snapshot stability
- queue-state visibility under representative load

## Prerequisites

- a running ASL-enabled application
- preferably the sample app on `http://localhost:8080`
- stable test data or a resettable staging environment

## Recommended Target

Use the consumer sample first:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

Admin endpoints:

- `http://localhost:8080/asl`
- `http://localhost:8080/asl/api/summary`

## Generate A Report

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark.ps1
```

Custom target:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark.ps1 `
  -BaseUrl http://localhost:8080 `
  -Warmup 5 `
  -Iterations 20
```

Generate the full sample suite with both idle and backlog snapshots:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-sample-benchmark-suite.ps1 `
  -BaseUrl http://localhost:8080
```

Generate and validate the suite against explicit thresholds:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1 `
  -StartSample `
  -Profile local `
  -Warmup 1 `
  -Iterations 3
```

If the sample is not already running, let the script boot it for you:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-sample-benchmark-suite.ps1 `
  -StartSample
```

When `-StartSample` is used without a custom base URL, the suite defaults to `http://localhost:18080` to avoid common local port collisions.
It also performs an automatic baseline reset before the idle snapshot so previous runs do not leak queue state into the report.
When the suite boots the sample itself, it uses an isolated benchmark queue under `reports/real-sample/sample-runtime/benchmark-queue.db`, so the main demo queue file is not reused or force-terminated by the benchmark process.
The suite only stops the sample when that sample was started by the suite itself.

The script writes a Markdown report under:

- `reports/control-plane-benchmark-*.md`

The suite script writes a grouped output set under:

- `reports/real-sample/`

The validation script can also be run independently against existing outputs:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\assert-control-plane-benchmark.ps1 `
  -Profile local `
  -IdleSummaryPath .\reports\real-sample\summary-idle.json `
  -BacklogSummaryPath .\reports\real-sample\summary-backlog.json `
  -IdleReportPath .\reports\real-sample\control-plane-benchmark-sample-idle.md `
  -BacklogReportPath .\reports\real-sample\control-plane-benchmark-sample-backlog.md
```

Available profiles:

- `local`
- `ci`
- `staging`

Profile thresholds are stored in:

- [control-plane-benchmark-thresholds.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-thresholds.json)
- Optional overlay example: [control-plane-benchmark-thresholds.override.example.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-thresholds.override.example.json)
- Profile resolution rules: [control-plane-benchmark-profile-resolution.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-profile-resolution.json)

Example profile usage:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1 `
  -StartSample `
  -Profile ci
```

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1 `
  -BaseUrl https://staging.example.internal `
  -Profile staging
```

Explicit parameters still override profile defaults when needed.

Threshold resolution order:

1. Explicit CLI parameters
2. Environment variables
3. Overlay JSON files
4. Base profile JSON

Supported environment variables:

- `ASL_BENCHMARK_PROFILE`
- `ASL_BENCHMARK_PROFILE_RESOLUTION_PATH`
- `ASL_BENCHMARK_BRANCH_NAME`
- `ASL_BENCHMARK_RELEASE_TAG`
- `ASL_BENCHMARK_THRESHOLDS_PATH`
- `ASL_BENCHMARK_EXTRA_THRESHOLDS_PATHS`
- `ASL_BENCHMARK_IDLE_ADMIN_SUMMARY_AVG_MAX_MS`
- `ASL_BENCHMARK_BACKLOG_ADMIN_SUMMARY_AVG_MAX_MS`
- `ASL_BENCHMARK_MINIMUM_BACKLOG_QUEUE_DEPTH`
- `ASL_BENCHMARK_MINIMUM_BACKLOG_FAILED_ENTRIES`
- `ASL_BENCHMARK_MINIMUM_BACKLOG_HIGH_ATTENTION_ITEMS`
- `ASL_BENCHMARK_READY_TIMEOUT_SECONDS`

Overlay file handling:

- `ASL_BENCHMARK_EXTRA_THRESHOLDS_PATHS` accepts semicolon-separated JSON files
- `scripts/control-plane-benchmark-thresholds.override.json` is auto-loaded if present
- `scripts/control-plane-benchmark-thresholds.<profile>.override.json` is auto-loaded if present
- overlay files can override existing profiles or introduce new profile names such as `team-ci`

Profile resolution order:

1. Explicit `-Profile`
2. `ASL_BENCHMARK_PROFILE`
3. Release tag mapping
4. Branch mapping
5. `defaultProfile` from the resolution file

Default mapping behavior:

- `main`, `master`, `develop`, `development` -> `ci`
- `release/*`, `hotfix/*` -> `staging`
- tags `v*`, `release-*` -> `staging`
- everything else -> `local`

Example environment-based override:

```powershell
$env:ASL_BENCHMARK_PROFILE = "staging"
$env:ASL_BENCHMARK_BACKLOG_ADMIN_SUMMARY_AVG_MAX_MS = "520"
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1
```

Example team/project overlay:

```powershell
$env:ASL_BENCHMARK_EXTRA_THRESHOLDS_PATHS = ".\scripts\control-plane-benchmark-thresholds.override.json;E:\Shared\team-thresholds.json"
powershell -ExecutionPolicy Bypass -File .\scripts\assert-control-plane-benchmark.ps1 `
  -Profile team-ci `
  -IdleSummaryPath .\reports\real-sample\summary-idle.json `
  -BacklogSummaryPath .\reports\real-sample\summary-backlog.json `
  -IdleReportPath .\reports\real-sample\control-plane-benchmark-sample-idle.md `
  -BacklogReportPath .\reports\real-sample\control-plane-benchmark-sample-backlog.md
```

CI injection options:

- `workflow_dispatch` inputs in [control-plane-benchmark-gate.yml](E:\ReactorRepository\async-service-library\.github\workflows\control-plane-benchmark-gate.yml)
- repository/environment variables
- GitHub secrets mapped into `ASL_BENCHMARK_*` environment variables
- startup timeout can be raised with `ASL_BENCHMARK_READY_TIMEOUT_SECONDS` for slower environments

Gate artifact output (generated locally and uploaded as CI artifacts, not committed):

- default JSON summary: `reports/real-sample/control-plane-benchmark-gate-summary.json`
- summary contains resolved profile source, branch/tag context, thresholds, key metrics, and pass/fail state
- human-readable Markdown summary: `reports/real-sample/control-plane-benchmark-gate-summary.md`
- release note appendix: `reports/real-sample/control-plane-benchmark-gate-release-note.md`
- trend diff report: `reports/real-sample/control-plane-benchmark-gate-trend.md`
- historical snapshots: `reports/real-sample/history/`

Reference examples in the repo:

- `reports/example-control-plane-benchmark-idle.md`
- `reports/example-control-plane-benchmark-backlog.md`

## Suggested Benchmark Sequence

1. Reset sample scenarios or let the suite perform the baseline reset
2. Capture a baseline report while the queue is mostly idle
3. Create representative queue pressure
4. Capture a second report while backlog exists
5. Drain the queue
6. Capture a recovery report

This gives you:

- idle baseline
- stressed control-plane snapshot
- recovery snapshot

If you need a formatting reference before the first live run, compare your output with the example reports in `reports/`.

## How To Create Representative Pressure

Examples with the consumer sample:

- stop consumers on an async-capable method
- enqueue several requests
- create one forced failure and replay it later

Example:

```powershell
curl -X POST http://localhost:8080/api/test/scenarios/mail/audit `
  -H "Content-Type: application/json" `
  -d "{\"failuresRemaining\":1,\"processingDelayMillis\":0}"
```

Then use the admin API or UI to:

- switch the method to `ASYNC`
- set consumer threads to `0`
- publish several events

## What To Watch

For each report, compare:

- `overallPressure`
- `failedEntries`
- `totalQueueDepth`
- `attentionItems`
- average latency of:
  - `/asl`
  - `/asl/api/summary`
  - `/asl/api/services`

## Acceptance Guidance

Use the benchmark together with automated tests, not instead of them.

Recommended minimum acceptance gate:

- all tests pass
- summary endpoint returns stable structure
- admin UI renders expected overview and digest sections
- benchmark report under idle load shows low pressure
- benchmark report under recovery load shows attention items and then clears after drain

## CI Gate

An example GitHub Actions workflow is included here:

- [control-plane-benchmark-gate.yml](E:\ReactorRepository\async-service-library\.github\workflows\control-plane-benchmark-gate.yml)

It runs the sample benchmark gate on Windows, uploads the generated reports, and fails the workflow if:

- idle pressure is not `LOW`
- backlog pressure is not `HIGH`
- backlog queue/failure thresholds are not met
- `Admin Summary` average latency exceeds the configured threshold

When running on GitHub Actions, the gate also appends a Markdown summary and trend block into the job summary via `GITHUB_STEP_SUMMARY`.
