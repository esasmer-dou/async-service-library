# Guvenlik Kontrol Listesi

## Erisim Kontrolu

- admin endpoint’leri acik internete kontrolsuz acilmadi
- runtime mutasyonlari sadece yetkili kullanicilara acik
- gizli bilgiler log’lara dusurulmuyor

## Veri ve Kuyruk

- queue verisinin saklandigi alan uygun izinlerle korunuyor
- payload codec secimi veri hassasiyetine gore degerlendirildi
- schema migration hook’lari guvenli sekilde test edildi

## Operasyonel Guvenlik

- replay / clear / delete islemleri icin denetlenebilir prosedur var
- benchmark ve test endpoint’leri uretimde gereksiz acik degil
- sample odakli senaryo endpoint’leri uretime tasinmiyor
