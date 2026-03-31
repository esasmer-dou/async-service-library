# Async Service Library Kullanim Rehberi

Bu rehber, `async-service-library` icin Turkce kullanim ve teknik referans ozetidir. Bolum yapisi, Ingilizce rehberle paralel tutulmustur.

## 1. Guncel Durum

Proje su anda:

- governed service modeli sunar
- sync ve async metodlari tek catida yonetir
- Spring Boot ile auto-configure olabilir
- admin UI ve REST yuzeyi uzerinden runtime kontrol saglar

## 2. Kavram

Temel hedef:

- metodlari sadece cagrilabilir degil, yonetilebilir yapmak
- async kuyruk davranisini gozlenebilir ve mudahale edilebilir hale getirmek

## 3. Moduller

- `asl-annotations`
- `asl-processor`
- `asl-core`
- `asl-mapdb`
- `asl-spring-boot-starter`
- sample projeler

## Kendi Spring Boot Projene Adim Adim Ekleme

ASL'yi mevcut Spring Boot projesine eklemek istiyorsan su sirayi izle.

### 1. Bagimliliklari ekle

Uygulama moduline starter ve annotation bagimliliklarini ekle:

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

Ayni workspace icindeki modullerle derliyorsan sabit versiyon yerine `${project.version}` kullanabilirsin.

### 2. Annotation processor'u ekle

ASL wrapper'lari derleme zamaninda urettigi icin `asl-processor` compiler'a baglanmalidir.

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

### 3. Interface uzerine `@GovernedService` koy

Annotation'i implementasyona degil interface'e koy.

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

### 4. Implementasyonu normal sekilde yaz

Is kurallari implementasyon sinifinda kalir.

```java
@Service
public class MailServiceImpl implements MailService {
    @Override
    public String send(String payload) {
        return "sent:" + payload;
    }

    @Override
    public void publishAudit(String event) {
        // arka plan icin uygun is
    }

    @Override
    public String health() {
        return "UP";
    }
}
```

### 5. Uygulamanin diger yerlerinde interface tipini inject et

Generated wrapper sinifini degil, `MailService` interface'ini inject et.

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

Runtime'da Spring primary bean olarak governed wrapper'i enjekte eder.

### 6. Admin plane'i ac

Minimal ASL ayarlarini ekle:

```yaml
asl:
  admin:
    enabled: true
    path: /asl
    api-path: /asl/api
```

Bununla birlikte sunlar acilir:

- `/asl` altinda admin UI
- `/asl/api` altinda admin REST

### 7. Runtime async lane istiyorsan queue'yu ac

`asyncCapable = true` olan metotlari runtime'da `ASYNC` moda alabilmek icin MapDB'yi ac:

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

Async engine yoksa `ASYNC` mod pratikte kullanilamaz.

### 8. Uygulamayi bir kez derle ve baslat

Generated wrapper'larin uretilmesi icin once normal build al:

```powershell
mvn clean package
```

Ardindan uygulamayi baslat:

```powershell
mvn spring-boot:run
```

### 9. Control plane'i ac

Uygulama kalkinca:

- `http://localhost:8080/asl` adresini ac
- servisinin Services listesinde gorundugunu dogrula
- metod detay panelini ac

### 10. Async modu dogru sekilde kullan

Async-capable bir `void` metot icin tipik akis:

1. execution mode'u `ASYNC` yap
2. backlog biriktirmek istiyorsan `consumerThreads = 0` birak
3. kuyrugu bosaltmak istedigin zaman `consumerThreads` arttir
4. calisma hatalarini buffer/failed bolumunden incele
5. gerekirse failed entry'leri replay veya delete et

### 11. Su kurallari unutma

- runtime async gecisi sadece `void` metotlar icin vardir
- response donduren metotlar sync kalmalidir
- health ve her zaman acik kalmasi gereken yardimci metotlarda `@Excluded` kullan
- operatorler veya dis sistemler hedefleyecekse explicit service/method id tanimla
- production'da `/asl` ve `/asl/api` icin kendi security katmanini koy

## 4. Public Annotation’lar

### `@GovernedService`

Bir servis sinifinin governed sarmalayıci ve runtime yonetim modeline dahil edilmesini saglar.

### `@GovernedMethod`

Bir metodun:

- runtime enable/disable
- mode secimi
- async davranis
- queue yonetimi

gibi ozelliklerle isaretlenmesini saglar.

### `@Excluded`

Governed wrapping disinda tutulmasi gereken metodlar veya alanlar icin kullanilir.

## 5. Uretilen Artefact’lar

Processor asamasinda wrapper ve metadata turevleri uretilebilir. Amac runtime’ta refleksiyonla degil, daha net ve kontrol edilebilir bir modelle ilerlemektir.

## 6. Spring Disi Kullanim

Spring olmadan da governed runtime modeli kullanilabilir. Bu senaryoda runtime kontrolunu manuel baglamak gerekir.

### Runtime kontrol

- enable / disable
- mode degisimi
- queue/consumer davranisi
- istatistik ve gozlemleme

## 7. Spring Boot Kullanim

### Gerekli bagimliliklar

Starter ve gerekirse MapDB queue modulu eklenir.

### Servis tanimi

Servisler annotation ile isaretlenir ve starter tarafindan runtime’a baglanir.

### Auto-wrap siniri

Auto-wrap davranisi, bean yapisi ve proxy sinirlari dahilinde calisir; proje icinde dogru bean tanimi onemlidir.

## 8. Async Queue Kullanim

### MapDB queue’yu acmak

Queue persistence isteniyorsa ilgili modulu ve property’leri acmak gerekir.

### Async calisma semantigi

- enqueue
- consume
- fail
- replay
- clear
- recovery

akislari desteklenir.

### Yapilmamasi gerekenler

- clear ile replay ihtiyacini karistirmak
- disabled method’lari belirsiz sure acik tutmak
- concurrency artisini kalici cozum sanmak

## 9. Admin UI ve REST

### UI

Admin panel servis ve metod bazli gorunum sunar.

### REST

Baslica islemler:

- servis listesi
- servis detayi
- method enable
- method disable
- concurrency degisimi
- mode degisimi
- consumer thread degisimi
- buffer preview
- clear buffer
- delete entry
- replay failed entry

## 10. Konfigurasyon

### Admin properties

Admin panel ve REST katmaninin runtime ayarlari buradan belirlenir.

```yaml
asl:
  runtime:
    default-unavailable-message: Method is disabled
    max-concurrency-exceeded-message-template: "Method reached max concurrency: %d"
  admin:
    enabled: true
    path: /asl
    api-path: /asl/api
    buffer-preview-limit: 50
    dashboard:
      attention-limit: 8
      medium-utilization-percent: 40
      high-utilization-percent: 80
      refresh:
        live-refresh-enabled: true
        live-buffer-enabled: true
        default-interval-ms: 5000
        interval-options-ms: [3000, 5000, 10000, 30000]
        change-flash-ms: 1400
        success-message-auto-hide-ms: 1600
        error-message-auto-hide-ms: 3200
```

Anlami:

- `runtime.default-unavailable-message`: governed method'lar icin ortak varsayilan disable mesaji
- `runtime.max-concurrency-exceeded-message-template`: concurrency doldugunda kullanilan ortak reject mesaji sablonu
- `admin.enabled`: admin controller kayitlarini acar
- `admin.path`: Thymeleaf UI taban yolu
- `admin.api-path`: REST taban yolu
- `admin.buffer-preview-limit`: queue onizlemede gosterilecek maksimum kayit sayisi
- `admin.dashboard.attention-limit`: summary icinde dondurulecek maksimum attention item sayisi
- `admin.dashboard.medium-utilization-percent` / `high-utilization-percent`: summary ve UI pressure esikleri
- `admin.dashboard.refresh.*`: admin sayfasinin varsayilan live refresh ve mesaj zamanlamalari

### MapDB async properties

Queue persistence, dosya yolu ve benzeri ayarlar burada tanimlanir.

```yaml
asl:
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
      worker-shutdown-await-millis: 10000
      registration-idle-sleep-millis: 100
      empty-queue-sleep-millis: 50
      requeue-delay-millis: 75
      recovered-in-progress-message: Recovered stale in-progress invocation after restart
      transactions-enabled: true
      memory-mapped-enabled: false
      reset-if-corrupt: true
```

Anlami:

- `enabled`: MapDB async engine olusturur
- `path`: queue persistence dosya yolu
- `codec`: `java-object-stream` veya `jackson-json` secimini yapar
- `worker-shutdown-await-millis`: close/resize sirasinda worker drain icin beklenecek sure
- `registration-idle-sleep-millis`: lane henuz register edilmemisse worker bekleme suresi
- `empty-queue-sleep-millis`: queue bos oldugunda worker bekleme suresi
- `requeue-delay-millis`: max concurrency doluyken requeue sonrasi worker bekleme suresi
- `recovered-in-progress-message`: stale `IN_PROGRESS` kayitlar failed'e cekildiginde yazilan recovery mesaji
- `transactions-enabled`: MapDB transaction yazimlarini acar
- `memory-mapped-enabled`: acikca istenirse mmap erisimini acar
- `reset-if-corrupt`: recoverable store bozulmalarinda fatal yerine startup recovery/reset davranisini acar

### Payload codec

Java veya Jackson tabanli codec secimi, tasinan veri ve uyumluluk gereksinimine gore yapilir.

### Jackson schema registry ve migration hook’lari

Payload seklinin zaman icinde degismesi durumunda migration yapisi devreye alinir.

## 11. Runtime API Ayrintilari

Runtime API, method lane’lerinin yonetimi, queue introspection ve kontrol yuzeyi icin kullanilir.

## 12. Method ID’leri

Method lane secimi ve REST/runtime mutasyonlari icin method ID formati onemlidir.

## 13. Hata Davranisi

### Method disabled

Traffic reddedilir veya beklenen koruyucu davranis calisir.

### Async engine missing

Async altyapi yoksa isaretli lane’lerde hata beklentisi dogar.

### Failed queued item

Failed item queue veya recovery akisina dusebilir.

## 14. Onerilen Kullanim Kaliplari

- kritik metodlari governed hale getir
- operator akislarini benchmark ve sample ile dene
- replay / clear / disable prosedurlerini yazili hale getir

## 15. Operasyonel Notlar

- queue durumu tek basina yorumlanmamali; summary ve attention ile birlikte okunmali
- benchmark gate’i release oncesi kalite kapisi olarak kullan

## 16. Hazirlik Degerlendirmesi

Mevcut durum:

- teknik olarak guclu
- operator odakli
- urun polish olarak halen gelistirilebilir

## 17. Minimal Uctan Uca Ornek

Pratikte sample projeler ve README’ler en hizli baslangic noktasidir:

- [README.md](E:\ReactorRepository\async-service-library\README.md)
- [asl-consumer-sample/README.md](E:\ReactorRepository\async-service-library\tr\asl-consumer-sample\README.md)

## 18. Referans Dosyalar

- [README.md](E:\ReactorRepository\async-service-library\tr\README.md)
- [USAGE_GUIDE.md](E:\ReactorRepository\async-service-library\tr\USAGE_GUIDE.md)
