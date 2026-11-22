# Reports

Generated reports are not committed. They are written locally or uploaded as CI artifacts.

## Recovery stress suite (MapDB abuse)

- Output root: `reports/mapdb-abuse/<timestamp>/` (Turkish summary under `tr/reports/mapdb-abuse/`)
- Runner: [run-mapdb-abuse-suite.ps1](../scripts/run-mapdb-abuse-suite.ps1) (PowerShell 7)
- Nightly CI: [mapdb-abuse-nightly.yml](../.github/workflows/mapdb-abuse-nightly.yml)

The suite starts the central `asl-admin-server` and the consumer sample with the admin client enabled.
Each scenario kills the sample hard, restarts it and checks that queued work survives. Mode and consumer
changes, queue browse and replay go through the admin server, the same path an operator uses.

| Scenario | What it proves |
| --- | --- |
| `pending-survives-force-kill` | Pending work survives a hard kill and runs after restart |
| `failed-queue-replay-after-force-kill` | A failed entry survives restart and can be replayed |
| `in-progress-recovery-after-force-kill` | Work interrupted mid-execution is kept and delivered after restart |
| `repeated-pending-force-kill-loop` | Pending work accumulates correctly across three kills |
| `header-corruption-startup-recovery` | A corrupted store is archived and recreated at startup |

Each run writes `mapdb-abuse-suite-summary.json` and `mapdb-abuse-suite-summary.md`, plus one log pair per
sample start and for the admin server.

## Data plane performance

JMH evidence from [data-plane-performance.yml](../.github/workflows/data-plane-performance.yml) is uploaded
from `asl-benchmarks/target/performance-gate`.
