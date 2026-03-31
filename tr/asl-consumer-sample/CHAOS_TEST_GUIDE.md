# ASL Chaos Test Rehberi

Bu rehber, sample uygulama uzerinde operator ve gelistirici tarafinda denenebilecek temel kaos ve toparlanma senaryolarini Turkce ozetler.

JVM'i zorla olduren ve rapor ureten otomatik suite icin:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-mapdb-abuse-suite.ps1
```

## Kapsam

Amaç:

- async basari
- zorlanmis hata
- replay
- pending queue birikimi
- consumer resize
- stop/reject traffic
- restart recovery
- graceful shutdown
- zorla JVM kill ile pending persistence
- zorla JVM kill ile failed persistence
- in-progress sirasinda kill
- ayni queue dosyasinda tekrarli kill / restart dongusu
- kasitli header corruption startup recovery

senaryolarini gozlemlemek.

## Paylasilan Yardimcilar

Senaryolarda kullanilan ortak kavramlar:

- ilgili service/method lane secimi
- admin UI veya REST ile runtime mutasyonu
- summary ve queue sinyalleriyle dogrulama

## Temel Senaryolar

### 1. Async success

Normal enqueue-consume akisi beklenir.

### 2. Forced async failure

Failed queue veya error attention beklenir.

### 3. Replay failed entry

Replay sonrasi queue ve hata sayisi yeniden gozlenir.

### 4. Pending queue buildup

Consumer yetersizligi veya kontrollu gecikme ile backlog olusturulur.

### 5. Consumer resize and drain

Consumer artisi sonrasi queue’nun bosalma hizi izlenir.

### 6. Stop method and reject traffic

Method durduruldugunda traffic rejection davranisi izlenir.

### 7. Restart recovery for pending queue

Restart sonrasi stale state temizligi ve devam eden islerin toparlanmasi izlenir.

### 8. Restart recovery for failed queue

Failed queue davranisinin tutarliligi kontrol edilir.

### 9. Graceful shutdown drain

Shutdown sirasinda queue ve in-flight islerin dogru ele alinmasi beklenir.

### 10. Engine-level stale in-progress recovery

Motor seviyesinde yarim kalan islerin toparlanmasi dogrulanir.

## Onerilen Sira

1. Async success
2. Forced failure
3. Replay
4. Pending buildup
5. Resize and drain
6. Stop / reject
7. Restart recovery

## UI Kullanimi

Admin panelden:

- servis sec
- ilgili method lane’e gir
- queue preview ve attention sinyallerini izle
- gerekli runtime aksiyonunu uygula
