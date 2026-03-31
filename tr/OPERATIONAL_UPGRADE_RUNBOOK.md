# Operasyonel Yukseltme Rehberi

Bu rehber, ASL tabanli dagitimlarda surum gecisi ve operasyonel yukseltme sirasinda izlenecek ana adimlari Turkce ozetler.

## Yukseltme Once

- mevcut surum ve hedef surum farki dokumante edilir
- queue yapisi, codec ve schema etkileri kontrol edilir
- benchmark gate ve kabul testleri mevcut surumde basarili oldugu kaydedilir

## Yukseltme Sirasinda

1. runtime durumunu gozlemle
2. kritik lane’lerde queue baskisi yoksa ilerle
3. gerekirse kontrollu disable veya drain uygula
4. yeni surumu ac
5. admin summary, service detail ve queue sinyallerini kontrol et

## Yukseltme Sonrasi

- idle benchmark tekrar calistir
- representative backlog senaryosu dogrula
- replay / failed item davranisi beklenen gibi mi kontrol et
- benchmark gate artifact’lerini arsivle

## Geri Donus

- upgrade sonrasi queue davranisi bozulursa rollback karari queue guvenligiyle birlikte alinmali
- stale item veya replay ihtiyaci varsa operator proseduru izlenmeli
