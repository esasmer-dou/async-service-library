# Benchmark Calisma Rehberi

Bu rehber, ASL kontrol duzlemi icin tekrarlanabilir benchmark ve gate dogrulama akislarini Turkce olarak aciklar.

## Hedef

Bu benchmark sistemi maksimum throughput kanitlamak icin degil, asagidaki operator odakli sinyalleri olcmek icindir:

- admin UI tepki sureleri
- admin REST tepki sureleri
- summary tutarliligi
- idle ve backlog durumlarinda queue gorunurlugu
- gate esitigi asimlarinda otomatik fail davranisi

## Gereksinimler

- sample uygulamasi veya benchmark hedefi ayakta olmali
- PowerShell ve Java ortami hazir olmali
- benchmark scriptleri repo kokunden calistirilmali

## Ana Komutlar

### Tek rapor uretimi

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark.ps1
```

### Tam idle + backlog benchmark paketi

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\generate-sample-benchmark-suite.ps1
```

### Gate ile tam dogrulama

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-control-plane-benchmark-gate.ps1 `
  -StartSample `
  -Warmup 1 `
  -Iterations 3
```

`-StartSample` kullanildiginda benchmark suite sample'i izole bir queue yolu ile acir:

- `reports/real-sample/sample-runtime/benchmark-queue.db`

Boylece benchmark akisinda zorunlu surec kapatma gerekse bile ana demo queue dosyasi (`./data/asl-consumer-sample-queue.db`) etkilenmez.
Suite yalnizca kendisinin baslattigi sample surecini kapatir.

## Profil Yapisi

Varsayilan profil dosyasi:

- [control-plane-benchmark-thresholds.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-thresholds.json)

Desteklenen temel profiller:

- `local`
- `ci`
- `staging`

Opsiyonel overlay ornegi:

- [control-plane-benchmark-thresholds.override.example.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-thresholds.override.example.json)

## Profil Cozumleme

Profil secim sirasi:

1. `-Profile`
2. `ASL_BENCHMARK_PROFILE`
3. release tag esleme
4. branch esleme
5. `defaultProfile`

Kurallar:

- `main`, `master`, `develop`, `development` -> `ci`
- `release/*`, `hotfix/*` -> `staging`
- `v*`, `release-*` tag’leri -> `staging`
- diger durumlar -> `local`

Profil cozumleme dosyasi:

- [control-plane-benchmark-profile-resolution.json](E:\ReactorRepository\async-service-library\scripts\control-plane-benchmark-profile-resolution.json)

## Environment Variable Override’lari

- `ASL_BENCHMARK_PROFILE`
- `ASL_BENCHMARK_PROFILE_RESOLUTION_PATH`
- `ASL_BENCHMARK_BRANCH_NAME`
- `ASL_BENCHMARK_RELEASE_TAG`
- `ASL_BENCHMARK_THRESHOLDS_PATH`
- `ASL_BENCHMARK_EXTRA_THRESHOLDS_PATHS`
- `ASL_BENCHMARK_IDLE_ADMIN_SUMMARY_AVG_MAX_MS`
- `ASL_BENCHMARK_BACKLOG_ADMIN_SUMMARY_AVG_MAX_MS`
- `ASL_BENCHMARK_MINIMUM_BACKLOG_QUEUE_DEPTH`
- `ASL_BENCHMARK_MINIMUM_BACKLOG_FAILED_ENTRIES`
- `ASL_BENCHMARK_MINIMUM_BACKLOG_HIGH_ATTENTION_ITEMS`
- `ASL_BENCHMARK_READY_TIMEOUT_SECONDS`

## Uretilen Ciktilar

Makine okunur:

- [control-plane-benchmark-gate-summary.json](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-summary.json)

Insan okunur:

- [control-plane-benchmark-gate-summary.md](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-summary.md)
- [control-plane-benchmark-gate-release-note.md](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-release-note.md)
- [control-plane-benchmark-gate-trend.md](E:\ReactorRepository\async-service-library\reports\real-sample\control-plane-benchmark-gate-trend.md)
- [history](E:\ReactorRepository\async-service-library\reports\real-sample\history)

## Kabul Kriterleri

Gate su durumlarda fail etmelidir:

- idle pressure `LOW` degilse
- backlog pressure `HIGH` degilse
- backlog queue/failure esitigi saglanmiyorsa
- `Admin Summary` ortalama gecikmesi threshold’u asiyorsa

## Yorumlama

### Saglikli idle

- queue depth = 0
- failed entries = 0
- overall pressure = `LOW`

### Temsili backlog

- queue depth threshold’u asilir
- failed entries gorunur
- high attention item vardir
- overall pressure = `HIGH`

## CI Davranisi

Workflow:

- [control-plane-benchmark-gate.yml](E:\ReactorRepository\async-service-library\.github\workflows\control-plane-benchmark-gate.yml)

Bu workflow:

- branch/tag’e gore uygun profili secebilir
- input, variable veya secret uzerinden override alabilir
- artifact olarak benchmark ciktilarini yukler
- GitHub job summary alanina Markdown ozet ekleyebilir
