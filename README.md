# Async Service Library (ASL)

**Language:** English | [Türkçe](tr/README.md)

ASL lets you control selected Spring service methods at runtime, without redeploying. For each method you can:

- **stop or start** it,
- **limit** how many calls run at once or how many start per second,
- **switch** it between running immediately (SYNC) and running from a durable queue (ASYNC),
- **replay or delete** failed queued calls.

All of this is done from one central admin portal for every application and replica.

ASL runs inside your application. It generates the wrappers at compile time (no reflection), and every queue and limit is bounded by default.

## Requirements

| | Version |
|---|---|
| Java | 21 or newer |
| Spring Boot | 3.3.x |
| Maven | 3.9.x |

## Modules

Add only what you need.

| You want | Add | Brings MapDB |
|---|---|:---:|
| Stop/limit methods, metrics | `asl-spring-boot-core-starter` | no |
| Durable ASYNC queue | `asl-spring-boot-mapdb-starter` (includes core) | yes |
| Manage the application from the admin portal | `asl-spring-boot-admin-client-starter` | no |
| All of the above | `asl-spring-boot-starter` | yes |
| The admin portal itself (separate process) | `asl-admin-server` | yes |

Every application also needs `asl-annotations` and the compile-time processor `asl-processor`. No module adds a web framework to your application.

## In five minutes

```java
@GovernedService(id = "mail.service")
public interface MailService {

    @GovernedMethod(initialMaxConcurrency = 4)          // at most 4 calls at once
    String send(String mailId);

    @GovernedMethod(asyncCapable = true)                // may be switched to the durable queue
    void publishAudit(@AsyncIdempotencyKey String eventId, String mailId);
}
```

Implement the interface as a normal `@Service`, inject `MailService` (not the implementation) and call it. The generated wrapper applies the controls. You can change them later in `application.yml` or in the portal.

Step by step: [Getting started](docs/getting-started.md).

## Documentation

| Page | Read it to |
|---|---|
| [Getting started](docs/getting-started.md) | add ASL to a service and run the first governed method |
| [Concepts](docs/concepts.md) | understand every control: enabled, max concurrency, SYNC/ASYNC, consumers, rate limit, retries, queue states |
| [Scenarios](docs/scenarios.md) | find the right setting for a real situation: slow dependency, partner quota, traffic spike, outage, replay after a fix |
| [Admin server and portal](docs/admin-server.md) | run the central portal and use it |
| [Configuration reference](docs/configuration.md) | look up every property, its default and its effect |
| [Operations](docs/operations.md) | go to production: defaults, limits, metrics, health, recovery, Kubernetes |
| [Troubleshooting](docs/troubleshooting.md) | match a symptom to its cause |
| [Sample application](asl-consumer-sample/README.md) | try everything locally |

## Important limits

- **One queue per process.** The ASYNC queue is a local MapDB file. Every replica has its own queue, and a queue file is never shared between pods. Work that must be shared across replicas belongs on a message broker.
- **At-least-once.** A queued call can run again after a crash. Make queued methods idempotent.
- **Limits are per replica.** `max-concurrency: 20` on three replicas allows 60 calls in total.

## Build and verify

```powershell
mvn -B -ntp clean verify
```

Release gates (API compatibility, performance, SBOM) are described in [Operations](docs/operations.md#release-gates).

## Distribution

- [GitHub repository](https://github.com/esasmer-dou/async-service-library)
- [GitHub Packages](https://maven.pkg.github.com/esasmer-dou/async-service-library) (setup: [Getting started](docs/getting-started.md#1-add-the-repository))
- [Releases](https://github.com/esasmer-dou/async-service-library/releases)
