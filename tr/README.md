# Async Service Library Dokumantasyon Dizini (TR)

Bu dizin, `async-service-library` projesinin Turkce dokumantasyon aynasidir.

Amac:

- kullanici odakli dokumanlari Turkce sunmak
- temel kullanim bilgisini Turkce sunmak

## Dokumantasyon Kurali

Bu repo icin dokumantasyon politikasi:

1. Kok dizinde veya alt dizinlerde yeni bir kalici Markdown dokumani eklenirse, `tr/` altinda Turkce karsiligi da eklenmelidir.
2. Var olan bir dokuman degisirse, Turkce es dokuman da ayni turde guncellenmelidir.
3. Otomatik uretilen benchmark ozetleri ve operator ciktilari icin mumkun oldugunda Turkce ciktilar da uretilmelidir.
4. Gecici veya tarihsel snapshot dosyalari birebir cevrilmek zorunda degildir; ancak bunlari uretecek ana dokumanlarin ve ana raporlarin Turkce karsiliklari bulunmalidir.

## Dizin Yapisi

- [README.md](E:\ReactorRepository\async-service-library\tr\README.md)
- [USAGE_GUIDE.md](E:\ReactorRepository\async-service-library\tr\USAGE_GUIDE.md)
- [asl-consumer-sample/README.md](E:\ReactorRepository\async-service-library\tr\asl-consumer-sample\README.md)
- [asl-consumer-sample/CHAOS_TEST_GUIDE.md](E:\ReactorRepository\async-service-library\tr\asl-consumer-sample\CHAOS_TEST_GUIDE.md)
- [reports/README.md](E:\ReactorRepository\async-service-library\tr\reports\README.md)

## Not

Bu dizin, repo icindeki Ingilizce kaynak dokumanlarin Turkce karsiliklarini sunar. Teknik ornekler, dosya yollari ve kod bloklari gerektiginde kaynak dokumandaki yapiyla uyumlu tutulur.

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
