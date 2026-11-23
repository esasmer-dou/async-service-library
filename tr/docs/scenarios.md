# Senaryolar

**Dil:** [English](../../docs/scenarios.md) | Türkçe · [Dokümantasyon ana sayfası](../README.md#dokümantasyon)

Sık karşılaşılan durumlar ve onları çözen ayarlar. Her senaryo hangi kontrolün kullanılacağını, nerede ayarlanacağını ve ne beklenmesi gerektiğini söyler. Kontrollerin kendisi [Kavramlar](concepts.md) sayfasında anlatılır.

"Portal", admin portalında metodun **Tune** penceresidir (tüm replica'lar veya tek replica). "yml", `asl.runtime.services["servis"].methods["metot"]` anlamına gelir ([Başlarken](getting-started.md#5-başlangıç-değerlerini-yml-ile-değiştirin)).

| Durum | Kullanın |
|---|---|
| [Bir bağımlılık yavaş ve thread'leri tüketiyor](#1-bir-bağımlılık-yavaş-ve-threadleri-tüketiyor) | max concurrency |
| [Partner saniyede N isteğe izin veriyor](#2-partner-saniyede-n-isteğe-izin-veriyor) | rate limit |
| [Trafik ani yükseliyor ama iş bekleyebilir](#3-trafik-ani-yükseliyor-ama-iş-bekleyebilir) | ASYNC + consumer'lar |
| [Bir bağımlılık bir süreliğine çöktü](#4-bir-bağımlılık-bir-süreliğine-çöktü) | consumer 0 veya durdurma |
| [Hata düzeltildi, başarısız çağrılar yeniden çalışmalı](#5-hata-düzeltildi-başarısız-çağrılar-yeniden-çalışmalı) | toplu replay |
| [Olay sırasında geçici bir değişiklik](#6-olay-sırasında-geçici-bir-değişiklik) | süreli override |
| [Yalnızca bir replica sorunlu](#7-yalnızca-bir-replica-sorunlu) | replica override |
| [Bir `CompletableFuture` metodu kuyruğu kullanmalı](#8-bir-completablefuture-metodu-kuyruğu-kullanmalı) | ASYNC |
| [Bir payload sürekli hata veriyor veya servisi çökertiyor](#9-bir-payload-sürekli-hata-veriyor-veya-servisi-çökertiyor) | silme, max interruptions |
| [İş iki kez çalışmamalı](#10-iş-iki-kez-çalışmamalı) | idempotency key |

## 1. Bir bağımlılık yavaş ve thread'leri tüketiyor

Bir rapor servisi yavaşlar; her istek onu bekler ve web server'ın thread'leri tükenir.

- **Ayarlayın:** max concurrency'yi bağımlılığın kaldırabileceği değere, örneğin 10'a. Kalıcı olarak yml'de, o an için portalda.
- **Sonuç:** replica başına en fazla 10 çağrı bağımlılığı bekler. Diğerleri `CONCURRENCY_LIMIT` ile hemen reddedilir ve uygulamanın geri kalanı cevap vermeye devam eder.
- **Ayrıca:** çağırana açık bir hata dönün (örneğin `Retry-After` ile HTTP 503). Çağrılar reddedilmemeliyse 3. senaryoya bakın.

## 2. Partner saniyede N isteğe izin veriyor

Bir SMS sağlayıcısı hesabın tamamı için saniyede 50 isteğe izin veriyor. 2 replica çalıştırıyorsunuz.

- **Ayarlayın:** rate limit 25 (50 ÷ 2 replica).
- **SYNC:** hızı aşan çağrılar `RATE_LIMIT` ile reddedilir.
- **ASYNC:** hiçbir şey reddedilmez. Çağrılar kuyrukta bekler, consumer'lar her replica'da saniyede en fazla 25 çalıştırma başlatır. Kotalarda genellikle istenen budur.
- **Not:** limit replica başınadır. 2 replica'dan 4'e çıkarsanız değeri yarıya indirin.

## 3. Trafik ani yükseliyor ama iş bekleyebilir

Bir kampanya birkaç dakika içinde 20.000 karşılama e-postası gönderiyor. Çağıranın sonuca ihtiyacı yok.

- **Ayarlayın:** metot `asyncCapable` olmalı. ASYNC'e geçirin, consumer'ları 4 yapın (gerekirse e-posta sağlayıcısı için bir rate limit de verin).
- **Sonuç:** çağrılar saklanır saklanmaz döner. Replica başına dördü paralel çalışır, tepeyi kuyruk karşılar.
- **Kontrol edin:** metot başına kuyruk limiti varsayılan olarak 10.000 kayıttır. Tepe daha büyükse lane override ile yükseltin ([yapılandırma](configuration.md#kabul-ve-kapasite)). `asl.mapdb.lane.oldest.pending.age` metriğini izleyin: bekleyen en eski çağrının yaşı, consumer'ların yetişip yetişmediğini gösterir.

## 4. Bir bağımlılık bir süreliğine çöktü

E-posta sağlayıcısı 30 dakikalığına çöktü.

| Seçenek | Çağıranlar | Kuyruktaki iş | Ne zaman |
|---|---|---|---|
| ASYNC + **consumer 0** | çalışmaya devam eder; çağrılar saklanır | bekler, hiçbir şey başarısız olmaz | iş kaybolmamalıysa |
| Metodu **durdurun** | `DISABLED` ile reddedilir | dokunulmaz | bağımlılık çöktüğü sürece çağrıların değeri yoksa |

Sağlayıcı geri gelince consumer'ları normal değerine çevirin; birikmiş iş kendiliğinden boşalır. Süreli override ile (6. senaryo) bu geri dönüş kendiliğinden olur.

## 5. Hata düzeltildi, başarısız çağrılar yeniden çalışmalı

Bir hata yüzünden dün gece 3.000 çağrı `BUSINESS` hatasıyla başarısız oldu. Düzeltme deploy edildi.

- **Portalda:** metodun kuyruğunu açın, **Bulk…** seçin: *Failed*, kategori `BUSINESS`, zaman aralığı ve replica başına en fazla N kayıt. Sonra **Replay matching**.
- **Sonuç:** her replica eşleşen başarısız kayıtları tek adımda kuyruğa geri koyar ve kaç tane olduğunu bildirir. Çalışan kayıtlara hiç dokunulmaz.
- **İpucu:** küçük bir N ile başlayın (örneğin 100), sonucu kontrol edin, sonra daha büyük bir N ile tekrarlayın (komut başına en fazla 10.000).

## 6. Olay sırasında geçici bir değişiklik

Bir olay sırasında max concurrency'yi 2'ye düşürüyorsunuz ve böyle unutulmasını istemiyorsunuz.

- **Ayarlayın:** Tune'da değişikliğin yanında **Keep for: 1 hour** seçin.
- **Sonuç:** bir saat sonra metot önceki ayarına (yoksa yml değerine) döner. Dönüş audit log'a `override.expired` olarak yazılır. Portal metodun yanında kalan süreyi gösterir.
- Süreli bir değişikliği tekrar süreli değiştirirseniz ilk "önceki" değer korunur. Kalıcı bir değişiklik veya override'ı kaldırmak zamanlayıcıyı iptal eder.

## 7. Yalnızca bir replica sorunlu

Bir pod'un ağ yolu bozuk ve çağrıları timeout alıyor.

- **Ayarlayın:** replica tablosunda **Tune this replica** ile metodu yalnızca o replica'da durdurun veya limitlerini düşürün.
- **Sonuç:** diğer replica'lar ayarlarını korur. Replica override'ı bellekte tutulur ve replica gidince kaybolur.

## 8. Bir `CompletableFuture` metodu kuyruğu kullanmalı

`CompletableFuture<Quote> quote(String symbol)` birçok servis tarafından çağrılıyor ve çağrılarının restart'lardan ve trafik artışlarından etkilenmemesini istiyorsunuz.

- **Ayarlayın:** `@GovernedMethod(asyncCapable = true)`, sonra ASYNC'e geçirin.
- **Sonuç:** çağıranlar aynı kodu ve aynı sonucu korur; artık kuyruktaki çalıştırmanın future'ını alırlar.
- **Plan yapın:** çağıranlar varsayılan olarak `result-wait-timeout-millis` (10 dk) kadar bekleyebilir. Daha erken cevap gerekiyorsa future'da `orTimeout(...)` kullanın. Restart sonrası gelen sonuçları kaybetmemek için bir `AsyncResultHandler` tanımlayın. Ayrıntılar: [Kavramlar](concepts.md#completablefuture-metotları).

## 9. Bir payload sürekli hata veriyor veya servisi çökertiyor

- **Hata veriyorsa:** varsayılan `retry.max-attempts: 1` ile tek denemeden sonra `FAILED` olur. Kuyruk penceresinde inceleyin (özet, hata tipi, son hata), sonra silin veya replay edin.
- **JVM'i çökertiyorsa:** `retry.max-interruptions` (3) kesintiden sonra `FAILED` olarak işaretlenir ve artık alınmaz.
- **Çok sayıdaysa:** bir kategori ve zaman aralığıyla **Bulk… → Delete matching**.

## 10. İş iki kez çalışmamalı

- Bir `String` parametresine (örneğin olay id'si) `@AsyncIdempotencyKey` ekleyin. Aynı key ile tekrar gelen çağrı 24 saat boyunca yeniden kuyruğa alınmaz.
- Teslim yine de at-least-once biçimindedir: yanlış anda gelen bir çökmeden sonra saklanmış bir çağrı iki kez çalışabilir. Yan etkiyi de idempotent yapın; örneğin tablonuzda olay id'si üzerinde benzersiz bir kısıtla.
