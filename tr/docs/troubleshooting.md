# Sorun giderme

**Dil:** [English](../../docs/troubleshooting.md) | Türkçe · [Dokümantasyon ana sayfası](../README.md#dokümantasyon)

| Belirti | Olası neden | Ne yapmalı |
|---|---|---|
| Açılış hatası: *"configures unknown governed service/method"*, *"does not reach any governed method"* veya *"is not a method signature"* | `asl.runtime.services` veya `asl.async.mapdb.services` altındaki bir yml anahtarında yazım hatası var ya da tırnaklı köşeli parantez kullanılmadığı için Spring anahtarı noktalardan böldü | birebir id'leri kullanın ve `"[mail.service]"`, `"[send(java.lang.String)]"` biçiminde yazın ([kurallar](configuration.md#kurallar)) |
| Açılış hatası: *"…to ASYNC but no async execution engine is attached"* | kuyruk olmadan `execution-mode: ASYNC` verilmiş | MapDB starter'ı ekleyin ve `asl.async.mapdb.enabled: true` verin |
| Bir metot yönetilmiyor: metrik yok, limitler işlemiyor | interface yerine implementation inject edilmiş ya da interface başka modülde ve wrapper bean'i yok (ASL uyarı loglar) | interface'i inject edin; `XxxAsl.wrap(...)`'i `@Primary` olarak kaydedin ([başlarken](getting-started.md#4-interfacei-inject-edip-çalıştırın)) |
| Derleme hatası: *asyncCapable … supported only for void methods and … CompletableFuture* | `asyncCapable = true` başka tip döndüren bir metotta | yalnızca `void` veya `CompletableFuture<T>` kuyruğa alınabilir |
| `MethodUnavailableException`, sebep `DISABLED` | metot durdurulmuş (portal, yml veya annotation) | başlatın; portalda bir override etiketi olup olmadığına bakın |
| … sebep `CONCURRENCY_LIMIT` | `max-concurrency`'den fazla eşzamanlı çağrı | limiti artırın, daha sonra tekrar deneyin veya ASYNC kullanın ([senaryo 1](scenarios.md#1-bir-bağımlılık-yavaş-ve-threadleri-tüketiyor)) |
| … sebep `RATE_LIMIT` | SYNC modda `rate-limit`'ten fazla saniyede çağrı | limiti artırın veya reddetmek yerine kuyruğa alan ASYNC'i kullanın ([senaryo 2](scenarios.md#2-partner-saniyede-n-isteğe-izin-veriyor)) |
| `AsyncAdmissionRejectedException` | kuyruk dolu (`LANE_CAPACITY`, `GLOBAL_CAPACITY`), payload çok büyük (`PAYLOAD_TOO_LARGE`) veya sonuç bekleyen çağıran çok fazla (`RESULT_CAPACITY`) | consumer'ların neden geride kaldığını bulun; o metodun limitini yükseltin ([kapasite](configuration.md#kabul-ve-kapasite)) |
| Çağrılar `PENDING`'de birikiyor | consumer'lar `0`, metot rate limit'e takılıyor veya worker havuzu dolu | *Consumers* ve *Rate limit* sütunlarına bakın; gerçek paralellik consumer sayısı, max concurrency ve `workers.max-threads`'in en küçüğüdür |
| Çağrılar hemen `FAILED` oluyor | `retry.max-attempts` `1` (varsayılan) | nedeni düzeltip replay edin ([senaryo 5](scenarios.md#5-hata-düzeltildi-başarısız-çağrılar-yeniden-çalışmalı)); denemeleri yalnız idempotent metotlarda artırın |
| Kategori `DECODE` | saklanan argümanlar okunamıyor; örneğin sınıfın adı değişmiş veya allowlist'te değil | tipi allowlist'e ekleyin veya kaydedin ya da kayıtları silin |
| Kategori `TIMEOUT` | `execution-timeout-millis` veya `completion-timeout-millis` aşıldı | çağrıyı hızlandırın veya timeout'u artırın |
| `CompletableFuture` çağıranı `AsyncResultUnavailableException` alıyor | `WAIT_TIMEOUT`: hiçbir consumer zamanında bitiremedi. `SHUTDOWN`: uygulama durdu. `DUPLICATE`: aynı idempotency key. | çağrı kaybolmadı; sonucu `AsyncResultHandler`'ınıza gider ([kavramlar](concepts.md#completablefuture-metotları)) |
| Açılış `MapDbStoreLockedException` ile duruyor | kuyruk dosyası başka bir process'e ait | her replica'ya kendi dosyasını ve volume'unu verin |
| Açılış bir bozulma hatasıyla duruyor | depo bozuk ve politika `FAIL_FAST` | dosyayı inceleme için saklayın; `corruption-policy: ARCHIVE_AND_RESET` boş başlar (veri kaybı) |
| Health `aslMapDb` DOWN, `asl.mapdb.engine.healthy` = 0 | kuyruk bir depolama hatası sonrası durdu | disk ve yetkileri kontrol edip restart edin |
| Portalda replica *in sync* değil | son ayarları henüz almadı (sonraki heartbeat) veya bir ayar hatası bildiriyor | birkaç saniye bekleyin; replica satırı hata metnini gösterir |
| Bir replica portaldan kayboluyor | `3 × interval` (45 sn) boyunca heartbeat gelmedi | pod'un çalıştığını ve server'a ulaştığını kontrol edin; replica override'ları onunla birlikte silinir |
| Portal kuyruk kayıtlarını listeleyemiyor | o replica'da `asl.admin.client.queue-browse` `false` | kayıt özetlerinin server'a gönderilmesi kabul edilebilirse açın |
| Admin server heartbeat'leri reddediyor (`401`) | secret'lar farklı veya saatler arasında 60 sn'den fazla fark var | aynı secret'ı kullanın; saatleri eşitleyin (NTP) |
| Portal değişiklikleri `403` ile reddediliyor | istek başka bir origin'den geldi veya kullanıcı `VIEWER` | portalın kendi adresini veya `security.allowed-origins`'i kullanın; kullanıcıya `OPERATOR` verin |
