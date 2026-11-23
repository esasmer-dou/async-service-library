# Getting started

**Language:** English | [Türkçe](../tr/docs/getting-started.md) · [Documentation home](../README.md#documentation)

This page takes a Spring Boot service from zero to its first governed method, then adds the durable queue and the admin portal. Each step is optional after step 4.

## 1. Add the repository

ASL is published to GitHub Packages. Add the repository to `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/async-service-library</url>
    </repository>
</repositories>
```

and the credentials to `~/.m2/settings.xml` (the token needs `read:packages`):

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>${env.GITHUB_ACTOR}</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

Keep tokens in environment variables or CI secrets, never in `pom.xml`.

## 2. Add the dependencies and the processor

```xml
<properties>
    <asl.version>YOUR_RELEASE</asl.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-core-starter</artifactId>   <!-- or a larger starter, see README -->
        <version>${asl.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-annotations</artifactId>
        <version>${asl.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.reactor.asl</groupId>
                        <artifactId>asl-processor</artifactId>
                        <version>${asl.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The processor generates a wrapper and its Spring configuration when the project compiles.

## 3. Define the service

```java
@GovernedService(id = "mail.service")
public interface MailService {

    @GovernedMethod(initialMaxConcurrency = 4)
    String send(String mailId);

    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 2)
    void publishAudit(@AsyncIdempotencyKey String eventId, String mailId);

    @Excluded
    String localHealth();
}

@Service
public class MailServiceImpl implements MailService {
    // normal implementation
}
```

| Annotation | Meaning |
|---|---|
| `@GovernedService(id)` | Marks the interface. The `id` is how the method is addressed in yml and in the portal. Keep it stable. |
| `@GovernedMethod` | Optional. Sets the start values: `initiallyEnabled`, `initialMaxConcurrency`, `unavailableMessage`, `asyncCapable`, `initialConsumerThreads`, and `id` (defaults to the Java signature, for example `send(java.lang.String)`). |
| `asyncCapable = true` | The method may run from the durable queue. Allowed on methods that return `void` or `CompletableFuture<T>`. It starts in SYNC mode. |
| `@AsyncIdempotencyKey` | Optional, on one `String` parameter of an async-capable method. A second call with the same key is not queued twice. |
| `@Excluded` | The method is not governed; calls go straight to the implementation. |

Methods without `@GovernedMethod` are still governed, with no limits.

## 4. Inject the interface and run

Inject `MailService`, not `MailServiceImpl`. The generated `@Primary` bean wraps the implementation. Every call now goes through ASL: counted, limited and stoppable.

If the interface and its implementation are in different modules, register the wrapper yourself:

```java
@Bean
@Primary
MailService mailService(MailServiceImpl implementation, GovernanceRegistry registry) {
    return MailServiceAsl.wrap(implementation, registry);
}
```

ASL logs a warning at startup when a governed interface is exposed without its wrapper. Set `asl.runtime.unwrapped-governed-bean-policy: FAIL` to make that a startup error.

## 5. Change the start values in yml (optional)

```yaml
asl:
  runtime:
    services:
      "[mail.service]":
        methods:
          "[send(java.lang.String)]":
            max-concurrency: 6
            rate-limit: 50
```

Write both keys **in brackets with quotes**. Without them Spring splits the key at each dot, and ASL stops startup with a hint. Every key is listed in the [configuration reference](configuration.md#method-start-values).

## 6. Add the durable queue (optional)

Use `asl-spring-boot-mapdb-starter` instead of the core starter and enable the engine:

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
```

An `asyncCapable` method can now be switched to ASYNC, in yml (`execution-mode: ASYNC`) or in the portal. Before you do, read [SYNC and ASYNC](concepts.md#sync-and-async) and make the method idempotent.

## 7. Connect to the admin portal (optional)

Add `asl-spring-boot-admin-client-starter` and point it at the admin server:

```yaml
asl:
  admin:
    client:
      enabled: true
      server-url: http://asl-admin-server:8080
      secret: ${ASL_ADMIN_SECRET}     # same value as the server, at least 16 characters
```

Running the server itself is covered in [Admin server and portal](admin-server.md).

## Next

- [Concepts](concepts.md): what each control means.
- [Scenarios](scenarios.md): which control to use when.
- [Sample application](../asl-consumer-sample/README.md): everything above, ready to run.
