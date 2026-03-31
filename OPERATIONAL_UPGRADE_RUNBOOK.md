# Operational Upgrade Runbook

This runbook is for ASL deployments that use:

- persistent MapDB async queues
- custom async payload codecs
- Jackson schema registry and migration hooks

Use it before changing:

- application version
- payload schema version
- codec implementation
- method signature or async payload contract

## 1. Pre-Upgrade Checks

Before deploying a new build:

- Open the admin UI at `/asl`
- For every async-capable method, inspect `Queue Buffer`
- Record:
  - `Pending`
  - `Failed`
  - `In progress`
  - `Codec`
  - `Payload Type`
  - `Payload Version`
  - `Error Category`

Do not deploy blind if:

- queue depth is unexpectedly growing
- failed entries already exist and are not understood
- payload versions in the queue are unknown to the target build

## 2. Decide the Upgrade Mode

Choose one of these modes explicitly.

### Drain First

Use this when:

- payload schema changed incompatibly
- method parameters changed order or meaning
- you do not have migration hooks

Steps:

1. Switch the async method to `ASYNC` if it is not already.
2. Set `Consumer Threads` to a stable value and let the queue drain.
3. Wait until `Pending = 0` and `In progress = 0`.
4. Resolve or delete any `Failed` entries based on business policy.
5. Deploy the new version.

### Live Migration

Use this when:

- the target build contains a backward-compatible codec/schema registry
- migration hooks are implemented and tested

Steps:

1. Keep the queue persistent path unchanged.
2. Deploy the new build with the updated codec/schema registry.
3. Start consumers with a low count, usually `1`.
4. Watch queue entries for `MIGRATION` and `DECODE` failures.
5. Scale consumer threads up only after the first migrated entries succeed.

## 3. Safe Deployment Procedure

Recommended sequence:

1. Reduce consumer threads for each async method to `0` or `1`.
2. If downstream systems are unstable, stop the method first.
3. Confirm queue state in the admin UI.
4. Deploy the new version.
5. Re-open stopped methods if needed.
6. Increase consumer threads gradually.
7. Watch buffer entries for new failures.

Why:

- it reduces the blast radius
- it isolates migration problems quickly
- it prevents a broken codec from rapidly poisoning the whole queue

## 4. Interpreting Error Categories

ASL classifies failed buffer entries into these categories.

### `BUSINESS`

Meaning:

- the payload decoded successfully
- the method implementation failed while processing business logic

Typical action:

- inspect the business exception
- fix the downstream or domain issue
- replay if safe

### `DECODE`

Meaning:

- the queue payload could not be decoded with the active codec
- codec mismatch or payload parse failure occurred

Typical action:

- verify `Codec`, `Payload Type`, and `Payload Version`
- confirm the target build is using the correct codec
- do not bulk replay until decoding is fixed

### `MIGRATION`

Meaning:

- the payload was recognized, but schema migration could not complete
- migration hook is missing or rejected the source version

Typical action:

- inspect the schema registry
- add or fix the migration path
- redeploy
- replay failed entries

### `RECOVERY`

Meaning:

- the application restarted while an invocation was `IN_PROGRESS`
- ASL recovered it as a failed queue entry on startup

Typical action:

- inspect whether the underlying business action is idempotent
- replay only if replay is safe

## 5. Schema and Signature Changes

Treat these changes as queue-impacting:

- changing method parameter order
- renaming durable payload fields
- replacing one payload type with another
- removing fields used by old queue entries

Safe rule:

- if old queue entries still exist, the new build must still understand them

For Jackson schema registry mode:

- keep `schemaType` stable
- bump `schemaVersion` intentionally
- implement migration from the old version to the new one

## 6. When to Drain Instead of Migrate

Drain before deployment if any of these are true:

- the old payload cannot be represented in the new model
- you changed method parameters without a migration plan
- the queue contains long-lived entries with mixed old shapes
- the new codec id differs and the new build cannot decode the old one

## 7. Post-Upgrade Validation

After deployment:

1. Open `/asl`
2. Check each async method buffer
3. Confirm new entries show the expected:
  - `Codec`
  - `Payload Type`
  - `Payload Version`
4. Confirm `Failed` does not grow unexpectedly
5. Replay one known-safe failed entry if you intentionally tested migration
6. Increase consumer threads back to operational level

## 8. Rollback Guidance

Rollback is safe only if the old build can decode all queue entries written by the new build.

Before rollback:

- compare codec ids
- compare schema versions
- confirm backward compatibility

If rollback cannot decode new queue entries:

- stop consumers
- export or clear the affected queue based on business policy
- then rollback

## 9. Recommended Operational Policy

- Do not change codec strategy and business payload model in the same release unless migration is fully tested.
- Keep one explicit owner for replay and delete permissions.
- Never bulk replay unknown `DECODE` or `MIGRATION` failures.
- Treat `RECOVERY` entries as potentially duplicated business actions until proven idempotent.
- Validate upgrade scenarios in [asl-consumer-sample](E:\ReactorRepository\async-service-library\asl-consumer-sample) before production rollout.
