# MapDB Yipratici Test Raporu

- Calisma zamani: 2026-03-29T09:04:56.7014219+03:00
- Genel durum: PASS

| Senaryo | Durum | Ana sinyal |
| --- | --- | --- |
| pending-survives-force-kill | PASS | pendingBeforeKill=1 |
| failed-queue-replay-after-force-kill | PASS | failedBeforeKill=1 |
| in-progress-recovery-after-force-kill | PASS | inProgressBeforeKill=1 |
| repeated-pending-force-kill-loop | PASS | pendingBeforeKill#1=1 |
| header-corruption-startup-recovery | PASS | storageRecoveryVisible=True |

## pending-survives-force-kill

- Durum: PASS
- pendingBeforeKill: 1
- startupRecovered: False
- overallPressureAfterDrain: LOW
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\pending-survives-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\pending-survives-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\pending-survives-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\pending-survives-force-kill-start-2.err.log

## failed-queue-replay-after-force-kill

- Durum: PASS
- failedBeforeKill: 1
- failedAfterRestart: 1
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\failed-queue-replay-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\failed-queue-replay-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\failed-queue-replay-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\failed-queue-replay-after-force-kill-start-2.err.log

## in-progress-recovery-after-force-kill

- Durum: PASS
- inProgressBeforeKill: 1
- failedAfterRestart: 1
- recoveryErrorCategory: RECOVERY
- recoveryLastError: Recovered stale in-progress invocation after restart
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\in-progress-recovery-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\in-progress-recovery-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\in-progress-recovery-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\in-progress-recovery-after-force-kill-start-2.err.log

## repeated-pending-force-kill-loop

- Durum: PASS
- pendingBeforeKill#1: 1
- pendingBeforeKill#2: 2
- pendingBeforeKill#3: 3
- finalEventCount: 3
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-start-2.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-start-3.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-start-3.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-final.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\repeated-pending-force-kill-loop-final.err.log

## header-corruption-startup-recovery

- Durum: PASS
- storageRecoveryVisible: True
- storageRecoveryStatus: Fresh recovery store created
- storageRecoveryMovedToPath: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\header-corruption-startup-recovery.db.recovered-1774764290043
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\header-corruption-startup-recovery-start.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-090035\header-corruption-startup-recovery-start.err.log

