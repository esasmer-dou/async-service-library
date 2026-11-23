# Admin server and portal

**Language:** English | [Türkçe](../tr/docs/admin-server.md) · [Documentation home](../README.md#documentation)

The admin server is one small process that gives operators a single portal for every application and replica. It works like a config server: it shows telemetry, relays settings and commands, and has no business logic. Applications expose no admin endpoint; they connect to the server themselves.

## How it works

```
replica ── register, then a heartbeat every 15 s (2 s while the portal is open) ──> admin server <── browser (portal)
        <── settings and commands in the heartbeat reply ─────────────────────────
```

| Part | Runs | Holds |
|---|---|---|
| `asl-admin-server` | one separate process (`java -jar …-exec.jar`), no web framework | in memory: connected replicas, their latest counters, open commands. In one MapDB file: telemetry history, application-wide overrides, audit log |
| `asl-spring-boot-admin-client-starter` | inside every application, one daemon thread | nothing; no UI, no inbound endpoint |

- **Traffic is small.** The heartbeat is a signed binary message (HMAC-SHA256, ±60 s clock window) carrying only values that changed. An idle heartbeat is 10–15 bytes.
- **Applications do not depend on it.** If the server is unreachable, the application keeps its last settings, backs off (up to 60 s) and drops telemetry. Governed calls never wait on the admin connection.
- **No payload leaves the pod.** Queue entries stay in the replica's own queue. Only when `asl.admin.client.queue-browse=true` does a replica send entry summaries and error texts, and the server keeps them in memory only.

## Run the server

Local, with environment variables:

```bash
ASL_ADMIN_SECRET=change-me-at-least-16-chars \
ASL_ADMIN_USER=admin ASL_ADMIN_PASSWORD=change-me \
./asl-admin-server.sh            # or: java @asl-admin-server.jvmopts -jar asl-admin-server-<version>-exec.jar
```

Open `http://localhost:8080/asl-admin/` and sign in.

`asl-admin-server.sh`, `.cmd` and `.jvmopts` are in `asl-admin-server/src/main/dist`; keep them next to the jar. The options file sets a small-footprint JVM (16 MB heap by default). The server stops on an out-of-memory error so that Kubernetes restarts it.

Settings can also come from `asl-admin.properties` in the working directory (or `--config=<path>`), JVM properties or `--key=value` arguments. All of them are in the [configuration reference](configuration.md#admin-server).

### Kubernetes

- One replica (Deployment) with a small ReadWriteOnce volume for the MapDB file. Or set `storage.mode=MEMORY` and use no volume; overrides and history are then lost on restart, and applications keep their current settings.
- Expose the portal (`/asl-admin/`) through your ingress with TLS; the server speaks plain HTTP.
- Keep `/asl/v1/*` (the heartbeat endpoint) reachable from inside the cluster only.
- The server ignores Kubernetes service-link variables such as `ASL_ADMIN_SERVER_PORT=tcp://…`. `enableServiceLinks: false` removes them altogether.

## Connect an application

```yaml
asl:
  admin:
    client:
      enabled: true
      server-url: http://asl-admin-server:8080
      secret: ${ASL_ADMIN_SECRET}     # same value as the server
      queue-browse: true              # optional: lets the portal list queue entries
```

The application name defaults to `spring.application.name`, and the replica name to the `HOSTNAME` (the pod name in Kubernetes).

## Using the portal

### Applications and methods

- **Sidebar:** applications, filterable by name or by "needs attention" (setting errors, failed queue entries, replicas still applying settings).
- **Method list:** totals over all replicas (in flight, succeeded, failed, rejected, queue). Filter by name, service, replica or state (stopped, async, queue not empty, failed entries, errors, overridden, replicas differ). Choose a replica to see only its numbers.
- **Method detail** (expand a row): one line per replica with its current values (enabled, mode, max, consumers, rate limit, in flight, errors, queue), plus trend charts from the stored history.
- **Labels:** *All: …* is an application-wide override, *Replica: …* is a replica override, and *Replicas differ* means the replicas do not run the same settings. A clock icon marks a timed override and shows the time left.

### Tune: changing a method

**Tune** (all replicas) or **Tune this replica** opens the settings dialog:

| Field | Effect | Explained in |
|---|---|---|
| Method | Running / Stopped | [Enabled](concepts.md#enabled) |
| Execution mode | SYNC / ASYNC (async-capable methods only) | [SYNC and ASYNC](concepts.md#sync-and-async) |
| Max concurrency | parallel calls per replica | [Max concurrency](concepts.md#max-concurrency) |
| Consumer threads | parallel queued runs per replica; 0 holds the queue | [Consumers](concepts.md#consumers) |
| Rate limit (calls/s) | starts per second per replica; 0 removes a limit | [Rate limit](concepts.md#rate-limit) |
| Keep for | Permanent, or 15 min / 1 h / 4 h / 24 h | [Timed overrides](#timed-overrides) |

Empty fields keep the application's own value. **Remove override** returns the method to its yml or annotation values. Replicas apply changes with their next heartbeat, within about 2 seconds while the portal is open. A replica shows *in sync* once it has applied the latest settings.

### Timed overrides

Choose a duration in **Keep for**. When the time is up, the method returns to the override it had before, or to its yml value if there was none. This return is recorded in the audit log as `override.expired` by `system`.

- Changing a timed override again with a duration keeps the original "before" value.
- A permanent change or **Remove override** cancels the timer.
- Application-wide timers survive a server restart. Replica timers disappear with the replica.

### Queue actions

The **queue** button of a method (or a *failed* label) opens the queue dialog. It asks the replicas for their entries; answers arrive with the next heartbeat.

| Action | What it does |
|---|---|
| Browse / Refresh | lists entries: state, attempts, queued at, payload summary, error category and last error |
| Replay (one entry) | puts a failed entry back in the queue |
| Delete (one entry) | removes an entry that is not running |
| **Bulk…** | replays or deletes every entry that matches a filter (states, error category, time window, at most N per replica, up to 10,000). The replica selects the entries itself, so this also reaches entries the list does not show. A confirmation shows the exact scope first. |
| Clear queue | removes every entry that is not running |

Running entries are never touched. Replay works on failed entries only. One bulk command looks at no more than 50,000 entries and commits once.

The payload summary is what the application stored under `asl.async.mapdb.payload.summary-policy`: the payload type by default, a short text of the arguments with `CODEC`. Use `CODEC` only if that text is safe to show.

### Commands and audit

- **Recent commands** shows each command, the replica, the result and who sent it. Unanswered commands expire after 5 minutes.
- The **Audit log** lists every override change, expiry and queue command (not browse), with user and time. It is kept for 7 days by default.

## Security

| `security.mode` | Use |
|---|---|
| `BASIC` (default) | users from `security.users` (`name:password:ROLE`), or `ASL_ADMIN_USER` + `ASL_ADMIN_PASSWORD`. Role `VIEWER` reads; `OPERATOR` can also change. |
| `PROXY_HEADER` | behind an authenticating proxy (SSO); the user and groups come from headers, and members of `asl-operator` get `OPERATOR`. |
| `NONE` | isolated local demo only. |

- Changes from a foreign `Origin` are rejected (`403`).
- Heartbeats need the shared secret.
- The server does not terminate TLS; put it behind a TLS ingress.

## Sizing

Memory follows the number of replica × method pairs, not the request rate. Measured with 50 methods per replica:

| Replicas | Heap after GC | `-Xmx` | Server CPU |
|---:|---:|---:|---:|
| 300 | ~7 MB | `16m` (default) | ~0.5 core |
| 1,000 | ~19 MB | `48m` | ~0.7 core |

Raise `-Xmx` before the fleet grows past about 300 replicas × 50 methods. At the default 15 s heartbeat the load is far lower; the figures above use 2 s.

History is a fixed ring per replica and method (`history.retention / history.resolution` points), so the file stops growing. Plan about 150 bytes per point.

## Portal API

The portal uses a small JSON API under `/asl-admin/api`. Scripts can use it with the same login. Calls that change something need the `OPERATOR` role.

| Endpoint | Purpose |
|---|---|
| `GET /apps` | connected applications |
| `GET /apps/{app}` | methods, replicas with their latest counters, overrides, timers, command results |
| `GET /apps/{app}/history?serviceId=&methodId=` | stored history per replica |
| `POST /apps/{app}/overrides` | form: `serviceId`, `methodId`, optional `instance`, `enabled`, `async`, `maxConcurrency`, `consumerThreads`, `rateLimit`, `durationSeconds` (10 s – 7 days) |
| `DELETE /apps/{app}/overrides?serviceId=&methodId=[&instance=]` | remove an override |
| `POST /apps/{app}/commands` | form: `type`, `serviceId`, `methodId`, optional `instance` (all replicas when empty), `entryId`, `limit`; returns `commandIds` |
| `GET /audit?limit=100` | audit log |
| `GET /server` | server counters |

Command types: `BROWSE_QUEUE`, `REPLAY_ENTRY`, `DELETE_ENTRY`, `CLEAR_QUEUE`, `RESET_OBSERVATIONS`, `REPLAY_MATCHING`, `DELETE_MATCHING`. The two bulk types also take `states` (`FAILED`, `PENDING`, `RETRY_WAIT`; default `FAILED`), `category` (`-` for none), `from` and `to` (epoch ms), and `limit` (1–10,000 per replica).

```powershell
$api = "http://localhost:8080/asl-admin/api/apps/my-app"
Invoke-RestMethod -Method Post "$api/overrides" -Credential $cred -Body @{
  serviceId = "mail.service"; methodId = "send(java.lang.String)"; maxConcurrency = "2"; durationSeconds = "3600" }
```
