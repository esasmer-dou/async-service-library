# Async Service Library Dokumantasyon Dizini (TR)

Bu dizin, `async-service-library` projesinin Turkce dokumantasyon aynasidir.

Amac:

- kullanici odakli dokumanlari Turkce sunmak
- teknik ve operasyonel rehberleri Turkceye eslemek
- benchmark, saglik, durum ve karsilastirma raporlarinin Turkce kopyalarini tutmak

## Dokumantasyon Kurali

Bu repo icin dokumantasyon politikasi:

1. Kok dizinde veya alt dizinlerde yeni bir kalici Markdown dokumani eklenirse, `tr/` altinda Turkce karsiligi da eklenmelidir.
2. Var olan bir dokuman degisirse, Turkce es dokuman da ayni turde guncellenmelidir.
3. Otomatik uretilen benchmark ozetleri ve operator ciktilari icin mumkun oldugunda Turkce ciktilar da uretilmelidir.
4. Gecici veya tarihsel snapshot dosyalari birebir cevrilmek zorunda degildir; ancak bunlari uretecek ana dokumanlarin ve ana raporlarin Turkce karsiliklari bulunmalidir.

## Standart Kaynaklari

- [DOKUMANTASYON_STANDARTLARI.md](E:\ReactorRepository\async-service-library\tr\DOKUMANTASYON_STANDARTLARI.md)
- [TERIM_SOZLUGU.md](E:\ReactorRepository\async-service-library\tr\TERIM_SOZLUGU.md)
- [EN_TR_ESLEME_TABLOSU.md](E:\ReactorRepository\async-service-library\tr\EN_TR_ESLEME_TABLOSU.md)

## Dizin Yapisi

- [README.md](E:\ReactorRepository\async-service-library\tr\README.md)
- [USAGE_GUIDE.md](E:\ReactorRepository\async-service-library\tr\USAGE_GUIDE.md)
- [ADMIN_CONTROL_PLANE_GUIDE.md](E:\ReactorRepository\async-service-library\tr\ADMIN_CONTROL_PLANE_GUIDE.md)
- [BENCHMARK_RUNBOOK.md](E:\ReactorRepository\async-service-library\tr\BENCHMARK_RUNBOOK.md)
- [PRODUCTION_HARDENING_CHECKLIST.md](E:\ReactorRepository\async-service-library\tr\PRODUCTION_HARDENING_CHECKLIST.md)
- [SECURITY_CHECKLIST.md](E:\ReactorRepository\async-service-library\tr\SECURITY_CHECKLIST.md)
- [OPERATIONAL_UPGRADE_RUNBOOK.md](E:\ReactorRepository\async-service-library\tr\OPERATIONAL_UPGRADE_RUNBOOK.md)
- [PROJECT_HEALTH_REPORT.md](E:\ReactorRepository\async-service-library\tr\PROJECT_HEALTH_REPORT.md)
- [PROJECT_STATUS_REPORT.md](E:\ReactorRepository\async-service-library\tr\PROJECT_STATUS_REPORT.md)
- [asl-consumer-sample/README.md](E:\ReactorRepository\async-service-library\tr\asl-consumer-sample\README.md)
- [asl-consumer-sample/CHAOS_TEST_GUIDE.md](E:\ReactorRepository\async-service-library\tr\asl-consumer-sample\CHAOS_TEST_GUIDE.md)
- [reports/README.md](E:\ReactorRepository\async-service-library\tr\reports\README.md)
- [mapdb-abuse-nightly.yml](E:\ReactorRepository\async-service-library\.github\workflows\mapdb-abuse-nightly.yml)

## Not

Bu dizin, repo icindeki Ingilizce kaynak dokumanlarin Turkce karsiliklarini sunar. Teknik ornekler, dosya yollari ve kod bloklari gerektiginde kaynak dokumandaki yapiyla uyumlu tutulur.

Operasyonel dogrulama icin MapDB yipratici suite gece calisan CI workflow'una da baglanmistir; raporlar artifact olarak saklanir.

## Runtime Konfigurasyon Notu

Kutudan ciktigi haliyle gelen runtime davranislari artik property tabanlidir. Varsayilanlar korunur; ancak:

- runtime unavailable / concurrency mesajlari
- admin dashboard attention ve utilization esikleri
- live refresh ve buffer refresh zamanlamalari
- MapDB worker pacing ve startup recovery mesajlari

uygulama property'leri ile disaridan degistirilebilir.

## Public Dagitim

Public repo:

- [github.com/esasmer-dou/async-service-library](https://github.com/esasmer-dou/async-service-library)

Package ve release akisi:

- `CI` push ve pull request dogrulamasi yapar
- `Publish Package` Maven artefact'larini GitHub Packages'a yollar
- `Release` `v*` tag'lerinde versiyonlu artefact ve release asset'leri yayinlar

GitHub Packages adresi:

- `https://maven.pkg.github.com/esasmer-dou/async-service-library`
