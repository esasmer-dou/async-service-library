# Async Service Library Kullanım Rehberi

**Dil:** [English](../USAGE_GUIDE.md) | Türkçe

Bu rehber kurulum adımlarını ve runtime davranışını anlatır. Dışarıdan değiştirilebilen bütün ayarlar, varsayılan değerleri ve ayar verilmediğinde oluşan davranış [yapılandırma referansında](CONFIGURATION_REFERENCE.md) yer alır.

## 1. Gereksinimler ve Kapsam

- Java 21 veya daha yeni bir sürüm.
- Starter tabanlı kurulum için Spring Boot 3.3.x.
- Bu rehberdeki komutlar için Maven 3.9.x.
- Her governed interface için tek bir somut Spring stereotype implementation.
- Runtime sırasında yalnızca `asyncCapable=true` olarak işaretlenen `void` metotlar async moda geçirilebilir.

ASL süreç içi bir yönetim kütüphanesidir; dağıtık iş akışı motoru değildir. MapDB backend’i tek JVM’e aittir ve aynı dosya birden fazla pod tarafından paylaşılmamalıdır.

## 2. Uygun Starter’ı Seç

Uygulamanın ihtiyacını karşılayan en küçük starter kullanılmalıdır:

| İhtiyaç | Eklenecek artifact |
|---|---|
| Yalnız senkron durdurma, sınırlandırma ve metrik | `asl-spring-boot-core-starter` |
| Kalıcı async kuyruk | `asl-spring-boot-mapdb-starter` |
| Merkezi admin server'dan yönetim | `asl-spring-boot-admin-client-starter` |
| Hepsi bir arada kurulum | `asl-spring-boot-starter` |

MapDB starter core starter’ı zaten getirir. Admin client web bağımlılığı eklemez ve `asl.admin.client.enabled=true` yapılana kadar kapalıdır. Hepsi bir arada starter core, MapDB ve admin client'ı içerir.

Uygulama içinde artık admin portalı yoktur; `asl-spring-boot-admin-starter` kaldırıldı. Operatörler merkezi admin server'ı kullanır (bölüm 8).

## 3. GitHub Packages Ayarı

`pom.xml` içine repository tanımını ekle:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/esasmer-dou/async-service-library</url>
    </repository>
</repositories>
```

`~/.m2/settings.xml` dosyasını oluştur veya güncelle:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
    <servers>
        <server>
            <id>github</id>
            <username>${env.GITHUB_ACTOR}</username>
            <password>${env.GITHUB_TOKEN}</password>
        </server>
    </servers>
</settings>
```

Token’da `read:packages` yetkisi bulunmalıdır. Depo private ise repository erişimi de gerekir. Kimlik bilgilerini `pom.xml` içine veya Git’e yazma; ortam değişkeni ya da CI secret kullan.

## 4. Dependency ve Annotation Processor

Core, MapDB ve admin client kullanan örnek kurulum:

```xml
<properties>
    <asl.version>YAYINLANAN_SURUM</asl.version>
</properties>

<dependencies>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-mapdb-starter</artifactId>
        <version>${asl.version}</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-admin-client-starter</artifactId>
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

Processor, descriptor sınıfını, governed wrapper’ı ve Spring sarmalama konfigürasyonunu derleme sırasında üretir. Metot çağrılarını yönlendirmek için runtime classpath taraması yapılmaz.

Spring sarmalama konfigürasyonu yalnız governed arayüz ile `@Service`/`@Component` implementasyonu aynı modülde derlendiğinde üretilir. Arayüz ayrı bir API modülündeyse wrapper bean'ini kendin tanımla:

```java
@Bean
@Primary
MailService mailService(MailServiceImpl implementation, GovernanceRegistry registry) {
    return MailServiceAsl.wrap(implementation, registry);
}
```

ASL startup'ta wrapper'ı olmadan yayınlanan governed arayüzleri kontrol eder ve uyarı loglar (`asl.runtime.unwrapped-governed-bean-policy=WARN`). Eksik bir wrapper'ın governance'ı sessizce devre dışı bırakmaması için production'da `FAIL` kullan.

## 5. Servis Sözleşmesi ve Implementation

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 6)
    String send(String mailId);

    @GovernedMethod(
            asyncCapable = true,
            initialMaxConcurrency = 4,
            initialConsumerThreads = 2
    )
    void publishAudit(@AsyncIdempotencyKey String eventId, String mailId);

    @Excluded
    String localHealth();
}
```

```java
@Service
public class MailServiceImpl implements MailService {
    @Override
    public String send(String mailId) {
        return "sent:" + mailId;
    }

    @Override
    public void publishAudit(String eventId, String mailId) {
        // Retry açıksa bu yan etki idempotent tasarlanmalıdır.
    }

    @Override
    public String localHealth() {
        return "UP";
    }
}
```

Uygulamanın diğer sınıflarında implementation yerine `MailService` interface’ini inject et. Üretilen `@Primary` wrapper çağrıyı somut sınıfa iletir.

### Annotation kuralları

- `@GovernedService.id` kararlı ve benzersiz olmalıdır.
- `@GovernedMethod.id` verilmezse `send(java.lang.String)` biçimindeki tam Java imzası kullanılır.
- `initialMaxConcurrency` pozitif olmalıdır; annotation varsayılanı pratikte sınırsızdır.
- `initialConsumerThreads` sıfır veya daha büyük olmalıdır.
- `@AsyncIdempotencyKey` isteğe bağlıdır; yalnızca bir kez kullanılabilir ve async-capable metottaki `String` parametreyi işaretlemelidir. `null` veya boş key, SYNC ve ASYNC modda aynı şekilde "tekilleştirme yok" anlamına gelir.
- Kalıtılan arayüz metotları, generic metotlar, generic arayüzler ve bir sınıf içindeki nested arayüzler desteklenir. Nested `Outer.Inner` için `Outer_InnerAsl` ve `Outer_InnerAslGoverned` üretilir. Kalıtılan metotlardaki annotation'lar yalnız üst arayüz aynı modülde derlendiğinde dikkate alınır (source retention). Metot içinde tanımlanan local arayüzleri annotation processor göremez; bunlar governed olmaz.
- Redler `reason()` değeri `DISABLED` veya `CONCURRENCY_LIMIT` olan, stack trace içermeyen `MethodUnavailableException` fırlatır; böylece yük altında throttle maliyeti düşük kalır.
- `@Excluded` governance katmanını tamamen atlayarak doğrudan implementation’ı çağırır.
- `asyncCapable=true` yanıt döndüren bir metotta kullanılırsa processor derlemeyi durdurur.

## 6. Senkron ve Async Davranış

| Davranış | `SYNC` | `ASYNC` |
|---|---|---|
| Caller thread | İş mantığını çalıştırır | Payload’u serialize eder ve kalıcı kuyruğa kabul ettirir |
| Dönüş değeri | Normal biçimde döner | Yalnızca `void` desteklenir |
| Hata | Caller’a iletilir | Retry veya failed kaydı olarak saklanır |
| Eşzamanlılık | Aktif çağrıları sınırlar | Lane’deki aktif consumer sayısını sınırlar |
| Dayanıklılık | ASL ek dayanıklılık sağlamaz | Kabul çağrısı MapDB commit’inden sonra döner |

Async wrapper çağrısının dönmesi, işin tamamlandığını değil kuyruğa kalıcı olarak kabul edildiğini gösterir. Kuyruk sınırı, payload limiti, writer doygunluğu, timeout veya storage hatası varsa kabul çağrısı hemen hata verir.

Teslimat at-least-once biçimindedir. Graceful shutdown, crash veya süresi dolan lease bir çalıştırmayı keser; çağrı retry denemesi tüketmeden, en fazla `retry.max-interruptions` kez kuyruğa geri döner. JVM dış sistemdeki yan etki tamamlandıktan sonra fakat queue completion yazılmadan önce kapanabilir. Kararlı bir `@AsyncIdempotencyKey` kullan ve asıl idempotency kontrolünü hedef veritabanında ya da dış API’de uygula.

## 7. MapDB’yi Güvenli Açma

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
      transactions-enabled: true
      memory-mapped-enabled: false
      corruption-policy: FAIL_FAST
      admission:
        max-total-entries: 100000
        max-total-payload-bytes: 536870912
        default-lane-max-entries: 10000
        default-lane-max-payload-bytes: 67108864
        max-single-payload-bytes: 1048576
        max-concurrent-enqueues: 64
        overflow-policy: REJECT
      retry:
        max-attempts: 1
```

Production kuralları:

- Kalıcı iş yükünde `transactions-enabled=true` değerini koru.
- Kuyruk verisini ve olay kanıtını korumak otomatik ayağa kalkmaktan önemliyse `corruption-policy=FAIL_FAST` kullan.
- `ARCHIVE_AND_RESET`, bozuk store setini arşivleyip boş bir kuyruk başlatır. Bu, veri kaybı ile erişilebilirlik arasında açık bir tercihtir.
- `BLOCK_WITH_TIMEOUT` için pozitif `enqueue-timeout-millis` gerekir ve caller thread’i bekletir. Yalnızca bounded upstream thread pool ile kullanılmalıdır.
- Yoğun metotlara lane bazlı sınır ver; tek bir lane’in global bütçeyi tüketmesine izin verme.
- Kuyruğu local kalıcı diskte veya pod’a özel PVC’de tut. Paylaşılan RWX MapDB dosyası kullanma.

Özel Jackson DTO paketleri allowlist’e eklenmeli veya metot için `JacksonAsyncPayloadArgumentSchema` kaydedilmelidir. Java object stream uyumluluk modu allowlist ve object-filter sınırları içerir; uzun ömürlü queue şeması için tercih edilen yöntem değildir.

MapDB starter, uygulamada `ObjectMapper` bean’i varsa onu kullanır. Web katmanı olmayan bir uygulamada böyle bir bean yoksa yalnızca ASL codec’ine özel, modülleri algılayan bir `JsonMapper` oluşturur; bu mapper application context’e bean olarak eklenmez.

## 8. Merkezi Yönetim

ASL'nin uygulama içinde yönetim ucu yoktur. Runtime değişiklikleri (başlatma/durdurma, eşzamanlılık sınırı, async mod, consumer thread sayısı) ve kuyruk işlemleri aşağıda anlatılan merkezi admin server üzerinden yapılır. Başlangıç değerleri `asl.runtime.services.*` ile verilir (bölüm 10). Uygulama içi portal (`asl-spring-boot-admin-starter`, `/asl` ve `/asl/api`) kaldırıldı; eski `asl.admin.services.*` anahtarlarını `asl.runtime.services.*` altına taşı, `asl.admin.client.*` dışındaki diğer `asl.admin.*` anahtarlarını sil.

## Merkezi Admin Server ve Birden Fazla Replica

Tek bir merkezi admin server tüm uygulama ve replica'ları yönetir. Sunucu bir config server gibi çalışır: web portalini sunar, ayarları ve komutları aktarır, üzerinde iş mantığı yoktur.

| Parça | Nerede | İçinde ne var |
|---|---|---|
| `asl-admin-server` | Ayrı, küçük tek süreç (`java -jar asl-admin-server-<sürüm>-exec.jar`) | JDK'nın kendi HTTP sunucusu ve portal sayfası; bellekte bağlı replica'lar, son ham değerleri ve açık komutlar; tek bir MapDB dosyasında telemetri geçmişi, uygulama düzeyi override'lar ve audit. Web framework'ü yok |
| `asl-spring-boot-admin-client-starter` | Her uygulamada | Tek daemon thread. Arayüz yok, dışarıdan erişilen endpoint yok, ek bağımlılık yok (JDK `HttpClient`) |

**İletişim.** Bağlantıyı yalnızca pod açar: açılışta bir kez `POST /asl/v1/register`, ardından her aralıkta `POST /asl/v1/beat`. Gövde kompakt ikili bir mesajdır (protokol modülü `asl-admin-protocol`, yalnız `java.base`) ve HMAC-SHA256 ile imzalanır (`X-ASL-Auth` başlığı, ±60 sn saat farkı). Heartbeat yalnızca değeri değişen metotları taşır; değişiklik yokken gövde 10–15 bayttır ve sunucu gövdesiz `204` döner. Ayarlar ve komutlar cevabın içinde geri gelir. Biri uygulamayı portalde açtığında sunucu o uygulamanın replica'larından daha sık heartbeat ister (varsayılan 2 sn); portal kapanınca normal aralığa (15 sn) döner.

**Kim ne yapar.** Sunucu saklar ve aktarır, hesaplama yapmaz. Her `history.resolution` adımında her replica ve metodun son ham sayaçlarını MapDB'deki sabit boyutlu halkaya yazar. Pod önce uygulama düzeyindeki override'ı, sonra kendine özel override'ı üstüne uygular. Toplamları, oranları, "replica'lar farklı" işaretini ve trend çizgilerini saklanan noktalardan tarayıcı hesaplar. Uzun vadeli metrik ve alarm için yer Prometheus/Grafana'dır.

**Payload pod'dan çıkmaz.** Admin server yalnızca telemetri sayaçlarını saklar. Kuyruk kayıtları, argümanları ve payload'ları replica'nın kendi MapDB kuyruğunda kalır; browse sonuçları portalde gösterilir, sunucunun deposuna yazılmaz.

**Hata davranışı.** Sunucuya ulaşılamazsa client bekleme süresini artırır (en fazla 60 sn), telemetriyi biriktirmez, düşürür; uygulama son uygulanan ayarlarla çalışmaya devam eder. Yönetilen metot çağrıları admin bağlantısını hiçbir zaman beklemez.

**Ayar modeli.** Sunucu yalnızca uygulamanın kendi `application.yml` değerinden farklı olan override'ları tutar: uygulama düzeyinde (kalıcı) veya replica düzeyinde (yalnız bellekte, replica kapanınca silinir). Yeni açılan replica kayıt olurken güncel override'ları alır. Override kaldırılınca metot yml değerine döner. Limitler replica başınadır: üç replica'da `max-concurrency: 20` toplamda 60 eşzamanlı çağrıya izin verir.

**Kuyruk işlemleri.** Her replica kendi MapDB kuyruğuna sahiptir; bu yüzden clear / replay / delete / browse tek bir replica'yı hedefler. Browse, kayıt özetlerini ve hata metinlerini sunucuya gönderdiği için replica `asl.admin.client.queue-browse=true` vermedikçe kapalıdır.

Uygulama tarafı:

```yaml
asl:
  admin:
    client:
      enabled: true
      server-url: http://asl-admin-server:8080
      secret: ${ASL_ADMIN_SECRET}
```

Sunucu tarafı, yalnız ortam değişkenleriyle:

```bash
ASL_ADMIN_SECRET=... ASL_ADMIN_USER=admin ASL_ADMIN_PASSWORD=... \
./asl-admin-server.sh          # veya: java @asl-admin-server.jvmopts -jar asl-admin-server-<sürüm>-exec.jar
```

`asl-admin-server.sh`, `asl-admin-server.cmd` ve `asl-admin-server.jvmopts` dosyaları `asl-admin-server/src/main/dist` altındadır; jar'ın yanında tutulmalıdır. Seçenek dosyası düşük kaynak kullanımı için JVM ayarlarını içerir (`-Xmx16m -Xms4m`, Serial GC, 12 MB code cache ile yalnız C1 JIT, 256 KB thread stack, perf data dosyası yok); betik ayrıca `MALLOC_ARENA_MAX=2` ayarlar. Bellek yetmezse süreç kapanır (`-XX:+ExitOnOutOfMemoryError`) ve Kubernetes onu yeniden başlatır; replica'lar yeniden kayıt olur, uygulama düzeyindeki override'lar MapDB'den geri yüklenir.

Heap, istek hızına değil sunucunun izlediği replica × metot çiftlerinin sayısına bağlıdır. Replica başına 50 metotla ölçüldü (2026-11-22, Windows, JDK 21, 2 sn heartbeat, bir uygulamada portal açık):

| Replica × metot | GC sonrası canlı heap | `-Xmx` | Heartbeat/sn | Sunucu CPU | Heartbeat p99 |
|---|---:|---:|---:|---:|---:|
| 300 × 50 | ~7 MB | `16m` (varsayılan) | 150 | tek çekirdeğin ~%47'si | 5 ms |
| 1.000 × 50 | ~19 MB | `48m` | 500 | tek çekirdeğin ~%66–71'i | 5–8 ms |
| 1.000 × 50, 1 sn heartbeat | — | `96m` | 1.000 | tek çekirdeğin ~%95'i | 14 ms |

Varsayılan iki HTTP thread'i tüm durumlarda yetti. `-Xmx16m` ile 1.000 × 50'de heap tükenir; filo yaklaşık 300 × 50'yi geçmeden `-Xmx` değerini artır. CPU değerleri paylaşılan bir iş istasyonunda ölçüldü, üst sınırdır.

Ortam değişkenleri yerine sunucu bir `asl-admin.properties` dosyası da okuyabilir (başka konum için `--config=<yol>`):

```properties
asl.admin.server.port=8080
asl.admin.server.storage.mode=FILE
asl.admin.server.storage.path=/data/asl-admin.db
asl.admin.server.storage.max-size=64MB
asl.admin.server.history.resolution=1m
asl.admin.server.history.retention=1h
asl.admin.server.history.max-series=2000
asl.admin.server.audit.max-entries=1000
asl.admin.server.audit.retention=7d
asl.admin.server.security.mode=BASIC
```

Geçmiş, her replica ve metot için sabit boyutlu bir halkadır (`retention / resolution` nokta, yerinde üzerine yazılır); bu yüzden dosya sabit bir boyuta ulaşır ve orada kalır. Nokta başına yaklaşık 150 bayt hesaplayın: 1.000 seri × 60 nokta yaklaşık 9 MB eder; MapDB'nin en küçük dosyası 2 MB'tır. `storage.max-size` dolunca yeni seri açılmaz, mevcut seriler dönmeye devam eder. `resolution` veya `retention` değişirse geçmiş sıfırdan başlar. Bu seçeneklerle referans build üzerinde ölçülen değerler (Linux, JDK 21):

| Senaryo | RSS | CPU |
|---|---:|---:|
| Boşta, yaklaşık 0,6 sn'de hazır | ~59 MB | ~0 |
| Yük: 40 replica × 50 metot, 1 sn heartbeat, 5 sn'de bir 2.000 seri yazımı, portal açık | ~70 MB | tek çekirdeğin ~%10'u |
| Aynı yük, düz `java -Xmx32m -jar` | ~101 MB | tek çekirdeğin ~%17'si |

Varsayılan 15 sn heartbeat ve 1 dk geçmiş adımında yük yaklaşık on beş kat düşüktür. Yük senaryosunda canlı heap 6–7 MB civarında kalır; geri kalanı JVM'in kendisidir (kod, sınıf metadata'sı, paylaşılan sınıf arşivi). Çalıştırılabilir jar yaklaşık 17 MB'tır; büyük kısmı MapDB ve kütüphaneleridir.

Kubernetes'te sunucuyu küçük bir ReadWriteOnce volume ile (veya aynı veriyi heap'te tutan `storage.mode=MEMORY` ile volume olmadan) tek replica'lı Deployment olarak çalıştır, portali TLS'li ingress üzerinden aç ve `/asl/v1/*` yollarına yalnızca küme içinden erişilebilsin. Sunucu düz HTTP konuşur; TLS ingress'e aittir.

## 9. Admin Server Portal API'si

Portal sayfası `/asl-admin/api` altındaki küçük bir JSON API'sini çağırır. Scriptler de aynı API'yi kullanabilir; API portal girişinin (`security.mode`) arkasındadır. Değişiklik yapan çağrılar operatör rolü ister ve yabancı bir `Origin` taşıyorsa reddedilir.

| Endpoint | Amaç |
|---|---|
| `GET /server` | Sunucu sayaçları: uygulama, instance, depolama boyutu, geçmiş serisi |
| `GET /apps` | Bağlı uygulamalar |
| `GET /apps/{app}` | Metotlar, son ham sayaçlarıyla replica'lar, override'lar ve komut sonuçları; uygulamayı izleniyor olarak da işaretler (daha sık heartbeat) |
| `GET /apps/{app}/history?serviceId=&methodId=` | Replica başına saklanan telemetri noktaları |
| `POST /apps/{app}/overrides` | Form: `serviceId`, `methodId`, isteğe bağlı `instance`, `enabled`, `async`, `maxConcurrency`, `consumerThreads` |
| `DELETE /apps/{app}/overrides?serviceId=&methodId=[&instance=]` | Override'ı kaldırır; metot yml değerine döner |
| `POST /apps/{app}/commands` | Form: `type` (`BROWSE_QUEUE`, `REPLAY_ENTRY`, `DELETE_ENTRY`, `CLEAR_QUEUE`, `RESET_OBSERVATIONS`), `serviceId`, `methodId`, isteğe bağlı `instance`, `entryId`, `limit`; `commandIds` döner |
| `GET /audit?limit=100` | Son audit kayıtları |

Replica override ve komutları bir sonraki heartbeat'inde alır. Replica'nın `appliedConfigVersion` değeri uygulamanın `configVersion` değerine eşitse (`inSync` true) güncel ayarları uygulamıştır. Komut sonuçları `GET /apps/{app}` cevabındaki `commands` listesinde görünür.

## 10. Ayar Önceliği

1. Replica bağlıyken merkezi admin server'dan gelen override'lar: replica'ya özel olan uygulama düzeyindekinin önüne geçer.
2. `asl.runtime.services.<service>.methods.<signature>.*` başlangıç override’ı (core starter).
3. Annotation değerleri.
4. Kütüphane varsayılanları.

MapDB kapasite, codec, writer, worker, retry, payload ve corruption ayarları başlangıç ayarlarıdır; YAML değişikliği restart gerektirir. Uygulama düzeyindeki override'ları uygulamanın tüm replica'ları, sonradan açılanlar dahil, uygular; replica'ya özel override'ları yalnızca o replica uygular.

Uygulamada hiçbir `asl.*` ayarı yoksa:

- Üretilen wrapper’lar annotation varsayılanlarıyla çalışır.
- Admin client ve MapDB kapalı kalır.
- Yalnızca core starter kullanılıyorsa Web/Thymeleaf bağımlılığı gelmez.
- `MeterRegistry` yoksa Micrometer meter’ları oluşturulmaz.

Bütün property’ler için [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md) dosyasına bak.

## 11. Metrikler

Micrometer bulunduğunda ve `asl.observability.enabled=true` olduğunda şu metrikler açılır:

- `asl.method.success`, `errors`, `rejected`.
- Gözlem sayaçlarını sıfırlamak (`RESET_OBSERVATIONS` komutu) yalnızca portal penceresini yeniler; dışarı aktarılan metrik sayaçları doğru rate hesabı için monotonik kalır.
- `asl.method.in.flight`, `peak.in.flight`, `max.concurrency`, `enabled`, `async.mode`, `consumer.threads`.
- `asl.mapdb.writer.*` komut, queue ve commit metrikleri.
- `asl.mapdb.enqueue.active` ve `asl.mapdb.enqueue.max`.
- `asl.mapdb.worker.*` sağlık, hata ve scheduling metrikleri.
- `asl.mapdb.projection.version`.
- Lane başına `asl.mapdb.lane.pending`, `.lane.in.progress`, `.lane.retry.wait`, `.lane.failed` ve `.lane.oldest.pending.age` (ms); alarmı kuyruk derinliğine değil bekleme yaşına göre kur.
- `asl.mapdb.execution.timeouts`, `asl.mapdb.leases.reclaimed`, `asl.mapdb.released.on.shutdown`.
- `asl.observability.method-latency-enabled=true` olduğunda `asl.method.duration` Timer.
- Spring Boot Actuator varsa `aslMapDb` health bileşeni; writer durduğunda, store bozulduğunda veya coordinator heartbeat 60 sn'den eskiyse DOWN olur, kuyruk birikimi yalnız detay olarak raporlanır. `management.health.aslmapdb.enabled=false` ile kapatılır.
- `asl.mapdb.engine.healthy` (1/0) ve `asl.mapdb.writer.accepting` (1/0). Herhangi biri `0` olduğunda alarm üret: motor fatal bir depolama hatasından sonra durmuştur ve restart gerektirir.

Service ve method değerleri tag olarak kullanılır. Governed metot sayısı `asl.observability.max-method-meters` sınırını aşarsa uygulama başlangıçta hata verir; böylece beklenmeyen metric cardinality büyümesi önlenir.

## 12. Hata ve Recovery Modeli

- Aynı queue dosyasını ikinci süreç açmaya çalışırsa `MapDbStoreLockedException` oluşur; ASL sessizce başka dosyaya geçmez.
- Yetki, lock, desteklenmeyen format, disk ve genel I/O hataları fatal kabul edilir; corruption reset adayı sayılmaz.
- Doğrulanmış header bozulması `FAIL_FAST` altında `MapDbStoreCorruptionException` üretir.
- `ARCHIVE_AND_RESET` seçilmişse database ve WAL dosyaları rollback korumalı tek set olarak taşındıktan sonra boş store açılır.
- Claim edilen iş lease token ile korunur. Eski owner’ın completion veya failure yazısı yeni owner’ın durumunu ezemez.
- Restart sırasında yarım kalmış in-progress kayıt pending duruma alınır ve yeniden çalışabilir.
- Clear ve delete aktif in-progress kayıtları kaldırmaz.

Nightly yıpratma suite’i pending, failed ve in-progress durumlarında sample JVM’ini zorla kapatır. Dosya sisteminin kaybolması, disk-full davranışı ve donanım dayanıklılığı deployment katmanının sorumluluğundadır.

## 13. Build ve Release Kapıları

```powershell
mvn -B -ntp clean verify
.\scripts\run-api-compatibility-gate.ps1
.\scripts\run-data-plane-performance-gate.ps1 -Threads 1,8,32,128
mvn -B -ntp -Psbom -DskipTests package
```

- JMH; doğrudan, excluded, governed, rejected ve exception yollarını karşılaştırır.
- API kapısı public annotation/core/MapDB binary’lerini erişilebilen son release tag’iyle karşılaştırır.
- CycloneDX toplu SBOM dosyaları `target/bom.json` ve `target/bom.xml` altında oluşur.
- CI, Windows/Linux ile Temurin/Semeru Java 21 matrisini çalıştırır.
- `mvn verify`, her modülün kendi testlerinden gelen satır kapsamı için alt sınır uygular (`jacoco.line.minimum`); toplu rapor `asl-coverage` altındadır.
- Pull request'lerde GitHub dependency review çalışır (yüksek önemdeki açıklarda başarısız olur); Dependabot haftalık Maven ve Actions güncellemesi önerir.

Performans kapısı regression’ı yakalar; mutlak sıfır overhead kanıtlamaz. Governed başarılı çağrı policy okuması ve sayaç güncellemesi yapmak zorundadır. Hedef, başarılı senkron yolda ek allocation üretmeyen, ölçülmüş ve sınırlı overhead’dir.

## 14. Kubernetes Kontrol Listesi

- CPU ve memory request/limit değerlerini açıkça ver.
- Worker thread sayısını iş yükünün I/O/CPU niteliğine ve CPU kotasına göre ayarla.
- Queue sınırlarını PVC ve heap bütçesinin altında tut.
- Her MapDB dosyasının tek owner’ı olmasını sağla.
- Graceful termination süresini worker shutdown süresinden kısa tutma.
- Writer hatası, rejected admission, queue depth, lease sağlığı ve commit latency için alarm tanımla.
- HPA replica’ları ortak bir mantıksal kuyruğu tüketecekse dış broker kullan.
- Birden fazla replica'yı pod içi portallerden değil merkezi admin server'dan yönet; sunucuya tek bir küçük ReadWriteOnce volume ver.

## 15. Çalıştırılabilir Örnek

[Consumer sample](asl-consumer-sample/README.md); gerçek servis endpoint’leri, backlog/hata/replay senaryoları ve merkezi admin server üzerinden yürütülen crash-recovery testleri içerir.
