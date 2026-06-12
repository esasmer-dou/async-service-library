# Benchmark ve Rapor Dokümanları

Bu dizin, ASL kontrol paneli için örnek benchmark raporlarını ve rapor okuma notlarını içerir. Amaç, operatörün “sistem sakin mi, baskı altında mı, müdahale gerekiyor mu?” sorularına hızlı cevap verebilmesidir.

## Kapsam

- Idle durum için örnek kontrol paneli benchmark raporu.
- Backlog ve attention üreten durum için örnek benchmark raporu.
- Gate summary, release note ve trend raporları için beklenen dosya yerleri.
- MapDB yıpratma testlerinin ürettiği rapor dizinleri.

## Ana Dosyalar

- [Idle benchmark örneği](example-control-plane-benchmark-idle.md)
- [Backlog benchmark örneği](example-control-plane-benchmark-backlog.md)

Gerçek çalıştırma çıktıları repoya commit edilmez; yerelde veya CI artifact’i olarak üretilir:

- `tr/reports/real-sample/control-plane-benchmark-gate-summary.md`
- `tr/reports/real-sample/control-plane-benchmark-gate-release-note.md`
- `tr/reports/real-sample/control-plane-benchmark-gate-trend.md`
- `tr/reports/mapdb-abuse/`

## Nasıl Okunmalı?

- Idle raporda pressure değerinin `LOW`, queue depth değerinin `0`, failed entry sayısının `0` olması beklenir.
- Backlog raporda pressure değerinin `HIGH` olması, queue depth ve failed entry sinyallerinin görünmesi beklenir.
- Gate summary, threshold değerlerinin geçilip geçilmediğini tek sayfada gösterir.
- Trend raporu, aynı benchmark’ın zaman içindeki sapmasını görmek için kullanılır.

## İlgili Araçlar

- Benchmark gate script’i: [run-control-plane-benchmark-gate.ps1](../../scripts/run-control-plane-benchmark-gate.ps1)
- Benchmark doğrulama script’i: [assert-control-plane-benchmark.ps1](../../scripts/assert-control-plane-benchmark.ps1)
- MapDB yıpratma suite’i: [run-mapdb-abuse-suite.ps1](../../scripts/run-mapdb-abuse-suite.ps1)
- Nightly destructive CI workflow: [mapdb-abuse-nightly.yml](../../.github/workflows/mapdb-abuse-nightly.yml)

## Not

Otomatik üretilen tarihsel snapshot dosyaları birebir Türkçeleştirilip repoda tutulmaz. Kalıcı dokümanlarda Türkçe açıklama dili korunur; gerçek ölçüm çıktıları ise çalıştırıldığı ortamın artifact’i olarak saklanır.
