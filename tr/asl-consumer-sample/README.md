# ASL Consumer Sample

[English](../../asl-consumer-sample/README.md) | [Türkçe](README.md)

Bu doküman, `asl-consumer-sample` uygulamasını Türkçe olarak açıklar. Sample uygulama, ASL’nin Spring Boot içinde nasıl davrandığını canlı olarak görmek, admin arayüzünü denemek ve async kuyruk senaryolarını test etmek için hazırlanmıştır.

## Amaç

Bu sample şunları gösterir:

- Aynı Spring Boot uygulamasında birden fazla governed servis kullanımı.
- Governed interface üzerinden normal Spring injection akışı.
- Senkron çalışan governed metotlar.
- Runtime sırasında `ASYNC` moda alınabilen `void` metotlar.
- Governance dışında bırakılan `@Excluded` metotlar.
- Admin UI, admin REST ve summary endpoint’lerinin aynı porttan çalışması.
- Benchmark, backlog, hata, replay ve recovery senaryoları.

## Uygulamayı Çalıştırma

Repo kök dizininden:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

Uygulama varsayılan olarak şu adreslerden açılır:

- Uygulama API’si: `http://localhost:8080`
- Admin arayüzü: `http://localhost:8080/asl`
- Admin REST: `http://localhost:8080/asl/api`
- Summary snapshot: `http://localhost:8080/asl/api/summary`

## Runtime Ayarları

Sample uygulama, kullanıcıların runtime davranışı kod değiştirmeden nasıl ayarlayabileceğini göstermek için temel ASL ayarlarını [`application.yml`](../../asl-consumer-sample/src/main/resources/application.yml) içinde açık biçimde gösterir.

```yaml
asl:
  runtime:
    default-unavailable-message: Method is disabled
    max-concurrency-exceeded-message-template: "Method reached max concurrency: %d"
  admin:
    enabled: true
    buffer-preview-limit: 50
    security:
      # Yalnızca yerel demo içindir. Production uygulaması AslAdminAccessPolicy sağlamalıdır.
      allow-unauthenticated: true
    dashboard:
      attention-limit: 8
      medium-utilization-percent: 40
      high-utilization-percent: 80
      refresh:
        live-refresh-enabled: true
        live-buffer-enabled: true
        default-interval-ms: 5000
        interval-options-ms: [3000, 5000, 10000, 30000]
  async:
    mapdb:
      worker-shutdown-await-millis: 10000
      registration-idle-sleep-millis: 100
      empty-queue-sleep-millis: 50
      requeue-delay-millis: 75
      recovered-in-progress-message: Recovered stale in-progress invocation after restart
      transactions-enabled: true
      memory-mapped-enabled: false
      corruption-policy: ARCHIVE_AND_RESET
      admission:
        max-concurrent-enqueues: 64
```

Sample yalnızca demo davranışını açık kılmak için gerekli ayarları gösterir. Desteklenen bütün property’ler, kesin varsayılan değerleri ve verilmediklerinde oluşacak davranış [CONFIGURATION_REFERENCE.md](../CONFIGURATION_REFERENCE.md) içinde açıklanmıştır.

`allow-unauthenticated: true` yalnızca bu yerel demo için bilerek açılmıştır. Production uygulamasında bu değer `false` bırakılmalı ve bir `AslAdminAccessPolicy` bean’i sağlanmalıdır; aksi durumda admin yüzeyi güvenli biçimde açılmaz.

## Kontrol Paneli Akışı

Admin arayüzünü şu sırayla kullanmak daha sağlıklıdır:

1. `/asl` adresini aç.
2. Genel özet kartlarını kontrol et.
3. Pressure, queue depth ve failed entry sinyallerini incele.
4. Problemli servisi seç.
5. İlgili metot detayını aç.
6. En küçük güvenli müdahaleyi uygula: concurrency düşürme/yükseltme, metodu durdurma, `ASYNC` moda alma, consumer thread değiştirme veya failed entry replay.

Makine tarafından okunabilir summary çıktısı:

```powershell
curl http://localhost:8080/asl/api/summary
```

## Örnek API Çağrıları

Mail taslağı oluştur:

```powershell
curl -X POST http://localhost:8080/api/mails `
  -H "Content-Type: application/json" `
  -d "{\"recipient\":\"user@company.com\",\"subject\":\"Welcome\",\"body\":\"Hello from ASL\"}"
```

Mailleri listele:

```powershell
curl http://localhost:8080/api/mails
```

Tek mail kaydını getir:

```powershell
curl http://localhost:8080/api/mails/{mailId}
```

Mail gönder:

```powershell
curl -X POST http://localhost:8080/api/mails/{mailId}/send
```

Audit event yayınla:

```powershell
curl -X POST http://localhost:8080/api/mails/{mailId}/publish-audit
```

Audit event kayıtlarını görüntüle:

```powershell
curl http://localhost:8080/api/mails/audit-events
```

Health:

```powershell
curl http://localhost:8080/api/health
```

Customer oluştur:

```powershell
curl -X POST http://localhost:8080/api/customers `
  -H "Content-Type: application/json" `
  -d "{\"email\":\"mustafa@company.com\",\"fullName\":\"Mustafa Korkmaz\"}"
```

Inventory kaydı oluştur veya güncelle:

```powershell
curl -X POST http://localhost:8080/api/inventory `
  -H "Content-Type: application/json" `
  -d "{\"sku\":\"SKU-1\",\"title\":\"Thermal Printer\",\"available\":7}"
```

## Async Demo

`publishAudit(...)` metodu async-capable tanımlıdır; fakat uygulama açıldığında `SYNC` modda başlar.

Kuyruk modunu görmek için:

1. `http://localhost:8080/asl` adresini aç.
2. `publishAudit(java.lang.String)` metodunu bul.
3. Execution mode değerini `ASYNC` yap.
4. Backlog görmek istiyorsan consumer thread sayısını `0` bırak.
5. Kuyruğu eritmek istiyorsan consumer thread sayısını `1` veya daha yüksek bir değere çıkar.
6. `/api/mails/{mailId}/publish-audit` çağrısını yap.
7. Queue state bilgisini admin ekranından veya `/asl/api` üzerinden izle.

Aynı yaklaşımı şu metotlarda da deneyebilirsin:

- `customer.service -> publishWelcome(java.lang.String)`
- `inventory.service -> publishSnapshot(java.lang.String)`
- `chaos.service -> emit(java.lang.String)`

## Async Senaryo Laboratuvarı

Sample uygulama, gerçek MapDB-backed async engine üzerinde kontrollü senaryolar üretmek için test endpoint’leri içerir.

Senaryo endpoint’leri:

- Mail audit: `POST /api/test/scenarios/mail/audit`
- Customer welcome: `POST /api/test/scenarios/customer/welcome`
- Inventory snapshot: `POST /api/test/scenarios/inventory/snapshot`
- Chaos emit: `POST /api/test/scenarios/chaos/emit`
- Tüm sample state reset: `POST /api/test/scenarios/reset`

İstek gövdesi:

```json
{
  "failuresRemaining": 1,
  "processingDelayMillis": 500
}
```

Mail audit için bir defalık async hata üret:

```powershell
curl -X POST http://localhost:8080/api/test/scenarios/mail/audit `
  -H "Content-Type: application/json" `
  -d "{\"failuresRemaining\":1,\"processingDelayMillis\":0}"
```

Inventory snapshot işini yavaşlatıp backlog ve `IN_PROGRESS` kayıtları gözlemle:

```powershell
curl -X POST http://localhost:8080/api/test/scenarios/inventory/snapshot `
  -H "Content-Type: application/json" `
  -d "{\"failuresRemaining\":0,\"processingDelayMillis\":400}"
```

## Önerilen Test Matrisi

Bu sample ile şu akışlar doğrulanmalıdır:

- Async başarı akışı.
- Async hata sonrası işin failed buffer’a düşmesi.
- Failed entry replay sonrası işin tekrar çalışması.
- Consumer thread `0` iken pending queue birikmesi.
- Tek bir queued entry’nin silinmesi.
- In-progress olmayan kayıtların clear ile temizlenmesi.
- Consumer sayısı artırıldığında pending queue’nun erimesi.
- Disabled async metodun kuyruğa yazmadan önce isteği reddetmesi.
- Async-capable metodun `SYNC` modda inline çalışması ve hatayı inline üretmesi.

İlgili test:

- [AslConsumerSampleIntegrationTest.java](../../asl-consumer-sample/src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleIntegrationTest.java)

## Control Plane Benchmark

Çalışan sample’a karşı tek benchmark raporu üret:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark.ps1
```

Idle ve backlog raporlarını birlikte üret:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-sample-benchmark-suite.ps1
```

Threshold gate ile birlikte çalıştır:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1 `
  -StartSample `
  -Profile local `
  -Warmup 1 `
  -Iterations 3
```

Benchmark davranışı:

- `-StartSample` verilirse script sample uygulamayı kendisi başlatır.
- Varsayılan benchmark portu meşgulse boş bir loopback portu otomatik seçilir.
- Benchmark için izole queue dosyası kullanılır: `reports/real-sample/sample-runtime/benchmark-queue.db`.
- Ana demo queue dosyası bu akışta bozulmaz: `./data/asl-consumer-sample-queue.db`.
- Script yalnızca kendi başlattığı sample sürecini kapatır.
- Yavaş CI ortamlarında `ASL_BENCHMARK_READY_TIMEOUT_SECONDS` artırılabilir.
- Gate profilleri: `local`, `ci`, `staging`.

## MapDB Yıpratma Testleri

İzole sample runtime üzerinde destructive MapDB abuse suite çalıştır:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-mapdb-abuse-suite.ps1
```

Bu suite bilerek şu durumları dener:

- Pending iş varken JVM’i zorla öldürür.
- Failed async entry persist edildikten sonra JVM’i zorla öldürür.
- İş `IN_PROGRESS` durumundayken JVM’i zorla öldürür.
- Aynı queue dosyası üzerinde tekrar eden kill/restart döngüleri çalıştırır.
- Header’ı bilerek bozulmuş queue dosyasıyla startup recovery davranışını test eder.

Raporlar yerelde veya CI artifact’i olarak şu dizinlere yazılır:

- `reports/mapdb-abuse/`
- `tr/reports/mapdb-abuse/`

Nightly CI workflow:

- [mapdb-abuse-nightly.yml](../../.github/workflows/mapdb-abuse-nightly.yml)

## Chaos ve Recovery

Sample uygulama restart tabanlı recovery senaryolarını da içerir:

- Pending queue restart sonrası korunur.
- Failed queue restart sonrası korunur ve replay edilebilir.
- Graceful shutdown sırasında in-flight işler kontrollü ele alınır.
- Stale `IN_PROGRESS` recovery motor seviyesinde doğrulanır.

İlgili testler:

- [AslConsumerSampleChaosRecoveryTest.java](../../asl-consumer-sample/src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleChaosRecoveryTest.java)
- [MapDbAsyncExecutionEngineCoverageTest.java](../../asl-mapdb/src/test/java/com/reactor/asl/mapdb/MapDbAsyncExecutionEngineCoverageTest.java)

Manuel senaryolar:

- [CHAOS_TEST_GUIDE.md](CHAOS_TEST_GUIDE.md)

## Notlar

- Mail storage bellek içindedir; production persistence örneği değildir.
- Async queue MapDB ile `./data/asl-consumer-sample-queue.db` dosyasına yazılır.
- Sample güvenlik öncelikli MapDB ayarlarıyla gelir: `transactions-enabled=true`, `memory-mapped-enabled=false`.
- Sample `corruption-policy=ARCHIVE_AND_RESET` kullanır. Yalnızca kesin olarak doğrulanmış header bozulmalarında dosya `*.corrupt-*` adıyla arşivlenir ve ardından temiz store oluşturulur.
- Dosya kilidi, yetki, disk ve genel I/O hataları corruption sayılmaz. Sistem başka bir queue dosyasına sessizce geçmek yerine açılışı durdurur.
- Bozuk store güvenli biçimde arşivlenemiyorsa startup başarısız olur; veri kaybını gizleyen bir fallback queue yolu kullanılmaz.
- Recovery başarıyla tamamlanırsa admin arayüzü arşivlenen ve aktif store yollarını operasyonel banner üzerinde gösterir.
- Tamamen temiz manuel başlangıç için uygulamayı başlatmadan önce `./data/asl-consumer-sample-queue.db` dosyasını silebilirsin.
- Sample built-in Jackson async payload codec kullanır.
- Bu uygulama kütüphaneyi denemek içindir; production business persistence örneği olarak tasarlanmamıştır.
