# Başlarken

**Dil:** [English](../../docs/getting-started.md) | Türkçe · [Dokümantasyon ana sayfası](../README.md#dokümantasyon)

Bu sayfa bir Spring Boot servisini sıfırdan ilk yönetilen metoduna götürür, sonra kalıcı kuyruğu ve admin portalını ekler. 4. adımdan sonrası isteğe bağlıdır.

## 1. Depoyu ekleyin

ASL, GitHub Packages'ta yayınlanır. Depoyu `pom.xml`'e ekleyin:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/async-service-library</url>
    </repository>
</repositories>
```

Kimlik bilgilerini `~/.m2/settings.xml`'e ekleyin (token'da `read:packages` yetkisi olmalı):

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

Token'ları ortam değişkenlerinde veya CI secret'larında tutun, asla `pom.xml`'de değil.

## 2. Bağımlılıkları ve processor'ü ekleyin

```xml
<properties>
    <asl.version>SURUMUNUZ</asl.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-core-starter</artifactId>   <!-- ya da daha büyük bir starter, README'ye bakın -->
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

Processor, proje derlenirken wrapper'ı ve onun Spring yapılandırmasını üretir.

## 3. Servisi tanımlayın

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

| Annotation | Anlamı |
|---|---|
| `@GovernedService(id)` | Interface'i işaretler. `id`, metoda yml'de ve portalda nasıl erişileceğini belirler. Değiştirmeyin. |
| `@GovernedMethod` | İsteğe bağlı. Başlangıç değerlerini verir: `initiallyEnabled`, `initialMaxConcurrency`, `unavailableMessage`, `asyncCapable`, `initialConsumerThreads` ve `id` (varsayılanı Java imzasıdır, örneğin `send(java.lang.String)`). |
| `asyncCapable = true` | Metot kalıcı kuyruktan çalışabilir. `void` veya `CompletableFuture<T>` döndüren metotlarda kullanılabilir. Metot SYNC modda başlar. |
| `@AsyncIdempotencyKey` | İsteğe bağlı; async-capable bir metodun tek bir `String` parametresine konur. Aynı key ile gelen ikinci çağrı ikinci kez kuyruğa alınmaz. |
| `@Excluded` | Metot yönetilmez; çağrılar doğrudan implementation'a gider. |

`@GovernedMethod` olmayan metotlar da yönetilir, yalnızca limitsizdir.

## 4. Interface'i inject edip çalıştırın

`MailServiceImpl`'i değil, `MailService`'i inject edin. Üretilen `@Primary` bean implementation'ı sarar. Artık her çağrı ASL'den geçer: sayılır, sınırlanır, durdurulabilir.

Interface ile implementation farklı modüllerdeyse wrapper'ı kendiniz kaydedin:

```java
@Bean
@Primary
MailService mailService(MailServiceImpl implementation, GovernanceRegistry registry) {
    return MailServiceAsl.wrap(implementation, registry);
}
```

ASL, yönetilen bir interface wrapper'sız bean olarak yayınlanırsa açılışta uyarı loglar. Bunu açılış hatasına çevirmek için `asl.runtime.unwrapped-governed-bean-policy: FAIL` verin.

## 5. Başlangıç değerlerini yml ile değiştirin

İsteğe bağlıdır.

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

İki anahtarı da **tırnak içinde, köşeli parantezle** yazın. Aksi halde Spring anahtarı her noktada böler ve ASL açılışı bir ipucuyla durdurur. Tüm anahtarlar [yapılandırma referansında](configuration.md#metot-başlangıç-değerleri) listelenir.

## 6. Kalıcı kuyruğu ekleyin

İsteğe bağlıdır. Core starter yerine `asl-spring-boot-mapdb-starter` kullanın ve motoru açın:

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
```

Artık `asyncCapable` bir metot ASYNC moda geçirilebilir: yml'de (`execution-mode: ASYNC`) veya portalda. Geçirmeden önce [SYNC ve ASYNC](concepts.md#sync-ve-async) bölümünü okuyun ve metodu idempotent yapın.

## 7. Admin portalına bağlayın

İsteğe bağlıdır. `asl-spring-boot-admin-client-starter` ekleyin ve admin server'ı gösterin:

```yaml
asl:
  admin:
    client:
      enabled: true
      server-url: http://asl-admin-server:8080
      secret: ${ASL_ADMIN_SECRET}     # server ile aynı değer, en az 16 karakter
```

Server'ın kendisini çalıştırmak [Admin server ve portal](admin-server.md) sayfasında anlatılır.

## Sonraki adımlar

- [Kavramlar](concepts.md): her kontrol ne anlama gelir.
- [Senaryolar](scenarios.md): hangi kontrol ne zaman kullanılır.
- [Örnek uygulama](../asl-consumer-sample/README.md): yukarıdakilerin hepsi, çalışmaya hazır.
