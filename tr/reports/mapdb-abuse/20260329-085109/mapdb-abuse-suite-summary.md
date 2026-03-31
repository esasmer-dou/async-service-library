# MapDB Yipratici Test Raporu

- Calisma zamani: 2026-03-29T08:56:56.2782853+03:00
- Genel durum: FAIL

| Senaryo | Durum | Ana sinyal |
| --- | --- | --- |
| pending-survives-force-kill | PASS | pendingBeforeKill=1 |
| failed-queue-replay-after-force-kill | PASS | failedBeforeKill=1 |
| in-progress-recovery-after-force-kill | FAIL | inProgressBeforeKill=1 |
| repeated-pending-force-kill-loop | PASS | pendingBeforeKill#1=1 |
| header-corruption-startup-recovery | PASS | storageRecoveryVisible=True |

## pending-survives-force-kill

- Durum: PASS
- pendingBeforeKill: 1
- startupRecovered: False
- overallPressureAfterDrain: LOW
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\pending-survives-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\pending-survives-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\pending-survives-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\pending-survives-force-kill-start-2.err.log

## failed-queue-replay-after-force-kill

- Durum: PASS
- failedBeforeKill: 1
- failedAfterRestart: 1
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\failed-queue-replay-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\failed-queue-replay-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\failed-queue-replay-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\failed-queue-replay-after-force-kill-start-2.err.log

## in-progress-recovery-after-force-kill

- Durum: FAIL
- inProgressBeforeKill: 1
- failedAfterRestart: 0
- Not: The property 'entries' cannot be found on this object. Verify that the property exists.
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\in-progress-recovery-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\in-progress-recovery-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\in-progress-recovery-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\in-progress-recovery-after-force-kill-start-2.err.log

## repeated-pending-force-kill-loop

- Durum: PASS
- pendingBeforeKill#1: 1
- pendingBeforeKill#2: 2
- pendingBeforeKill#3: 3
- finalEventCount: 3
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-start-2.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-start-3.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-start-3.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-final.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\repeated-pending-force-kill-loop-final.err.log

## header-corruption-startup-recovery

- Durum: PASS
- storageRecoveryVisible: True
- storageRecoveryStatus: Fresh recovery store created
- storageRecoveryMovedToPath: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\header-corruption-startup-recovery.db.recovered-1774763811348
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\header-corruption-startup-recovery-start.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-085109\header-corruption-startup-recovery-start.err.log

