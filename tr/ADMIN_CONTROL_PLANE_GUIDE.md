# Admin Kontrol Duzlemi Rehberi

Bu dokuman, ASL admin panelinin ve kontrol duzlemi yuzeyinin operator gozuyle nasil kullanilacagini Turkce olarak aciklar.

## Amac

Admin kontrol duzlemi su sorulara hizli yanit vermelidir:

- Hangi servisler govern ediliyor?
- Hangi metodlar async, sync, stopped veya problemli durumda?
- Kuyruk baskisi var mi?
- Hangi lane once operator ilgisi gerektiriyor?
- Replay, clear, disable, resize gibi runtime islemler hangi sirayla yapilmali?

## Ana Ekranlar

### 1. Services alani

- governed servisleri listeler
- servis bazli method sayisi, async uygunluk, stopped ve error sinyalleri sunar
- arama ve filtreleme destekler

### 2. Method detay alani

- secili servisin metodlarina iner
- enable/disable, mode degisimi, concurrency ve consumer thread ayarlarini sunar
- buffer preview, clear, delete ve replay aksiyonlarini operatora acar

### 3. Operations digest

- sistemin dikkat gerektiren noktalarini tek yerde toplar
- attention item’lari sirali ve operator-odaklidir
- uygun lane’e tek tikla gecis saglar

## Kullanım Akisi

1. Once summary ve attention alanina bak.
2. Yüksek baski veya hata gorulen lane’i sec.
3. Servis/method detayindan queue ve runtime durumunu incele.
4. Gerekirse:
   - replay
   - clear
   - disable
   - consumer resize
   aksiyonlarini kontrollu sekilde uygula.
5. Son durumda summary ekranindan sistemin tekrar dengelenip dengelenmedigini dogrula.

## Operasyonel Yorumlama

### Dusuk baski

- idle queue depth dusuktur
- failed entries sifira yakindir
- overall pressure `LOW` olur

### Orta/Yuksek baski

- queue derinligi artar
- failed entries veya high attention item gorulebilir
- summary bu durumu `MEDIUM` veya `HIGH` olarak tasir

## Iyi Pratikler

- runtime degisikliklerinden once mevcut durumu kaydet
- clear islemini replay ihtiyacini dislayarak kullanma
- disabled method’lari sadece bilincli bakim modunda tut
- consumer thread artisini izleme olmadan kalici cozum sanma

## Gelistirme Durumu

Mevcut admin panel:

- kullanilabilir
- operator odakli
- benchmark ve attention akisiyla uyumlu

Acik kalan alanlar:

- daha derin bilgi mimarisi sadeleştirmesi
- ileri seviye UX polish
- daha zengin telemetry entegrasyonu
