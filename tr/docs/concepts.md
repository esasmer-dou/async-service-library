# Kavramlar

**Dil:** [English](../../docs/concepts.md) | Türkçe · [Dokümantasyon ana sayfası](../README.md#dokümantasyon)

Bu sayfa ASL'nin bir metot üzerinde verdiği her kontrolü anlatır: ne anlama geldiğini, küçük bir örneği ve ne zaman kullanılacağını. Hazır kombinasyonlar için [Senaryolar](scenarios.md) sayfasına bakın.

## Bir çağrı nasıl yönetilir

```
çağıran ──> üretilen wrapper ──> [enabled?] ──> [rate limit] ──> [max concurrency] ──> sizin implementation'ınız
                                    │
                                    └── ASYNC mod: çağrı bunun yerine kalıcı kuyruğa yazılır,
                                        bir consumer onu daha sonra çalıştırır
```

Her kontrol çağrıyı ya geçirir ya da `MethodUnavailableException` ile hemen reddeder. Redler stack trace taşımaz, bu yüzden yük altında maliyeti düşük kalır. Neden reddedildiğini `reason()` söyler: `DISABLED`, `RATE_LIMIT` veya `CONCURRENCY_LIMIT`.

## Kontroller bir bakışta

| Kontrol | Varsayılan | yml'de (`asl.runtime.services…`) | Portalda (Tune) | Neye göre |
|---|---|---|---|---|
| [Enabled](#enabled) | açık | `enabled` | Method: Running / Stopped | replica başına |
| [Max concurrency](#max-concurrency) | sınırsız | `max-concurrency` | Max concurrency | replica başına |
| [Rate limit](#rate-limit) | yok | `rate-limit` | Rate limit (calls/s) | replica başına |
| [Çalışma modu](#sync-ve-async) | SYNC | `execution-mode` | Execution mode | replica başına |
| [Consumer'lar](#consumerlar) | 1 (async-capable metotlar) | `consumer-threads` | Consumer threads | replica başına |
| [Kullanılamıyor mesajı](#kullanılamıyor-mesajı) | `Method is disabled` | `unavailable-message` | – | – |

Her limit **replica başına** uygulanır: üç replica'da `max-concurrency: 20` toplamda aynı anda 60 çağrıya izin verir.

## Enabled

**Nedir.** Metodun çağrı kabul edip etmediği. Durdurulmuş bir metot her çağrıyı `DISABLED` sebebiyle ve [kullanılamıyor mesajıyla](#kullanılamıyor-mesajı) reddeder. ASYNC modda durdurulmuş metot, kuyruğa hiçbir şey yazmadan reddeder.

**Ne zaman kullanılır:** bir bağımlılık çöktüğünde ve çağrılar durumu yalnızca kötüleştirdiğinde, bakım sırasında veya acil durumda bir özelliği kapatmak için.

**Dikkat.** Durdurmak, kuyrukta zaten bekleyen işe dokunmaz. Çağrıları kabul etmeye devam edip kuyruktaki işi bekletmek için bunun yerine [consumer'ları](#consumerlar) 0 yapın.

## Max concurrency

**Nedir.** Bu metodun bir replica'da aynı anda çalışabilecek en fazla çağrı sayısı. Limiti aşan çağrı beklemez, `CONCURRENCY_LIMIT` sebebiyle hemen reddedilir. ASYNC modda aynı anda çalışabilecek en fazla kuyruk işini de belirler.

**Örnek.** `max-concurrency: 10`. Aynı anda gelen 11. çağrı reddedilir; on çağrıdan biri bitince gelen sonraki çağrı kabul edilir.

**Ne zaman kullanılır:** metot sınırlı bir kaynak kullanıyorsa; örneğin 20 bağlantılık bir connection pool veya en fazla 10 paralel isteğe izin veren bir partner. Yavaş bir bağımlılığın tüm request thread'lerini ele geçirmesini önler (bulkhead).

**Dikkat.** Çağıranlar reddi ele almalıdır (daha sonra tekrar denemek veya 503 dönmek). Bunu yapamıyorlarsa ASYNC mod kullanın; orada çağrılar reddedilmek yerine kuyrukta bekler.

## Rate limit

**Nedir.** Bu metodun bir replica'da **saniyede başlayabilecek** en fazla çağrı sayısı. `0` sınır yok demektir ve yml'de verilmiş bir sınırı da kaldırır. Bir saniyelik çağrı tek seferde başlayabilir (burst); sonrasında çağrılar ayarlanan hızda başlar.

| Mod | Hızı aşan bir çağrı… |
|---|---|
| SYNC | `RATE_LIMIT` sebebiyle hemen reddedilir |
| ASYNC | yine kuyruğa alınır; consumer'lar saniyede en fazla N çalıştırma başlatır, fazlayı kuyruk karşılar |

**Örnek.** `rate-limit: 5` ve bir saniye içinde 30 çağrı geliyor. SYNC modda yaklaşık 10'u kabul edilir (5 burst + geçen saniye için 5), kalanı reddedilir. ASYNC modda 30'unun hepsi kuyruğa alınır ve yaklaşık 5 saniyede biter.

**Ne zaman kullanılır:** bir partner API'nin veya veritabanının saniye başına kotası varsa. Max concurrency paralel işi sınırlar, rate limit hızı sınırlar. İkisi birlikte kullanılabilir.

## SYNC ve ASYNC

**Nedir.** `asyncCapable` bir metodun nasıl çalıştığı. Metotlar SYNC başlar; çağıranları değiştirmeden yml'de (`execution-mode: ASYNC`) veya portalda moda geçirilir.

| | SYNC | ASYNC |
|---|---|---|
| Metodu kim çalıştırır | çağıranın thread'i, hemen | bir consumer, daha sonra, kalıcı kuyruktan |
| Çağıran ne alır | sonucu veya exception'ı | `void`: çağrı saklanınca döner. `CompletableFuture<T>`: kuyruktaki çalıştırmanın future'ı ([aşağıda](#completablefuture-metotları)) |
| Hata | çağırana gider | başarısız kuyruk kaydı olarak saklanır; tekrar denenebilir, replay edilebilir veya silinebilir |
| Çok fazla çağrı | reddedilir (max concurrency, rate limit) | kuyruk limitlerine kadar kuyruğa alınır |
| Restart sonrası kalır mı | hayır | evet, kuyruk lokal bir MapDB dosyasıdır |

**ASYNC'i ne zaman kullanın:** çağıranın sonuca hemen ihtiyacı yoksa ve bir trafik artışında veya kısa bir kesintide çağrılar kaybolmamalı ya da reddedilmemeliyse. Örnekler: e-posta, audit olayları, bildirimler, dışa aktarımlar.

**Gerekenler.**

- Metot `asyncCapable` olmalı ve `void` veya `CompletableFuture<T>` döndürmeli.
- MapDB motoru açık olmalı (`asl.async.mapdb.enabled: true`).
- Metot idempotent olmalı: kuyruktaki bir çağrı bir çökme sonrasında yeniden çalışabilir ([Teslim garantisi](#teslim-garantisi)).

SYNC'e geri geçince yeni çağrılar hemen çalışır. Kuyrukta önceden kalan çağrıları consumer'lar işlemeye devam eder (`drain-backlog-in-sync-mode`, varsayılan açık).

## Consumer'lar

**Nedir.** Bu metodun kuyruktaki çağrılarından bir replica'da aynı anda en fazla kaçının çalışabileceği. Yalnızca metodun kuyrukta işi varken önemlidir: ASYNC modda veya SYNC'e geri geçtikten sonra birikmiş iş boşaltılırken.

**Örnek.** İki replica'da `consumer-threads: 2`, toplamda aynı anda en fazla 4 kuyruk işi demektir.

| Değer | Etkisi |
|---|---|
| `0` | Çağrılar kabul edilip saklanır ama hiçbiri çalışmaz. Bir kesinti sırasında işi bekletip sonra bırakmak için kullanılır. |
| `1` | Sırayla, birer birer. |
| `N` | En fazla N paralel. |

**Dikkat.** Consumer'ların kendi thread'leri yoktur; işi ortak worker havuzundan alırlar (`asl.async.mapdb.workers.max-threads`). Gerçek paralellik şu üç değerin en küçüğüdür: consumer sayısı, [max concurrency](#max-concurrency) ve havuz boyutu. Bir [rate limit](#rate-limit) de saniyede kaç çalıştırma başlayacağını ayrıca sınırlar. `CompletableFuture` metotlarda bir consumer, metodun future'ı tamamlanana kadar meşgul kalır.

## Kullanılamıyor mesajı

Metot durdurulmuşken çağıranın aldığı metin. Metot başına (`@GovernedMethod(unavailableMessage = …)` veya yml'de `unavailable-message`) ya da tüm uygulama için (`asl.runtime.default-unavailable-message`) verilir.

## Bir ayar nereden gelir

Aynı kontrol birden fazla yerde verilmişse en üstteki kazanır:

1. **Bu replica** için portal override'ı.
2. **Tüm uygulama** için portal override'ı.
3. yml (`asl.runtime.services…`).
4. Annotation (`@GovernedMethod`).
5. Kütüphane varsayılanı.

Bir portal override'ı kaldırılınca metot yml veya annotation değerine döner. Portal override'ı **süreli** de olabilir (örneğin 1 saat); süre dolunca önceki değer geri gelir ([Admin server ve portal](admin-server.md#süreli-overridelar)).

Kuyruk, worker ve retry ayarları (`asl.async.mapdb.*`) yalnızca açılışta okunur; değiştirmek restart gerektirir.

## Kuyruk

### Kayıt durumları

| Durum | Anlamı |
|---|---|
| `PENDING` | saklandı, bir consumer bekliyor |
| `IN_PROGRESS` | bir consumer çalıştırıyor |
| `RETRY_WAIT` | başarısız oldu, bir bekleme süresinden sonra tekrar denenecek |
| `FAILED` | kalıcı olarak başarısız; siz replay edene veya silene kadar kalır |

### Hatalar ve tekrar denemeler

- `asl.async.mapdb.retry.max-attempts` (varsayılan `1`) toplam deneme sayısıdır. Varsayılanla başarısız bir çalıştırma doğrudan `FAILED` olur.
- Daha fazla deneme verilirse denemeler arasında giderek artan bir süre beklenir (`retry.initial-backoff-millis`, `retry.max-backoff-millis`).
- Her hatanın filtrelenebilen bir kategorisi vardır:

| Kategori | Neden |
|---|---|
| `BUSINESS` | metodunuz exception fırlattı |
| `TIMEOUT` | çalıştırma `execution-timeout-millis`'i aştı veya bir `CompletableFuture` zamanında tamamlanmadı |
| `DECODE` | saklanan argümanlar geri okunamadı (örneğin sınıf değişti) |
| `MIGRATION` | bir payload migration'ı başarısız oldu |

Portaldan başarısız bir kaydı replay edebilir, silebilir ya da bir filtreye uyan tüm kayıtlar için ikisini toplu yapabilirsiniz ([Kuyruk işlemleri](admin-server.md#kuyruk-işlemleri)).

### Teslim garantisi

Teslim **at-least-once** biçimindedir. Bir çağrı çalışırken process durursa (çökme, kill, süresi dolan lease), çağrı kuyruğa döner ve tekrar çalışır. Kesilen çalıştırma bir deneme hakkı tüketmez. Ancak `retry.max-interruptions` (varsayılan 3) kesintiden sonra çağrı `FAILED` olarak işaretlenir. Böylece tek bir bozuk payload servisi sonsuza kadar çökertemez.

Sizin yan etkiniz gerçekleştikten sonra ama ASL tamamlandığını kaydetmeden önce gelen bir çökme, çağrının iki kez çalışmasına yol açar. Metodu idempotent yapın; örneğin hedef tabloda benzersiz bir anahtarla.

`@AsyncIdempotencyKey`, aynı key'in iki kez **kuyruğa alınmasını** engeller (varsayılan olarak 24 saat). Dış bir yan etkiyi transactional yapamaz.

### Kapasite

Kuyruk sınırsız büyümez. Varsayılan sınırlar:

| Limit | Varsayılan |
|---|---|
| toplam kayıt | 100.000 |
| toplam payload | 512 MiB |
| metot başına kayıt | 10.000 |
| metot başına payload | 64 MiB |
| çağrı başına | 1 MiB |

Limiti aşan çağrı `AsyncAdmissionRejectedException` ve bir sebeple (örneğin `LANE_CAPACITY`) reddedilir. Yalnızca bir metodun limitini lane override ile yükseltin ([yapılandırma](configuration.md#kabul-ve-kapasite)).

## CompletableFuture metotları

`CompletableFuture<T>` döndüren bir metot `asyncCapable` olabilir. Çağıran her iki modda da aynı sonucu görür; bu yüzden metot, çağıranlara dokunmadan mod değiştirebilir.

- **SYNC.** Metodun kendi future'ını alırsınız. Başarı ve hata, future tamamlanınca sayılır.
- **ASYNC.** Çağrı kuyruğa alınır ve kuyruktaki çalıştırmanın future'ını alırsınız. Bir consumer metodu çalıştırır, onun future'ını bekler, sonra sizinkini değerle veya hatayla tamamlar.

Önlemler:

| Eğer… | o zaman… |
|---|---|
| metodun future'ı hiç tamamlanmazsa | `workers.completion-timeout-millis` (5 dk) sonra çalıştırma `TIMEOUT` olarak başarısız olur |
| çağrıyı kimse işlemezse (consumer 0, uzun birikme) | `workers.result-wait-timeout-millis` (10 dk) sonra sizin future'ınız `AsyncResultUnavailableException(WAIT_TIMEOUT)` ile biter. Çağrı kuyrukta kalır ve yine çalışır. |
| siz beklerken uygulama kapanırsa | future'ınız `AsyncResultUnavailableException(SHUTDOWN)` ile biter; çağrı restart sonrasında çalışır |
| `workers.max-pending-results`'tan (10.000) fazla çağıran bekliyorsa | yeni çağrılar kuyruğa hiçbir şey yazılmadan reddedilir |

Artık kimsenin beklemediği sonuçlar (restart veya bekleme zaman aşımı sonrası), tanımladıysanız bir `AsyncResultHandler` bean'ine gider:

```java
@Bean
AsyncResultHandler quoteResults(QuoteStore store) {
    return result -> store.save(result.invocationId(), result.value(), result.failure());
}
```

Değer yalnızca bellekte tutulur. Restart sonrasında da gereken bir şeyi kendiniz saklayın.

## Referans

- Tüm property'ler: [Yapılandırma referansı](configuration.md)
- Bir durumda ne ayarlanır: [Senaryolar](scenarios.md)
