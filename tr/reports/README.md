# Benchmark ve Rapor Dokumanlari (TR)

Bu dizin, benchmark ve operator raporlarinin Turkce karsiliklarini toplar.

## Kapsam

- ornek idle/backlog benchmark dokumanlari
- gate summary, release-note ve trend raporlari
- benchmark yorumlama yardimcilari
- MapDB yipratici kill/crash suite raporlari

## Ana Dosyalar

- [example-control-plane-benchmark-idle.md](E:\ReactorRepository\async-service-library\tr\reports\example-control-plane-benchmark-idle.md)
- [example-control-plane-benchmark-backlog.md](E:\ReactorRepository\async-service-library\tr\reports\example-control-plane-benchmark-backlog.md)
- `tr/reports/real-sample/control-plane-benchmark-gate-summary.md` (yerelde/CI artefact'i olarak uretilir, repoya commit edilmez)
- `tr/reports/real-sample/control-plane-benchmark-gate-release-note.md` (yerelde/CI artefact'i olarak uretilir, repoya commit edilmez)
- `tr/reports/real-sample/control-plane-benchmark-gate-trend.md` (yerelde/CI artefact'i olarak uretilir, repoya commit edilmez)
- `tr/reports/mapdb-abuse/` (yerelde/CI artefact'i olarak uretilir, repoya commit edilmez)

## Not

Otomatik tarihsel snapshotlar birebir aynalanmaz; ama ana rapor sablonlari ve mevcut operator ciktilari Turkce olarak tutulur.

MapDB yipratici test ciktilari:

- cikti kok dizini: `tr/reports/mapdb-abuse/`
- calistirma scripti: [run-mapdb-abuse-suite.ps1](E:\ReactorRepository\async-service-library\scripts\run-mapdb-abuse-suite.ps1)
- gece calisan CI workflow'u: [mapdb-abuse-nightly.yml](E:\ReactorRepository\async-service-library\.github\workflows\mapdb-abuse-nightly.yml)
