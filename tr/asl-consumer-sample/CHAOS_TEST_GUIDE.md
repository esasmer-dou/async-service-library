# ASL Chaos Test Rehberi

Bu rehber, sample uygulama üzerinde denenebilecek temel kaos ve toparlanma senaryolarını açıklar. Amaç, async queue davranışının normal çalışma, hata, replay, restart ve zorla kill durumlarında nasıl tepki verdiğini görmektir.

Otomatik destructive suite:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-mapdb-abuse-suite.ps1
```

## Kapsam

Bu senaryolar özellikle şu davranışları gözlemlemek için kullanılır:

- Başarılı async enqueue ve consume akışı.
- Bilerek üretilen async hata.
- Failed entry replay.
- Pending queue birikimi.
- Consumer thread resize sonrası queue drain.
- Metot durdurulduğunda traffic reject davranışı.
- Restart sonrası pending ve failed queue durumunun korunması.
- Graceful shutdown sırasında in-flight işlerin ele alınması.
- JVM zorla öldürüldüğünde queue dosyasının yeniden açılabilir kalması.
- Stale `IN_PROGRESS` kayıtların recovery sırasında failed duruma çekilmesi.
- Bilerek bozulan MapDB header dosyasında startup recovery davranışı.

## Ortak Kontrol Noktaları

Her senaryoda şu sinyalleri birlikte oku:

- Service ve method lane seçimi doğru mu?
- Method `SYNC` mi `ASYNC` mi?
- Consumer thread sayısı beklenen değerde mi?
- Queue depth artıyor mu, azalıyor mu?
- Failed entry oluştu mu?
- Admin summary pressure seviyesi doğru mu?
- Replay veya clear sonrası state beklenen hâle geldi mi?

## Temel Senaryolar

### 1. Async başarı akışı

Async-capable bir `void` metodu `ASYNC` moda al. Consumer thread sayısını `1` veya daha yüksek bir değere çıkar. Çağrıdan sonra queue’nun kısa süre içinde boşalması ve success sayacının artması beklenir.

### 2. Bilerek async hata üretme

Scenario endpoint üzerinden `failuresRemaining` değerini artır. Metodu çağır. İşin failed buffer’a düşmesi ve method error sayacının artması beklenir.

### 3. Failed entry replay

Failed buffer’daki kaydı replay et. Hata koşulu kaldırılmışsa kaydın tekrar pending duruma alınması, worker tarafından işlenmesi ve queue’dan düşmesi beklenir.

### 4. Pending queue birikimi

Consumer thread sayısını `0` yap veya processing delay ekle. Arka arkaya çağrı gönder. Queue depth değerinin kontrollü biçimde yükselmesi beklenir.

### 5. Consumer resize ve drain

Pending queue biriktikten sonra consumer thread sayısını artır. Queue depth değerinin azalması, processed/success sinyallerinin yükselmesi beklenir.

### 6. Metodu durdurma ve traffic reject

Metodu disable et. Yeni çağrıların queue’ya yazılmadan reddedilmesi gerekir. Bu davranış, sistem baskı altındayken yeni iş kabulünü kapatmak için kullanılır.

### 7. Pending queue ile restart

Queue’da pending iş bırakıp uygulamayı restart et. Açılıştan sonra queue state korunmalı ve consumer açıldığında işler tüketilebilmelidir.

### 8. Failed queue ile restart

Failed entry oluşturup uygulamayı restart et. Failed kayıt restart sonrası da görünür olmalı ve replay/delete işlemleri yapılabilmelidir.

### 9. Graceful shutdown

Uygulamayı normal shutdown ile kapat. Worker shutdown bekleme süresi içinde in-flight işler kontrollü şekilde ele alınmalıdır.

### 10. Stale `IN_PROGRESS` recovery

İş `IN_PROGRESS` durumundayken JVM’i zorla öldüren destructive suite’i çalıştır. Restart sonrası stale kayıtların recovery mesajıyla failed duruma alınması beklenir.

## Önerilen Çalıştırma Sırası

1. Async başarı akışı.
2. Bilerek async hata üretme.
3. Failed entry replay.
4. Pending queue birikimi.
5. Consumer resize ve drain.
6. Metot disable ve traffic reject.
7. Restart recovery.
8. Destructive MapDB abuse suite.

## Admin UI ile İzleme

Admin panelde:

- Önce servis kartlarındaki queue depth, failed ve pressure sinyallerini kontrol et.
- Sonra ilgili metot detayını aç.
- Buffer bölümünde pending, failed ve in-progress kayıtları izle.
- Replay, delete veya clear işlemini yalnızca ilgili state’i gördükten sonra uygula.

## Beklenen Sonuç

Bu senaryolar başarılıysa sample uygulama şu davranışları göstermelidir:

- JVM kill sonrası uygulama fatal startup hatasıyla kalmamalı.
- Doğrulanmış header bozulmasında `FAIL_FAST` dosyayı değiştirmeden başlangıcı durdurmalı; yalnız `ARCHIVE_AND_RESET` seçilmişse bozuk store arşivlenip boş kuyruk açılmalıdır.
- Admin UI recovery durumunu banner ile göstermelidir.
- Failed işler görünür, replay edilebilir ve silinebilir olmalıdır.
- Pending backlog operatör tarafından consumer thread artırılarak eritilebilmelidir.
