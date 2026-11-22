# MapDB Yipratici Test Raporu

- Calisma zamani: 2026-11-22T15:48:38.8291083+03:00
- Genel durum: PASS

| Senaryo | Durum | Ana sinyal |
| --- | --- | --- |
| pending-survives-force-kill | PASS | pendingBeforeKill=1 |
| failed-queue-replay-after-force-kill | PASS | failedBeforeKill=1 |
| in-progress-recovery-after-force-kill | PASS | inProgressBeforeKill=1 |
| repeated-pending-force-kill-loop | PASS | pendingBeforeKill#1=1 |
| header-corruption-startup-recovery | PASS | storageRecovered=True |

## pending-survives-force-kill

- Durum: PASS
- pendingBeforeKill: 1
- storeArchivedOnRestart: False
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\pending-survives-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\pending-survives-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\pending-survives-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\pending-survives-force-kill-start-2.err.log

## failed-queue-replay-after-force-kill

- Durum: PASS
- failedBeforeKill: 1
- failedAfterRestart: 1
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\failed-queue-replay-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\failed-queue-replay-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\failed-queue-replay-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\failed-queue-replay-after-force-kill-start-2.err.log

## in-progress-recovery-after-force-kill

- Durum: PASS
- inProgressBeforeKill: 1
- failedAfterRestart: 0
- pendingAfterRestart: 1
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\in-progress-recovery-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\in-progress-recovery-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\in-progress-recovery-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\in-progress-recovery-after-force-kill-start-2.err.log

## repeated-pending-force-kill-loop

- Durum: PASS
- pendingBeforeKill#1: 1
- pendingBeforeKill#2: 2
- pendingBeforeKill#3: 3
- finalEventCount: 1
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-start-2.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-start-3.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-start-3.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-final.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\repeated-pending-force-kill-loop-final.err.log

## header-corruption-startup-recovery

- Durum: PASS
- storageRecovered: True
- archivedPath: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\header-corruption-startup-recovery.db.corrupt-1795351715594
- reportingToAdminServer: True
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\header-corruption-startup-recovery-start.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20261122-154500\header-corruption-startup-recovery-start.err.log

