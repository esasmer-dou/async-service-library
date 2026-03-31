# Uretim Sertlestirme Kontrol Listesi

Bu kontrol listesi, ASL tabanli uygulamalari uretime cikarmadan once kontrol edilmesi gereken ana basliklari ozetler.

## Runtime

- queue storage secimi bilincli yapildi
- retry/replay proseduru tanimli
- failed queue temizligi icin operator proseduru yazili
- concurrency ve consumer thread degerleri test edildi

## Operasyon

- admin panel erisimi yetkilendirildi
- runtime mutasyonlari icin rol/sorumluluk net
- incident durumunda replay / clear / disable sirası belirlendi

## Dayaniklilik

- baslangic sonrasi stale state recovery test edildi
- pending queue ve failed queue recovery senaryolari denendi
- graceful shutdown davranisi gozlemlendi

## Dogrulama

- benchmark gate calisti
- idle ve backlog raporlari incelendi
- kabul testleri gecti

## Dokumantasyon

- Ingilizce ve Turkce dokumanlar uyumlu
- operasyon rehberleri guncel
- sample/demo komutlari dogru
