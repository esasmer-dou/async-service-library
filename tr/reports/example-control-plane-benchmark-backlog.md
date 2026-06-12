# Örnek Kontrol Paneli Benchmark Raporu - Backlog

Bu rapor, queue’da bilinçli olarak backlog oluşturulduğunda beklenen kontrol paneli sinyallerini gösterir.

## Beklenen Durum

- Overall pressure: `HIGH`
- Queue depth eşik değerin üzerinde
- Failed entry sayısı görünür
- En az bir high-priority attention item üretilmiş durumda

## Yorum

Bu görünüm, kontrol panelinin problemli runtime durumunu operatöre açık şekilde taşıdığını kanıtlamalıdır. Backlog, failed entry ve attention sinyalleri birlikte görünmüyorsa benchmark gate gevşek kalmış olabilir.
