# ASL örnek uygulaması

**Dil:** [English](../../asl-consumer-sample/README.md) | Türkçe · [Dokümantasyon ana sayfası](../README.md#dokümantasyon)

Servis katmanında ASL kullanan, çalıştırılabilir bir Spring Boot uygulaması. Kütüphaneyi denemek içindir; production için değildir.

## Neler denenebilir

| Servis | Metot | Gösterdiği |
|---|---|---|
| `mail.service` | `send(java.lang.String)` | max concurrency 6 olan SYNC metot (yml'den) |
| `mail.service` | `publishAudit(java.lang.String)` | ASYNC ve 2 consumer ile başlayan metot (yml'den) |
| `customer.service` | `publishWelcome(java.lang.String)` | SYNC başlayan async-capable metot |
| `quote.service` | `quote(java.lang.String)` | `CompletableFuture` metot: SYNC ve ASYNC'te aynı cevap |
| `chaos.service` | `emit(java.lang.String)` | bilerek hata verdirilebilen veya yavaşlatılabilen metot |

Ayarlar [`application.yml`](../../asl-consumer-sample/src/main/resources/application.yml) dosyasındadır.

## Çalıştırma

Depo kök dizininden:

```powershell
mvn -pl asl-consumer-sample -am spring-boot:run
```

API `http://localhost:8080` adresinde başlar.

## Admin portalıyla çalıştırma

Lokal bir admin server başlatın (demo ayarları: giriş yok, veri yalnız bellekte):

```powershell
mvn -pl asl-admin-server -am package -DskipTests
java -jar asl-admin-server/target/asl-admin-server-0.1.0-SNAPSHOT-exec.jar `
  --port=8090 --secret=local-demo-secret-0001 --security.mode=NONE --storage.mode=MEMORY
```

Örnek uygulamayı ona bağlı başlatın:

```powershell
$env:ASL_ADMIN_CLIENT_ENABLED = "true"
$env:ASL_ADMIN_CLIENT_SERVER_URL = "http://localhost:8090"
$env:ASL_ADMIN_CLIENT_SECRET = "local-demo-secret-0001"
mvn -pl asl-consumer-sample -am spring-boot:run
```

`http://localhost:8090/asl-admin/` adresini açıp `asl-consumer-sample`'ı seçin. `security.mode=NONE` yalnızca lokal demo içindir.

## Uygulamalı örnekler

**İşi kuyruğa al, sonra bırak**

1. Portalda `customer.service › publishWelcome` için Tune: Execution mode `ASYNC`, Consumer threads `0`.
2. Bir müşteri oluşturup publish'i birkaç kez çağırın. Kuyruk sayısı artar; henüz hiçbiri çalışmaz.
   ```powershell
   $c = Invoke-RestMethod -Method Post http://localhost:8080/api/customers -ContentType application/json `
        -Body '{"email":"a@company.com","fullName":"A User"}'
   1..5 | % { Invoke-RestMethod -Method Post "http://localhost:8080/api/customers/$($c.id)/publish-welcome" }
   ```
3. Portalda metodun kuyruğunu açıp kayıtlara bakın.
4. Consumer threads'i `1` yapın ve kuyruğun boşalmasını izleyin.

**Hata verdir, sonra replay et**

1. Sonraki 3 `chaos.service › emit` çağrısını başarısız yapın:
   ```powershell
   Invoke-RestMethod -Method Post http://localhost:8080/api/test/scenarios/chaos/emit -ContentType application/json `
     -Body '{"failuresRemaining":3,"processingDelayMillis":0}'
   ```
2. Portalda `emit`'i ASYNC'e geçirip 3 kez çağırın: `POST http://localhost:8080/api/chaos/emit/{id}`.
3. Portal 3 başarısız kayıt gösterir. Kuyruğu açıp **Bulk… → Replay matching** kullanın (Failed, kategori `BUSINESS`).

**SYNC ve ASYNC'te aynı cevap**

```powershell
Invoke-RestMethod http://localhost:8080/api/quotes/ACME     # {"symbol":"ACME","quote":"ACME=…"}
```

Portalda `quote.service › quote`'u ASYNC'e geçirip tekrar çağırın: cevap aynıdır, ama artık kuyruktan gelir. `http://localhost:8080/api/quotes/FAIL-1` bir hata gösterir; `/api/quotes/unclaimed` ise hiçbir çağıranın beklemediği sonuçları gösterir.

## Test endpoint'leri

Bunlar yalnızca örnek uygulamada vardır ve kuyruk davranışını taklit etmek içindir.

| Endpoint | Etkisi |
|---|---|
| `POST /api/test/scenarios/mail/audit` | sonraki `publishAudit` çalıştırmalarını başarısız yapar veya yavaşlatır |
| `POST /api/test/scenarios/customer/welcome` | `publishWelcome` için aynısı |
| `POST /api/test/scenarios/inventory/snapshot` | `publishSnapshot` için aynısı |
| `POST /api/test/scenarios/chaos/emit` | `emit` için aynısı |
| `POST /api/test/scenarios/reset` | örnek uygulamanın tüm durumunu sıfırlar |
| `GET /api/chaos/storage-recovery` | açılışta bozuk bir kuyruk dosyasının arşivlenip arşivlenmediğini gösterir |

Gövde: `{"failuresRemaining": 1, "processingDelayMillis": 500}`.

İş endpoint'leri: `/api/mails`, `/api/customers`, `/api/inventory`, `/api/quotes/{symbol}`, `/api/health`.

## Testler

- [AslConsumerSampleIntegrationTest](../../asl-consumer-sample/src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleIntegrationTest.java): başarı, hata, replay, silme, temizleme, consumer değişimi, durdurma, SYNC ve ASYNC.
- [AslConsumerSampleChaosRecoveryTest](../../asl-consumer-sample/src/test/java/com/reactor/asl/consumer/sample/AslConsumerSampleChaosRecoveryTest.java): bekleyen ve başarısız işler restart'ı atlatır; çalışan iş graceful shutdown'da biter.
- Zorla öldürme kurtarma testi: `pwsh -File .\scripts\run-mapdb-abuse-suite.ps1` (CI'da her gece çalışır; bkz. [raporlar](../reports/README.md)).
- Adım adım manuel kurtarma testleri: [CHAOS_TEST_GUIDE.md](CHAOS_TEST_GUIDE.md).

## Notlar

- İş verileri (e-postalar, müşteriler) bellektedir. Kuyruk `./data/asl-consumer-sample-queue.db` konumunda bir MapDB dosyasıdır; temiz bir başlangıç için silin.
- Örnek uygulama `payload.summary-policy: CODEC` verir; böylece portal argüman metnini gösterir. Production servisleri bu metnin gösterilmesi güvenli değilse varsayılan `TYPE_ONLY` ile kalmalıdır.
- Örnek uygulama `corruption-policy: ARCHIVE_AND_RESET` verir: bozuk bir kuyruk dosyası kenara alınır ve yenisi oluşturulur. Production varsayılan `FAIL_FAST` ile kalmalıdır.
