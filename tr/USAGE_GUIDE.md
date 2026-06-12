# Async Service Library Kullanım Rehberi

Bu rehber, ASL’yi kendi Spring Boot projesine eklemek, runtime davranışını anlamak ve tüm override edilebilir ayarları doğru kullanmak için hazırlanmıştır.

## 1. ASL Ne İşe Yarar?

ASL, servis metotlarını yalnızca çağrılabilir Java metotları olmaktan çıkarıp operasyon sırasında yönetilebilir lane’lere dönüştürür.

Temel model şudur:

1. Servis interface’i annotation ile işaretlenir.
2. Annotation processor derleme zamanında wrapper sınıfı üretir.
3. Çağrılar reflection veya dinamik proxy zinciri yerine üretilmiş Java kodundan geçer.
4. Runtime kontrol `GovernanceRegistry`, `ServiceRuntime` ve `MethodRuntime` üzerinden yapılır.
5. Spring Boot starter varsa governed bean otomatik olarak `@Primary` wrapper ile sunulur.

Bu modelin amacı hot path’i basit tutmaktır:

- Her çağrıda reflection dispatch yoktur.
- Her çağrıda annotation taraması yapılmaz.
- Policy parsing runtime’da tekrar tekrar çalışmaz.
- Normal senkron metot çağrısı hâlâ doğrudan delegate metoda gider.

## 2. Güncel Kapsam

Hazır olanlar:

- Derleme zamanında governed wrapper üretimi.
- Metot bazında enable/disable.
- Metot bazında max concurrency kontrolü.
- `void` metotlar için queue-backed async execution.
- MapDB tabanlı kalıcı async queue.
- Payload codec SPI.
- Failed entry replay, delete, clear ve consumer thread resize.
- Spring Boot admin UI ve REST control plane.
- Spring içinde manuel `wrap(...)` yazmadan auto-wrap davranışı.

Bilerek sınırlandırılan alanlar:

- Runtime `SYNC -> ASYNC` geçişi yalnızca `void` metotlar için desteklenir.
- Response dönen metotlar yönetilebilir; fakat async moda alınamaz.
- Spring auto-wrap için her governed interface başına tek stereotype implementasyonu beklenir.
- Orijinal implementation bean context içinde kalır; injected primary bean governed wrapper’dır.
- Admin güvenliği otomatik kurulmaz. Production’da `/asl` ve `/asl/api` uygulama security katmanıyla korunmalıdır.

## 3. Modüller

- `asl-annotations`: Kullanıcıya açık annotation’lar.
- `asl-core`: Runtime governance modeli.
- `asl-processor`: Compile-time source generation.
- `asl-mapdb`: Kalıcı async queue engine.
- `asl-spring-boot-starter`: Spring Boot auto-configuration, admin UI ve REST.
- `asl-sample`: Basit manuel kullanım örneği.
- `asl-consumer-sample`: Spring Boot ile uçtan uca demo.
- `asl-coverage`: Doğrulama ve coverage yardımcı modülü.

## 4. Spring Boot Projesine Adım Adım Ekleme

### 4.1 Dependency’leri ekle

Uygulama modülüne starter ve annotation dependency’lerini ekle:

```xml
<dependencies>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-spring-boot-starter</artifactId>
        <version>0.1.0</version>
    </dependency>
    <dependency>
        <groupId>com.reactor.asl</groupId>
        <artifactId>asl-annotations</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

Aynı workspace içinde geliştirme yapıyorsan sabit versiyon yerine `${project.version}` kullanabilirsin.

### 4.2 GitHub Packages repository tanımını ekle

ASL’yi GitHub Packages üzerinden çekiyorsan consumer projenin `pom.xml` dosyasına repository ekle:

```xml
<repositories>
    <repository>
        <id>github-asl</id>
        <url>https://maven.pkg.github.com/esasmer-dou/async-service-library</url>
        <releases>
            <enabled>true</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### 4.3 Maven `settings.xml` içine GitHub credentials ekle

GitHub Packages anonim indirme yapmaz. `~/.m2/settings.xml` içinde repository `id` değeriyle aynı server tanımı olmalıdır.

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
    <servers>
        <server>
            <id>github-asl</id>
            <username>GITHUB_KULLANICI_ADIN</username>
            <password>GITHUB_CLASSIC_PAT</password>
        </server>
    </servers>
</settings>
```

Notlar:

- `pom.xml` içindeki repository `id` ile `settings.xml` içindeki server `id` aynı olmalıdır.
- Token en az `read:packages` yetkisine sahip olmalıdır.
- `-SNAPSHOT` sürüm kullanılacaksa snapshot resolution açık kalmalıdır.

### 4.4 Annotation processor’u bağla

Wrapper sınıfları derleme zamanında üretildiği için `asl-processor` Maven compiler’a tanıtılmalıdır.

```xml
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
                        <version>0.1.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 4.5 Interface’i annotation ile işaretle

Annotation implementation sınıfına değil, servis interface’ine konur.

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 4, unavailableMessage = "mail lane closed")
    String send(String payload);

    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 0, initialMaxConcurrency = 2)
    void publishAudit(String event);

    @Excluded
    String health();
}
```

### 4.6 Implementation sınıfını normal yaz

İş kuralları implementation sınıfında kalır.

```java
@Service
public class MailServiceImpl implements MailService {
    @Override
    public String send(String payload) {
        return "sent:" + payload;
    }

    @Override
    public void publishAudit(String event) {
        // Kuyruğa alınmaya uygun iş.
    }

    @Override
    public String health() {
        return "UP";
    }
}
```

### 4.7 Uygulama içinde interface tipini inject et

Generated wrapper sınıfını değil, kendi interface tipini kullan.

```java
@RestController
@RequestMapping("/api/mails")
public class MailController {
    private final MailService mailService;

    public MailController(MailService mailService) {
        this.mailService = mailService;
    }

    @PostMapping("/{id}/publish-audit")
    public void publishAudit(@PathVariable String id) {
        mailService.publishAudit(id);
    }
}
```

Runtime’da Spring, primary bean olarak governed wrapper’ı inject eder.

### 4.8 Admin yüzeyini aç

Minimal ayar:

```yaml
asl:
  admin:
    enabled: true
    path: /asl
    api-path: /asl/api
```

Bununla birlikte:

- Admin UI `/asl` altında açılır.
- Admin REST `/asl/api` altında açılır.

### 4.9 Async lane gerekiyorsa MapDB engine’i aç

`asyncCapable = true` olan `void` metotları runtime’da `ASYNC` moda alabilmek için async engine gerekir.

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
      transactions-enabled: true
      memory-mapped-enabled: false
      reset-if-corrupt: true
```

MapDB veya başka bir async engine yoksa `ASYNC` mode kullanılmamalıdır.

### 4.10 Derle ve çalıştır

Önce wrapper üretimi için build al:

```powershell
mvn clean package
```

Sonra uygulamayı başlat:

```powershell
mvn spring-boot:run
```

Admin arayüzü:

```text
http://localhost:8080/asl
```

## 5. Annotation’lar

### `@GovernedService`

Servis interface’ini ASL runtime modeline dahil eder.

Kurallar:

- Interface üzerinde kullanılmalıdır.
- Derleme zamanında işlenir.
- `id` verilmezse service id olarak interface’in fully qualified adı kullanılır.

### `@GovernedMethod`

Metot için başlangıç policy’sini tanımlar.

Alanlar:

- `id`: Açık method id.
- `initiallyEnabled`: Uygulama açıldığında metodun açık mı kapalı mı başlayacağı.
- `initialMaxConcurrency`: Başlangıç max concurrency değeri.
- `unavailableMessage`: Metot kapalıyken caller’a dönecek mesaj.
- `asyncCapable`: Metodun queue-backed async moda uygun olup olmadığı.
- `initialConsumerThreads`: Async consumer başlangıç thread sayısı.

Önemli kural:

- `asyncCapable = true` pratikte `void` metotlar için kullanılmalıdır.
- Async-capable metotlar da uygulama açıldığında `SYNC` modda başlar; `ASYNC` moda runtime’da alınır.

### `@Excluded`

Metodu governance dışına çıkarır. Wrapper bu metodu doğrudan delegate eder.

Kullanım yerleri:

- Health check.
- Hafif metadata çağrıları.
- ASL tarafından durdurulması veya yavaşlatılması istenmeyen yardımcı metotlar.

## 6. Spring Dışı Kullanım

Spring kullanmıyorsan wrapper manuel bağlanabilir.

```java
GovernanceRegistry registry = new GovernanceRegistry();
SampleService delegate = new SampleServiceImpl();
SampleService service = SampleServiceAsl.wrap(delegate, registry);

String value = service.echo("test");
```

Runtime kontrol:

```java
ServiceRuntime serviceRuntime = registry.service(SampleServiceAsl.SERVICE_ID);
MethodRuntime methodRuntime = serviceRuntime.methodById("echo(java.lang.String)");

methodRuntime.disable("temporarily stopped");
methodRuntime.enable();
methodRuntime.setMaxConcurrency(4);
```

Overload varsa veya dış sistemlerden kontrol yapılacaksa `methodById(...)` tercih edilmelidir.

## 7. Async Queue Davranışı

Örnek metot:

```java
@GovernedMethod(asyncCapable = true, initialConsumerThreads = 0, initialMaxConcurrency = 2)
void publish(String value);
```

Davranış:

- `SYNC` modda çağrı inline çalışır.
- `ASYNC` modda çağrı queue’ya yazılır ve worker tarafından daha sonra tüketilir.
- `initialConsumerThreads = 0` queue’nun iş kabul edip tüketmemesi anlamına gelir.
- Consumer thread sayısı runtime’da artırılarak queue eritilebilir.
- `initialMaxConcurrency`, worker’ların aynı anda kaç işi çalıştırabileceğini sınırlar.

Yapılmaması gereken:

- Response dönen metodu runtime’da async moda almaya çalışmak.
- `clear` işlemini replay yerine kullanmak.
- Concurrency artırmayı kalıcı kapasite çözümü gibi görmek.

Response dönen metot neden async olamaz?

- Java metot kontratı caller’a doğrudan bir değer döneceğini söyler.
- Queue’ya yazmak bu kontratı değiştirir.
- Bu durumda ya `Future`/`CompletionStage` gibi farklı bir dönüş tipi gerekir ya da ayrı bir job-id/query modeli gerekir.
- ASL’nin mevcut runtime async modeli, caller’a hemen sonuç döndürmesi gereken metotları sessizce fire-and-forget yapmaz. Bu bilinçli bir güvenlik sınırıdır.

## 8. Admin UI ve REST

Varsayılan UI:

```text
/asl
```

Varsayılan REST base path:

```text
/asl/api
```

Temel endpoint’ler:

```http
GET /asl/api/services
GET /asl/api/services/{serviceId}
POST /asl/api/services/{serviceId}/methods/{methodId}/enable
POST /asl/api/services/{serviceId}/methods/{methodId}/disable?message=faucet-closed
POST /asl/api/services/{serviceId}/methods/{methodId}/concurrency
POST /asl/api/services/{serviceId}/methods/{methodId}/mode
POST /asl/api/services/{serviceId}/methods/{methodId}/consumer-threads
GET /asl/api/services/{serviceId}/methods/{methodId}/buffer
DELETE /asl/api/services/{serviceId}/methods/{methodId}/buffer
DELETE /asl/api/services/{serviceId}/methods/{methodId}/buffer/{entryId}
POST /asl/api/services/{serviceId}/methods/{methodId}/buffer/{entryId}/replay
```

## 9. Konfigürasyon Önceliği

ASL startup davranışını şu sırayla çözer:

1. `application.yml` veya `application.properties`
2. Governed interface üzerindeki annotation değerleri
3. Kütüphane default’ları

Bu nedenle:

- Servisin UI’da görünmesi için `asl.admin.services.*` yazmak zorunda değilsin.
- Annotation üzerindeki değerler yeterliyse method bazlı property override gerekmez.
- Bir alan hem property’de hem annotation’da yoksa aşağıdaki default değer kullanılır.

Hiç konfigürasyon yazmazsan:

- Governed wrapper’lar yine üretilir.
- Servis ve metotlar admin UI’da görünür.
- Admin UI varsayılan olarak `/asl` altında açılır.
- Admin REST varsayılan olarak `/asl/api` altında açılır.
- Method başlangıç değerleri `@GovernedMethod` üzerinden gelir.
- Annotation’da da değer yoksa kütüphane default’u kullanılır.
- `asl.async.mapdb.enabled=true` verilmedikçe MapDB async engine açılmaz.

## 10. Tüm Override Edilebilir Property’ler

### `asl.runtime.*`

| Property | Ne için kullanılır? | Default | Set edilmezse |
| --- | --- | --- | --- |
| `asl.runtime.default-unavailable-message` | Metot kapalıyken method’a özel mesaj yoksa kullanılacak ortak fallback mesajı. | `Method is disabled` | Disabled metotlar özel mesaj yoksa bu mesajla reddedilir. |
| `asl.runtime.max-concurrency-exceeded-message-template` | Max concurrency dolduğunda reject edilen çağrılar için mesaj şablonu. | `Method reached max concurrency: %d` | Concurrency dolduğunda bu şablon kullanılır. |

### `asl.admin.*`

| Property | Ne için kullanılır? | Default | Set edilmezse |
| --- | --- | --- | --- |
| `asl.admin.enabled` | Admin UI ve REST controller’larını açar veya kapatır. | `true` | Admin yüzeyi açık gelir. |
| `asl.admin.path` | Admin HTML arayüzünün path değerini belirler. | `/asl` | UI `/asl` altında çalışır. |
| `asl.admin.api-path` | Admin REST base path değerini belirler. | `/asl/api` | REST `/asl/api` altında çalışır. |
| `asl.admin.buffer-preview-limit` | Buffer preview cevabında dönen maksimum kayıt sayısını sınırlar. | `50` | En fazla 50 kayıt gösterilir. |

### `asl.admin.dashboard.*`

| Property | Ne için kullanılır? | Default | Set edilmezse |
| --- | --- | --- | --- |
| `asl.admin.dashboard.attention-limit` | Dashboard summary içinde dönen attention item sayısını sınırlar. | `8` | Summary en fazla 8 attention item döner. |
| `asl.admin.dashboard.medium-utilization-percent` | Utilization değerinin medium pressure sayılacağı eşiği belirler. | `40` | Medium pressure eşiği %40 olur. |
| `asl.admin.dashboard.high-utilization-percent` | Utilization değerinin high pressure sayılacağı eşiği belirler. | `80` | High pressure eşiği %80 olur. |

### `asl.admin.dashboard.refresh.*`

| Property | Ne için kullanılır? | Default | Set edilmezse |
| --- | --- | --- | --- |
| `asl.admin.dashboard.refresh.live-refresh-enabled` | Sayfa açıldığında metric auto-refresh açık mı başlayacak? | `true` | Live refresh açık başlar. |
| `asl.admin.dashboard.refresh.live-buffer-enabled` | Sayfa açıldığında buffer auto-refresh açık mı başlayacak? | `true` | Live buffer refresh açık başlar. |
| `asl.admin.dashboard.refresh.default-interval-ms` | İlk auto-refresh aralığı. | `5000` | Refresh 5 saniye aralıkla başlar. |
| `asl.admin.dashboard.refresh.interval-options-ms` | UI’da seçilebilecek refresh aralıkları. | `[3000, 5000, 10000, 30000]` | Bu dört seçenek gösterilir. |
| `asl.admin.dashboard.refresh.change-flash-ms` | Değişen alanların highlight süresi. | `1400` | Highlight 1.4 saniye görünür. |
| `asl.admin.dashboard.refresh.success-message-auto-hide-ms` | Başarı mesajının otomatik kapanma süresi. | `1600` | Başarı mesajı 1.6 saniye sonra kapanır. |
| `asl.admin.dashboard.refresh.error-message-auto-hide-ms` | Hata mesajının otomatik kapanma süresi. | `3200` | Hata mesajı 3.2 saniye sonra kapanır. |

### `asl.admin.ui.*`

Bu alanlar admin ekranındaki metinleri değiştirmek için kullanılır. Set edilmezse yerleşik İngilizce metinler kullanılır.

| Property | Ne için kullanılır? | Default |
| --- | --- | --- |
| `asl.admin.ui.page-title` | Tarayıcı sekmesi ve sayfa başlığı. | `ASL Control Plane` |
| `asl.admin.ui.hero-title` | Admin ekranının ana başlığı. | `ASL Control Plane` |
| `asl.admin.ui.hero-description` | Ana başlık altındaki açıklama metni. | `Review governed methods, stop or resume traffic, change concurrency and async settings, and inspect queue state from the same Spring Boot port.` |
| `asl.admin.ui.rest-badge-prefix` | REST path badge ön eki. | `REST:` |
| `asl.admin.ui.empty-title` | Governed servis yoksa gösterilen başlık. | `No governed services registered` |
| `asl.admin.ui.empty-description` | Registry boşken gösterilen açıklama. | `The admin UI is active, but the runtime registry is empty.` |
| `asl.admin.ui.services-title` | Services bölüm başlığı. | `Services` |
| `asl.admin.ui.service-search-placeholder` | Service arama placeholder metni. | `Search services` |
| `asl.admin.ui.service-tab-note` | Service kartındaki kısa açıklama. | `Open this service subform` |
| `asl.admin.ui.service-detail-note` | Metot seçilmeden önce görünen açıklama. | `Select a method from the left subform list to manage its full details.` |
| `asl.admin.ui.methods-title` | Methods bölüm başlığı. | `Methods` |
| `asl.admin.ui.all-label` | Tüm kayıtları gösteren filtre etiketi. | `All` |
| `asl.admin.ui.no-parameters-label` | Parametresiz metot etiketi. | `No parameters` |
| `asl.admin.ui.running-label` | Çalışan metot durum etiketi. | `RUNNING` |
| `asl.admin.ui.stopped-label` | Durdurulmuş metot durum etiketi. | `STOPPED` |
| `asl.admin.ui.sync-mode-label` | Senkron çalışma modu etiketi. | `SYNC` |
| `asl.admin.ui.async-label` | Async durum, mode veya capability etiketi. | `ASYNC` |
| `asl.admin.ui.error-label` | Error filtre/durum etiketi. | `ERROR` |
| `asl.admin.ui.success-label` | Başarılı execution sayacı etiketi. | `Success` |
| `asl.admin.ui.rejected-label` | Reddedilen çağrı sayacı etiketi. | `Rejected` |
| `asl.admin.ui.load-label` | Trafik/load göstergesi etiketi. | `Load` |
| `asl.admin.ui.peak-in-flight-label` | En yüksek eş zamanlı in-flight iş etiketi. | `Peak In Flight` |
| `asl.admin.ui.execution-mode-label` | Mode değiştirme kontrol etiketi. | `Execution Mode` |
| `asl.admin.ui.consumer-threads-label` | Consumer thread kontrol etiketi. | `Consumer Threads` |
| `asl.admin.ui.last-error-label` | Son hata alanı etiketi. | `Last Error` |
| `asl.admin.ui.none-label` | Değer yoksa kullanılan fallback metin. | `none` |
| `asl.admin.ui.method-state-title` | Enable/disable kart başlığı. | `Method State` |
| `asl.admin.ui.start-method-label` | Metodu başlatma butonu. | `Start Method` |
| `asl.admin.ui.stop-method-label` | Metodu durdurma butonu. | `Stop Method` |
| `asl.admin.ui.disable-placeholder` | Disable reason input placeholder metni. | `Reason shown to callers` |
| `asl.admin.ui.method-state-hint` | Method state kartı yardım metni. | `Stopping a method returns the configured message to incoming callers.` |
| `asl.admin.ui.sync-concurrency-title` | Sync concurrency kart başlığı. | `Sync Concurrency` |
| `asl.admin.ui.update-limit-label` | Concurrency güncelleme butonu. | `Update Limit` |
| `asl.admin.ui.sync-concurrency-hint` | Max concurrency açıklama metni. | `Defines how many concurrent executions are allowed for this method.` |
| `asl.admin.ui.async-controls-title` | Async controls kart başlığı. | `Async Controls` |
| `asl.admin.ui.apply-mode-label` | Mode değişikliği apply butonu. | `Apply` |
| `asl.admin.ui.update-consumers-label` | Consumer thread güncelleme butonu. | `Update` |
| `asl.admin.ui.async-hint` | Async kullanım uyarısı. | `Use async mode only for methods designed to be safely queued and consumed later.` |
| `asl.admin.ui.queue-buffer-title` | Queue buffer kart başlığı. | `Queue Buffer` |
| `asl.admin.ui.load-overview-title` | Load overview kart başlığı. | `Load Overview` |
| `asl.admin.ui.no-buffer-message` | Buffer provider yoksa gösterilen mesaj. | `No buffer provider is currently attached to this method.` |
| `asl.admin.ui.clear-buffer-label` | Buffer temizleme butonu. | `Clear Buffer` |
| `asl.admin.ui.replay-entry-label` | Failed entry replay butonu. | `Replay Entry` |
| `asl.admin.ui.delete-entry-label` | Buffer entry silme butonu. | `Delete Entry` |
| `asl.admin.ui.processed-label` | Processed sayacı etiketi. | `Processed` |
| `asl.admin.ui.active-work-label` | Aktif iş sayacı etiketi. | `Active Work` |
| `asl.admin.ui.queue-depth-label` | Queue depth etiketi. | `Queue Depth` |
| `asl.admin.ui.utilization-label` | Utilization etiketi. | `Utilization` |
| `asl.admin.ui.work-pressure-label` | Work pressure etiketi. | `Work Pressure` |
| `asl.admin.ui.worker-capacity-label` | Worker capacity etiketi. | `Worker Capacity` |
| `asl.admin.ui.live-refresh-label` | Live refresh toggle etiketi. | `Live Refresh` |
| `asl.admin.ui.refresh-now-label` | Manuel refresh butonu. | `Refresh Now` |
| `asl.admin.ui.refresh-interval-label` | Refresh interval seçici etiketi. | `Refresh Interval` |
| `asl.admin.ui.refresh-buffer-label` | Buffer refresh aksiyonu etiketi. | `Refresh Buffer` |
| `asl.admin.ui.live-buffer-label` | Live buffer toggle etiketi. | `Live Buffer` |
| `asl.admin.ui.scroll-top-label` | Paneli yukarı sarma butonu. | `Top` |
| `asl.admin.ui.scroll-bottom-label` | Paneli aşağı sarma butonu. | `Bottom` |
| `asl.admin.ui.ready-status-label` | Hazır durum mesajı. | `Ready` |
| `asl.admin.ui.applying-change-message` | Değişiklik isteği sürerken gösterilen mesaj. | `Applying change...` |
| `asl.admin.ui.change-applied-message` | Başarılı değişiklik mesajı. | `Change applied` |
| `asl.admin.ui.request-failed-message` | Başarısız istek mesajı. | `Request failed` |
| `asl.admin.ui.refreshing-metrics-message` | Metrics refresh sırasında gösterilen mesaj. | `Refreshing live metrics...` |
| `asl.admin.ui.metrics-refreshed-message` | Metrics refresh tamamlandığında gösterilen mesaj. | `Metrics refreshed` |
| `asl.admin.ui.refreshing-buffer-message` | Buffer refresh sırasında gösterilen mesaj. | `Refreshing buffer...` |
| `asl.admin.ui.buffer-refreshed-message` | Buffer refresh tamamlandığında gösterilen mesaj. | `Buffer refreshed` |
| `asl.admin.ui.entry-id-label` | Queue entry id etiketi. | `Entry Id` |
| `asl.admin.ui.attempts-label` | Attempt sayacı etiketi. | `Attempts` |
| `asl.admin.ui.codec-label` | Payload codec etiketi. | `Codec` |
| `asl.admin.ui.payload-type-label` | Payload type metadata etiketi. | `Payload Type` |
| `asl.admin.ui.payload-version-label` | Payload schema version etiketi. | `Payload Version` |
| `asl.admin.ui.error-type-label` | Teknik hata tipi etiketi. | `Error Type` |
| `asl.admin.ui.error-category-label` | Normalize edilmiş hata kategorisi etiketi. | `Error Category` |
| `asl.admin.ui.methods-count-suffix` | Service kartındaki method count suffix’i. | `methods` |
| `asl.admin.ui.async-capable-suffix` | Service kartındaki async-capable suffix’i. | `async-capable` |
| `asl.admin.ui.stopped-suffix` | Service kartındaki stopped suffix’i. | `stopped` |
| `asl.admin.ui.methods-with-errors-suffix` | Hatalı method sayısı suffix’i. | `with errors` |
| `asl.admin.ui.pending-label` | Pending queue count etiketi. | `Pending` |
| `asl.admin.ui.failed-label` | Failed queue count etiketi. | `Failed` |
| `asl.admin.ui.in-progress-label` | In-progress queue count etiketi. | `In progress` |

### `asl.admin.services.*`

Bu blok opsiyoneldir. Kullanım amacı, annotation ile gelen startup değerlerini konfigürasyonla override etmektir.

```yaml
asl:
  admin:
    services:
      "mail.service":
        methods:
          "send(java.lang.String)":
            max-concurrency: 6
          "publishAudit(java.lang.String)":
            execution-mode: ASYNC
            consumer-threads: 2
```

Bu blok yoksa:

- Servis ve metotlar yine UI’da görünür.
- Annotation değerleri kullanılır.
- Annotation’da da değer yoksa kütüphane default’u kullanılır.

| Property | Ne için kullanılır? | Set edilmezse |
| --- | --- | --- |
| `asl.admin.services.<serviceId>.methods.<methodId>.enabled` | Metodun startup sırasında enabled mı disabled mı başlayacağını belirler. | `@GovernedMethod(initiallyEnabled)` kullanılır; default `true`. |
| `asl.admin.services.<serviceId>.methods.<methodId>.max-concurrency` | Metot için startup max concurrency değerini belirler. | `@GovernedMethod(initialMaxConcurrency)` kullanılır; default `Integer.MAX_VALUE`. |
| `asl.admin.services.<serviceId>.methods.<methodId>.unavailable-message` | Metot disabled olduğunda dönecek mesajı belirler. | `@GovernedMethod(unavailableMessage)` kullanılır; boşsa `asl.runtime.default-unavailable-message` fallback olur. |
| `asl.admin.services.<serviceId>.methods.<methodId>.execution-mode` | Metodun startup execution mode değerini belirler. | Açık override yoksa `SYNC` başlar. |
| `asl.admin.services.<serviceId>.methods.<methodId>.consumer-threads` | Async-capable metot için startup consumer thread sayısını belirler. | `@GovernedMethod(initialConsumerThreads)` kullanılır; default `1`. |

### `asl.async.mapdb.*`

| Property | Ne için kullanılır? | Default | Set edilmezse |
| --- | --- | --- | --- |
| `asl.async.mapdb.enabled` | Embedded MapDB async execution engine’i açar veya kapatır. | `false` | MapDB async engine oluşturulmaz. |
| `asl.async.mapdb.path` | Queue store dosya yolunu belirler. | `./data/asl-queue.db` | Engine açıksa queue bu dosyada tutulur. |
| `asl.async.mapdb.codec` | Queued item payload serialization codec’ini belirler. | `java-object-stream` | Java object stream codec kullanılır. |
| `asl.async.mapdb.worker-shutdown-await-millis` | Shutdown sırasında worker’ların bitmesi için beklenecek maksimum süre. | `10000` | Worker shutdown en fazla 10 saniye bekler. |
| `asl.async.mapdb.registration-idle-sleep-millis` | Lane henüz register edilmediyse worker backoff süresi. | `100` | Worker 100 ms bekler. |
| `asl.async.mapdb.empty-queue-sleep-millis` | Queue boşken worker backoff süresi. | `50` | Worker 50 ms bekler. |
| `asl.async.mapdb.requeue-delay-millis` | Concurrency doluluğu nedeniyle requeue sonrası worker bekleme süresi. | `75` | Worker 75 ms bekler. |
| `asl.async.mapdb.recovered-in-progress-message` | Restart sonrası stale `IN_PROGRESS` iş failed duruma alınırsa yazılacak mesaj. | `Recovered stale in-progress invocation after restart` | Recovery sırasında bu mesaj kaydedilir. |
| `asl.async.mapdb.transactions-enabled` | MapDB transaction yazımlarını açar. | `true` | Transactional writes açık kalır. |
| `asl.async.mapdb.memory-mapped-enabled` | MapDB için memory-mapped file access açar. | `false` | Memory-mapped IO kapalı kalır. |
| `asl.async.mapdb.reset-if-corrupt` | Recoverable store corruption durumunda startup fail yerine reset/fallback davranışına izin verir. | `false` | Bozuk store startup’ı fail ettirir. |

## 11. Payload Codec

Async engine queued invocation payload’unu byte olarak saklar.

Varsayılan codec:

- `JavaObjectStreamAsyncPayloadCodec`

Spring Boot ile yerleşik alternatif:

- `JacksonJsonAsyncPayloadCodec`

Production için öneri:

- Kalıcı queue kullanılacaksa Jackson codec ve method-specific schema registration tercih edilmelidir.
- Codec veya payload şekli değiştirilecekse rollout öncesinde replay/restart uyumluluğu test edilmelidir.
- Java serialization, hızlı demo için kabul edilebilir; uzun ömürlü production queue için daha kontrollü schema yaklaşımı gerekir.

Spring davranışı:

- `AsyncPayloadCodec` bean yoksa default codec kullanılır.
- Kendi `AsyncPayloadCodec` bean’ini verirsen MapDB onu kullanır.
- `asl.async.mapdb.codec=jackson-json` ve `ObjectMapper` varsa starter Jackson codec’i bağlar.

## 12. Jackson Schema Registry ve Migration

Jackson codec kullanırken stable logical type id ile schema kaydı yapılabilir.

Bu şu durumlarda gerekir:

- Payload sınıfları deployment’lar arasında değişiyorsa.
- Queue’da eski payload’lar varken yeni sürüm çıkacaksan.
- Raw class name yerine stable type id kullanmak istiyorsan.
- Method signature değişimlerinden queue payload’unu daha az etkilenir yapmak istiyorsan.

Temel tipler:

- `JacksonAsyncPayloadSchemaRegistry`
- `JacksonAsyncPayloadArgumentSchema`
- `AbstractJacksonAsyncPayloadArgumentSchema`
- `MapBackedJacksonAsyncPayloadSchemaRegistry`

Örnek:

```java
@Bean
JacksonAsyncPayloadSchemaRegistry jacksonAsyncPayloadSchemaRegistry() {
    MailEventSchema mailEventSchema = new MailEventSchema();
    return MapBackedJacksonAsyncPayloadSchemaRegistry.builder()
            .registerType(mailEventSchema)
            .bind("mail.service", "publish(com.acme.MailEvent)", 0, "mail-event")
            .build();
}
```

## 13. Method ID Formatı

`@GovernedMethod(id = "...")` verilmezse method id şu formatta üretilir:

```text
methodName(java.lang.String,int,...)
```

Örnekler:

- `echo(java.lang.String)`
- `slow(java.lang.String)`
- `publish(java.lang.String)`

Overload varsa dış sistemlerde method name değil, tam method id kullanılmalıdır.

## 14. Hata Davranışı

Method disabled:

- Caller `MethodUnavailableException` alır.
- Mesaj method-specific unavailable message veya runtime fallback mesajıdır.

Max concurrency dolu:

- Çağrı reddedilir.
- Rejected counter artar.

Async engine eksik:

- Metot `ASYNC` moda alınmış ama async engine bağlı değilse ASL sessizce sync’e düşmez; hata üretir.

Failed queued item:

- Method error stats artar.
- Queue item failed duruma geçer.
- Admin UI veya REST üzerinden incelenebilir, silinebilir veya replay edilebilir.

## 15. Operasyonel Kullanım Notları

- Her metodu governance altına almak doğru değildir. Pahalı, riskli veya operasyonel olarak durdurulması gerekebilecek metotları seç.
- Health, metadata ve static config read gibi her zaman açık kalması gereken metotlarda `@Excluded` kullan.
- Backlog görmek istiyorsan consumer thread değerini `0` başlatabilirsin.
- Metodu trafik almaya kapatmak istiyorsan concurrency değerini `0` yapmaya çalışma; metodu disable et.
- Dış sistemler endpoint çağıracaksa service id ve method id değerlerini açık tanımla.
- URL path içinde method id kullanırken parantez ve virgül karakterlerinin encode edilmesi gerektiğini unutma.
- Production’da admin endpoint’lerini authentication ve authorization olmadan açma.

## 16. Referans Dosyalar

- İngilizce README: [../README.md](../README.md)
- Türkçe README: [README.md](README.md)
- Spring Boot sample rehberi: [asl-consumer-sample/README.md](asl-consumer-sample/README.md)
- Sample interface: [../asl-sample/src/main/java/com/reactor/asl/sample/SampleService.java](../asl-sample/src/main/java/com/reactor/asl/sample/SampleService.java)
- Spring auto-wrap testi: [../asl-spring-boot-starter/src/test/java/com/reactor/asl/spring/boot/autowrap/AutoWrapIntegrationTest.java](../asl-spring-boot-starter/src/test/java/com/reactor/asl/spring/boot/autowrap/AutoWrapIntegrationTest.java)
- Admin REST controller: [../asl-spring-boot-starter/src/main/java/com/reactor/asl/spring/boot/AslAdminRestController.java](../asl-spring-boot-starter/src/main/java/com/reactor/asl/spring/boot/AslAdminRestController.java)
- Core auto-configuration: [../asl-spring-boot-starter/src/main/java/com/reactor/asl/spring/boot/AslCoreAutoConfiguration.java](../asl-spring-boot-starter/src/main/java/com/reactor/asl/spring/boot/AslCoreAutoConfiguration.java)
