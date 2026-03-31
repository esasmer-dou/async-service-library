# ASL Consumer Sample (TR)

Bu dokuman, sample uygulamanin Turkce kullanim ozetidir.

## Amac

`asl-consumer-sample`, ASL’nin:

- governed service davranisi
- async queue
- runtime kontrol
- admin panel
- benchmark ve chaos senaryolari

gibi ozelliklerini gostermek icin vardir.

## Baslangic

Repo kokunden sample’i calistir:

```powershell
mvn -pl asl-consumer-sample spring-boot:run
```

Admin arayuzu:

- `http://localhost:8080/asl`

Summary API:

- `http://localhost:8080/asl/api/summary`

## Runtime Konfigurasyon Yuzeyi

Sample uygulama, ana out-of-the-box runtime ayarlarini [`application.yml`](E:\ReactorRepository\async-service-library\asl-consumer-sample\src\main\resources\application.yml) icinde disari acik halde gosterir:

```yaml
asl:
  runtime:
    default-unavailable-message: Method is disabled
    max-concurrency-exceeded-message-template: "Method reached max concurrency: %d"
  admin:
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
  async:
    mapdb:
      worker-shutdown-await-millis: 10000
      registration-idle-sleep-millis: 100
      empty-queue-sleep-millis: 50
      requeue-delay-millis: 75
      recovered-in-progress-message: Recovered stale in-progress invocation after restart
      transactions-enabled: true
      memory-mapped-enabled: false
      reset-if-corrupt: true
```

Bu konfigurasyon bilerek ayrintili tutulur; boylece kullanici hangi runtime varsayilanlarinin artik kod degistirmeden override edilebildigini net gorebilir.

## Benchmark

Tek benchmark:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark.ps1
```

Tam suite:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-sample-benchmark-suite.ps1
```

Gate:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1 `
  -StartSample `
  -Warmup 1 `
  -Iterations 3
```

## Profil Notlari

- `local`
- `ci`
- `staging`

Profil verilmezse branch/tag tabanli cozumleme devreye girebilir.

MapDB icin yipratici kill/crash suite'i calistirmak icin:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-mapdb-abuse-suite.ps1
```

Bu suite kasitli olarak:

- pending work varken JVM'i zorla oldurur
- failed entry persist edildikten sonra JVM'i zorla oldurur
- is `IN_PROGRESS` durumundayken JVM'i zorla oldurur
- ayni queue dosyasi uzerinde coklu kill / restart donguleri dener
- bilerek bozulmus header'a sahip queue dosyasi ile startup recovery'yi dener

Uretilen raporlar yerelde / CI artefact'i olarak yazilir:

- `reports/mapdb-abuse/`
- `tr/reports/mapdb-abuse/`

## Chaos / Recovery

Detayli senaryolar:

- [CHAOS_TEST_GUIDE.md](E:\ReactorRepository\async-service-library\tr\asl-consumer-sample\CHAOS_TEST_GUIDE.md)

## Dikkat

- benchmark scriptleri sample queue durumunu resetlemeye calisir
- `-StartSample` ile acilan benchmark akisi artik ana demo queue dosyasini kullanmaz; izole queue yolu `reports/real-sample/sample-runtime/benchmark-queue.db` altinda acilir
- benchmark suite sadece kendisinin baslattigi sample surecini kapatir; disaridan calisan sample'i zorla kapatmaz
- yavas ortamlarda `ASL_BENCHMARK_READY_TIMEOUT_SECONDS` arttirilabilir
- sample uygulama MapDB'yi guvenlik agirlikli ayarlarla acar: `transactions-enabled=true` ve `memory-mapped-enabled=false`
- sample uygulamada `asl.async.mapdb.reset-if-corrupt=true` aciktir; bozuk demo queue dosyasi acilista arsivlenip yerine temiz store olusturulur
- header corruption durumunda bozuk dosya `*.corrupt-*` olarak kenara alinur; dosya temiz tasinamiyorsa uygulama yeni bir recovery queue dosyasina duser
- acilis sirasinda recovery yapilirsa admin arayuzunde operasyonel bir banner gorunur; burada recovery durumu, arsivlenen veya recovery icin kullanilan dosya yolu ve aktif queue store yolu acikca gosterilir
- tamamen temiz bir manuel baslangic istersen `./data/asl-consumer-sample-queue.db` dosyasini uygulama oncesi silebilirsin
