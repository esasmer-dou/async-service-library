# ASL Consumer Sample

[English](../../asl-consumer-sample/README.md) | [Türkçe](README.md)

ASL'i servis katmanında kullanan, çalıştırılabilir bir Spring Boot örneğidir.

## Neyi Gösterir

- tek Spring Boot uygulamasında governed servisler
- governed Spring servislerinin enjekte edilmesi
- servis arayüzleri üzerinden doğrudan REST API
- senkron governed metotlar
- MapDB kuyruğu üzerinde async çalışabilen governed metotlar
- hariç tutulan metotlar
- merkezi admin server'dan yönetim; sample'ın kendisi hiçbir admin ucu açmaz

## Çalıştırma

Repo kökünden:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

Uygulama API'si `http://localhost:8080` adresinde açılır. Tek başına çalışırken sample yml ayarlarıyla çalışır; ayarları çalışırken değiştirmek için merkezi admin server'a bağlanır.

## Merkezi Admin Server'dan Yönetim

Admin server'ı başlat (yerel demo ayarları: giriş yok, veri bellekte):

```powershell
mvn -pl asl-admin-server -am package -DskipTests
java -jar asl-admin-server/target/asl-admin-server-0.1.0-SNAPSHOT-exec.jar `
  --port=8090 --secret=local-demo-secret-0001 --security.mode=NONE --storage.mode=MEMORY
```

Sample'ı ona bağlı başlat:

```powershell
$env:ASL_ADMIN_CLIENT_ENABLED = "true"
$env:ASL_ADMIN_CLIENT_SERVER_URL = "http://localhost:8090"
$env:ASL_ADMIN_CLIENT_SECRET = "local-demo-secret-0001"
mvn -pl asl-consumer-sample -am spring-boot:run
```

Portal `http://localhost:8090/asl-admin/` adresindedir; sample `asl-consumer-sample` adıyla görünür. `security.mode=NONE` yalnızca yerel demo içindir; production'da varsayılan `BASIC` giriş (veya kimlik proxy'si arkasında `PROXY_HEADER`) kullanılır ve portal TLS'li bir ingress arkasında çalışır.

## Runtime Ayarları

Sample'ın ayarları [`application.yml`](../../asl-consumer-sample/src/main/resources/application.yml) dosyasındadır:

```yaml
asl:
  runtime:
    default-unavailable-message: Method is disabled
    max-concurrency-exceeded-message-template: "Method reached max concurrency: %d"
    services:
      "[mail.service]":
        methods:
          "[send(java.lang.String)]":
            max-concurrency: 6
          "[publishAudit(java.lang.String)]":
            execution-mode: ASYNC
            consumer-threads: 2
  admin:
    client:
      enabled: false
      application: asl-consumer-sample
      queue-browse: true
  async:
    mapdb:
      enabled: true
      codec: jackson-json
      path: ./data/asl-consumer-sample-queue.db
      transactions-enabled: true
      memory-mapped-enabled: false
      corruption-policy: ARCHIVE_AND_RESET
      payload:
        summary-policy: CODEC
```

`asl.runtime.services` başlangıç değerleridir. Portalde verilen override'lar sample bağlıyken bunların üstüne uygulanır; override kaldırılınca metot bu değerlere döner. `queue-browse: true` portalin kuyruk kayıtlarını listelemesine izin verir; bu durumda kayıt özetleri ve hata metinleri admin server'a gider. Sample `asl.async.mapdb.payload.summary-policy: CODEC` verir; böylece portalin kuyruk penceresi her çağrının argümanlarının kısa metnini gösterir. Production servisleri bu metnin gösterilmesi güvenli değilse varsayılan `TYPE_ONLY` (yalnız payload tipi) ile kalmalıdır. Desteklenen tüm property'ler, varsayılanları ve verilmediklerindeki davranış [CONFIGURATION_REFERENCE.md](../CONFIGURATION_REFERENCE.md) dosyasındadır.

## Ana API'ler

Taslak oluştur:

```powershell
curl -X POST http://localhost:8080/api/mails `
  -H "Content-Type: application/json" `
  -d "{\"recipient\":\"user@company.com\",\"subject\":\"Welcome\",\"body\":\"Hello from ASL\"}"
```

Mailleri listele: `curl http://localhost:8080/api/mails`

Tek mail: `curl http://localhost:8080/api/mails/{mailId}`

Mail gönder: `curl -X POST http://localhost:8080/api/mails/{mailId}/send`

Audit olayı yayınla: `curl -X POST http://localhost:8080/api/mails/{mailId}/publish-audit`

Audit olayları: `curl http://localhost:8080/api/mails/audit-events`

Sağlık: `curl http://localhost:8080/api/health`

Müşteri kaydet:

```powershell
curl -X POST http://localhost:8080/api/customers `
  -H "Content-Type: application/json" `
  -d "{\"email\":\"mustafa@company.com\",\"fullName\":\"Mustafa Korkmaz\"}"
```

Stok kaydı ekle/güncelle:

```powershell
curl -X POST http://localhost:8080/api/inventory `
  -H "Content-Type: application/json" `
  -d "{\"sku\":\"SKU-1\",\"title\":\"Thermal Printer\",\"available\":7}"
```

## Async Demo

`customer.service -> publishWelcome(java.lang.String)` async çalışabilir ve `SYNC` modda başlar.

Kuyruğa almak için, sample admin server'a bağlıyken:

1. `http://localhost:8090/asl-admin/` adresini aç ve `asl-consumer-sample` uygulamasını seç.
2. `publishWelcome(java.lang.String)` metodunu `ASYNC` moda al, consumer thread sayısını `0` yap.
3. `POST /api/customers/{customerId}/publish-welcome` çağrısını birkaç kez yap; bekleyen sayısı artar.
4. Kuyruğu portalden listele, sonra consumer thread sayısını `1` yap ve kuyruğun boşalmasını izle.

Aynı akış `mail.service -> publishAudit(java.lang.String)` (yml'de zaten `ASYNC`), `inventory.service -> publishSnapshot(java.lang.String)` ve `chaos.service -> emit(java.lang.String)` için de geçerlidir.

Portal, scriptlerin de çağırabildiği küçük bir JSON API'si kullanır ([USAGE_GUIDE.md bölüm 9](../USAGE_GUIDE.md#9-admin-server-portal-apisi)):

```powershell
$api = "http://localhost:8090/asl-admin/api/apps/asl-consumer-sample"
Invoke-RestMethod -Method Post "$api/overrides" -Body @{
  serviceId = "customer.service"; methodId = "publishWelcome(java.lang.String)"; async = "true"; consumerThreads = "1" }
Invoke-RestMethod "$api"
```

## Async Senaryo Laboratuvarı

Sample, MapDB tabanlı motora karşı gerçek kuyruk davranışını canlandırmak için yalnızca test amaçlı senaryo uçları sunar:

- mail audit: `POST /api/test/scenarios/mail/audit`
- müşteri karşılama: `POST /api/test/scenarios/customer/welcome`
- stok snapshot: `POST /api/test/scenarios/inventory/snapshot`
- chaos emit: `POST /api/test/scenarios/chaos/emit`
- tüm sample durumunu sıfırla: `POST /api/test/scenarios/reset`
- açılışta depo kurtarma durumu: `GET /api/chaos/storage-recovery`

İstek gövdesi:

```json
{
  "failuresRemaining": 1,
  "processingDelayMillis": 500
}
```

Örnek, mail audit için bir async hatası üret:

```powershell
curl -X POST http://localhost:8080/api/test/scenarios/mail/audit `
  -H "Content-Type: application/json" `
  -d "{\"failuresRemaining\":1,\"processingDelayMillis\":0}"
```

## Önerilen Async Test Matrisi

- async başarılı yol
- async hata kaydı failed kuyruğa taşır
- replay failed kaydı geri alır ve başarıyla çalıştırır
- consumer thread `0` bekleyen kuyruk biriktirir
- delete tek kaydı siler
- clear çalışmakta olmayan tüm kayıtları siler
- consumer sayısını artırmak bekleyen işi boşaltır
- kapatılmış async metot kuyruğa almadan reddeder
- async çalışabilen metot `SYNC` modda satır içinde çalışır ve satır içinde hata verir

Entegrasyon testi bu akışları uygulamanın kendi bean'leri üzerinden doğrular:

- [AslConsumerSampleIntegrationTest.java](../../asl-consumer-sample/src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleIntegrationTest.java)

## Kurtarma Stres Testi

Repo kökünden (PowerShell 7):

```powershell
pwsh -File .\scripts\run-mapdb-abuse-suite.ps1
```

Test merkezi admin server'ı ve admin client'ı açık sample'ı derleyip başlatır. Mod, consumer, listeleme ve replay işlemleri admin server üzerinden yapılır. Bilerek şunları yapar:

- iş beklerken JVM'i sert biçimde öldürür
- failed kayıt kalıcı hale geldikten sonra JVM'i öldürür
- iş `IN_PROGRESS` iken JVM'i öldürür
- aynı kuyruk dosyasında birkaç kez öldür/yeniden başlat döngüsü yapar
- sample'ı başlığı bilerek bozulmuş bir kuyruk dosyasıyla başlatır

Raporlar yerelde / CI artifact'i olarak `reports/mapdb-abuse/` ve `tr/reports/mapdb-abuse/` altına yazılır. Test her gece CI'da çalışır: [mapdb-abuse-nightly.yml](../../.github/workflows/mapdb-abuse-nightly.yml). Ayrıntı: [tr/reports/README.md](../reports/README.md).

## Chaos ve Kurtarma

Sample, kendi içinde bir async laboratuvar servisi etrafında yeniden başlatmaya dayalı kurtarma testleri de içerir:

- bekleyen kuyruk uygulama yeniden başlatılınca korunur
- failed kuyruk yeniden başlatmadan sonra kalır ve replay edilebilir
- çalışan iş graceful shutdown sırasında tamamlanır
- eskimiş `IN_PROGRESS` kurtarması motor seviyesinde doğrulanır

İlgili testler:

- [AslConsumerSampleChaosRecoveryTest.java](../../asl-consumer-sample/src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleChaosRecoveryTest.java)
- [MapDbAsyncExecutionEngineCoverageTest.java](../../asl-mapdb/src/test/java/com/reactor/asl/mapdb/MapDbAsyncExecutionEngineCoverageTest.java)

Elle adım adım: [CHAOS_TEST_GUIDE.md](CHAOS_TEST_GUIDE.md)

## Notlar

- Mail deposu bellektedir
- Async kuyruk `./data/asl-consumer-sample-queue.db` üzerinde MapDB ile tutulur
- Sample MapDB'yi güvenli modda kullanır: `transactions-enabled=true`, `memory-mapped-enabled=false`
- Sample `corruption-policy=ARCHIVE_AND_RESET` kullanır: başlığı bozuk olduğu doğrulanan dosya yeni depo oluşturulmadan önce `*.corrupt-*` olarak arşivlenir
- Kilit, izin, disk ve genel I/O hataları bozulma sayılmaz; depo sessizce değiştirilmez, açılış durur
- Bozuk depo temiz biçimde arşivlenemezse açılış durur
- Açılışta bir depo arşivlendiyse `GET /api/chaos/storage-recovery` orijinal ve arşiv yolunu döner
- Tamamen temiz bir başlangıç için sample'ı başlatmadan önce `./data/asl-consumer-sample-queue.db` dosyasını sil
- Sample hazır Jackson async payload codec'ini kullanır
- Bu proje kütüphaneyi denemek içindir, production iş verisi saklamak için değildir
