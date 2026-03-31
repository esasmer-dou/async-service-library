# MapDB Abuse Suite

- Executed at: 2026-03-29T08:48:44.8988556+03:00
- Overall status: FAIL

| Scenario | Status | Key Signal |
| --- | --- | --- |
| pending-survives-force-kill | FAIL | pendingBeforeKill=1 |
| failed-queue-replay-after-force-kill | PASS | failedBeforeKill=1 |
| in-progress-recovery-after-force-kill | FAIL | inProgressBeforeKill=1 |
| repeated-pending-force-kill-loop | PASS | pendingBeforeKill#1=1 |
| header-corruption-startup-recovery | FAIL | - |

## pending-survives-force-kill

- Status: FAIL
- pendingBeforeKill: 1
- Note: The property 'visible' cannot be found on this object. Verify that the property exists.
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\pending-survives-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\pending-survives-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\pending-survives-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\pending-survives-force-kill-start-2.err.log

## failed-queue-replay-after-force-kill

- Status: PASS
- failedBeforeKill: 1
- failedAfterRestart: 1
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\failed-queue-replay-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\failed-queue-replay-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\failed-queue-replay-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\failed-queue-replay-after-force-kill-start-2.err.log

## in-progress-recovery-after-force-kill

- Status: FAIL
- inProgressBeforeKill: 1
- Note: The property 'failedCount' cannot be found on this object. Verify that the property exists.
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\in-progress-recovery-after-force-kill-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\in-progress-recovery-after-force-kill-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\in-progress-recovery-after-force-kill-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\in-progress-recovery-after-force-kill-start-2.err.log

## repeated-pending-force-kill-loop

- Status: PASS
- pendingBeforeKill#1: 1
- pendingBeforeKill#2: 2
- pendingBeforeKill#3: 3
- finalEventCount: 3
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-start-1.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-start-1.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-start-2.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-start-2.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-start-3.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-start-3.err.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-final.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\repeated-pending-force-kill-loop-final.err.log

## header-corruption-startup-recovery

- Status: FAIL
- Note: The property 'visible' cannot be found on this object. Verify that the property exists.
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\header-corruption-startup-recovery-start.out.log
- Log: E:\ReactorRepository\async-service-library\reports\mapdb-abuse\20260329-084446\header-corruption-startup-recovery-start.err.log

