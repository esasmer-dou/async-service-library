# ASL Yapılandırma Referansı

**Dil:** [English](../CONFIGURATION_REFERENCE.md) | Türkçe

Bu doküman ASL’nin Spring Boot üzerinden dışarı açtığı bütün property’leri listeler. Her starter kendi Spring configuration metadata dosyasını paketlediği için bu anahtarlar IDE otomatik tamamlamasında da görünür.

## Genel Kurallar

- YAML içinde kebab-case kullanılır. Ortam değişkeninde ad büyük harf ve alt çizgiyle yazılır: `asl.async.mapdb.enabled` için `ASL_ASYNC_MAPDB_ENABLED`.
- Byte sınırları doğrudan byte cinsindendir; `MiB` metni kabul edilmez.
- Milisaniye alanları kullanım amacına göre sıfır veya pozitif değer ister.
- Geçersiz ayar birleşimleri sessizce düzeltilmez; uygulama başlangıçta hata verir.
- Dinamik service ve method anahtarları `@GovernedService.id` ile gerçek metot ID/imzasıyla bire bir eşleşmelidir.

## Core Runtime Ayarları

| Property | Varsayılan | Ne işe yarar, verilmezse ne olur? |
|---|---:|---|
| `asl.runtime.default-unavailable-message` | `Method is disabled` | Annotation veya admin üzerinden özel mesaj verilmemişse disabled çağrıya dönecek metin. |
| `asl.runtime.max-concurrency-exceeded-message-template` | `Method reached max concurrency: %d` | Eşzamanlılık reddi mesajı; `%d` yerine aktif sınır yazılır. |
| `asl.runtime.max-last-error-characters` | `2048` | Bellekte tutulan ve gözlemleme yüzeylerine açılan son senkron çağrı hatasının kesin karakter sınırı. |
| `asl.runtime.error-detail-policy` | `TYPE_ONLY` | `TYPE_ONLY` yalnızca exception sınıfını saklar. Hassas veri içerebilecek mesaj için `TYPE_AND_MESSAGE` açıkça seçilmelidir. |
| `asl.runtime.unwrapped-governed-bean-policy` | `WARN` | Governed arayüzün üretilen wrapper olmadan bean olarak yayınlanmasını (ör. arayüz ve implementasyonun farklı modüllerde derlenmesi) startup'ta kontrol eder. `WARN` log yazar, `FAIL` startup'ı durdurur, `IGNORE` kontrolü kapatır. |
| `asl.observability.enabled` | `true` | Micrometer ve `MeterRegistry` varsa ASL metriklerini kaydeder. |
| `asl.observability.metric-prefix` | `asl` | Bütün ASL metrik adlarının başlangıcı. |
| `asl.observability.max-method-meters` | `2000` | Metot bazlı metrik üretilecek en fazla governed metot sayısı; aşılırsa startup durur. MapDB lane metriklerini de sınırlar (fazla lane'ler loglanır, metrik üretilmez). |
| `asl.observability.method-latency-enabled` | `false` | Senkron governed çağrılar için metot başına `asl.method.duration` Timer yayınlar. Her çağrıya iki saat okuması eklediği için varsayılan kapalıdır. |

## Metot Bazlı Başlangıç Override’ı

`asl.runtime.services` (core starter) dinamik bir map köküdür. Altındaki ilk anahtar governed service ID’si, sonraki `methods` bölümü ise gerçek metot ID/imzasıdır. İki anahtar da köşeli parantez içinde yazılmalıdır (`"[mail.service]"`, `"[send(java.lang.String)]"`; properties dosyasında `asl.runtime.services[mail.service].methods[send(java.lang.String)].max-concurrency`): parantez olmadan Spring Boot anahtarı her noktada böler ve parantezleri siler, ayar hiçbir metotla eşleşmez. Bilinmeyen servis veya metot anahtarı ve parantezsiz yazılmış anahtarlar startup'ı bu ipucuyla durdurur. Merkezi admin server'dan gelen override'lar bu değerlerin üstüne uygulanır; kaldırıldıklarında metot buradaki değere döner. Kaldırılan uygulama içi portalın `asl.admin.services` anahtarı artık okunmaz; değerleri buraya taşı.

```yaml
asl:
  runtime:
    services:
      "[mail.service]":
        methods:
          "[publishAudit(java.lang.String,java.lang.String)]":
            enabled: true
            max-concurrency: 4
            unavailable-message: Geçici olarak kullanılamıyor
            execution-mode: ASYNC
            consumer-threads: 2
```

| Dinamik property son eki | Varsayılan | Amaç |
|---|---:|---|
| `.enabled` | annotation değeri | Metodu startup sırasında açar veya kapatır. |
| `.max-concurrency` | annotation değeri | Aktif SYNC/ASYNC çalışma eşzamanlılığını değiştirir. |
| `.unavailable-message` | annotation/runtime varsayılanı | Disabled çağrıya dönen mesajı değiştirir. |
| `.execution-mode` | `SYNC` | `SYNC` veya `ASYNC`; async için async-capable bir metot (`void` veya `CompletableFuture<T>`) ve engine gerekir. |
| `.consumer-threads` | annotation değeri | Lane consumer hedefini belirler; sıfır değeri iş kabul eder fakat tüketmez. |
| `.rate-limit` | `0` (sınır yok) | Replica başına saniyedeki çağrı sayısı. SYNC modda sınırı aşan çağrı `RATE_LIMIT` sebebiyle reddedilir. ASYNC modda çağrılar yine kuyruğa alınır; consumer'lar saniyede en fazla bu kadar çalıştırma başlatır. Bir saniyelik çağrı tek seferde (burst) çalışabilir. Negatif değer startup'ı durdurur. |

## Merkezi Admin Client

Modül `asl-spring-boot-admin-client-starter`. Uygulamayı `asl-admin-server`'a bağlar. Uygulamada dışarı açılan hiçbir şey yoktur; tüm bağlantıları client açar.

| Property | Varsayılan | Amaç ve verilmezse davranış |
|---|---:|---|
| `asl.admin.client.enabled` | `false` | Heartbeat thread'ini başlatır. |
| `asl.admin.client.server-url` | yok | Admin server'ın temel adresi. Açıkken zorunludur; verilmezse uygulama başlamaz. |
| `asl.admin.client.secret` | yok | Ortak HMAC secret; en az 16 bayt, sunucudakiyle aynı değer. Açıkken zorunludur. |
| `asl.admin.client.application` | `spring.application.name` | Sunucuda görünen uygulama adı. Yoksa `application` kullanılır. |
| `asl.admin.client.instance` | `HOSTNAME` ortam değişkeni | Replica adı. Kubernetes'te pod adıdır; değilse host adı kullanılır. |
| `asl.admin.client.interval` | `15s` | Sunucu başka bir değer isteyene kadar (izleme modu) heartbeat aralığı. |
| `asl.admin.client.max-backoff` | `60s` | Sunucuya ulaşılamazken yeniden deneme gecikmesinin üst sınırı. Telemetri biriktirilmez, düşürülür. |
| `asl.admin.client.connect-timeout` | `2s` | TCP bağlantı zaman aşımı. |
| `asl.admin.client.request-timeout` | `3s` | Tek isteğin zaman aşımı. |
| `asl.admin.client.queue-browse` | `false` | Sunucunun kuyruk kayıtlarını listelemesine izin verir. Bu durumda kayıt özetleri ve hata metinleri pod dışına çıkar. |
| `asl.admin.client.browse-limit` | `50` | Bir browse komutunda dönen en fazla kayıt (1–500). |
| `asl.admin.client.max-error-chars` | `200` | Sunucuya gönderilen hata metinleri bu uzunlukta kesilir. |
| `asl.admin.client.max-summary-chars` | `200` | Browse cevabıyla gönderilen payload özetleri bu uzunlukta kesilir (0–2000; `0` hiç göndermez). Özet, `asl.async.mapdb.payload.summary-policy` ayarının sakladığı değerdir: varsayılanda payload tipi, `CODEC` ile argümanların kısa metni. Payload'ın kendisi pod'dan hiç çıkmaz. |

## Merkezi Admin Server

Çalıştırılabilir `asl-admin-server-<sürüm>-exec.jar` (JDK HTTP sunucusu ve MapDB, web framework'ü yok). Spring uygulaması değildir; ayarlar artan öncelikle şu kaynaklardan okunur: hazır varsayılanlar, çalışma dizinindeki `asl-admin.properties` (veya `--config=<yol>`), ortam değişkenleri (`asl.admin.server.watch.idle-timeout` için `ASL_ADMIN_SERVER_WATCH_IDLE_TIMEOUT`), JVM system property'leri ve `--anahtar=değer` argümanları (önek isteğe bağlı). Süreler: `500ms`, `15s`, `5m`, `1h`, `7d`. Boyutlar: `512KB`, `1MB` veya bayt.

| Property | Varsayılan | Amaç ve verilmezse davranış |
|---|---:|---|
| `asl.admin.server.secret` | `ASL_ADMIN_SECRET` ortam değişkeni | Ortak HMAC secret; client'lardakiyle aynı değer. Boşsa sunucu başlamaz. |
| `asl.admin.server.port` | `8080` | Dinlenen port (`0` boş bir port seçer). |
| `asl.admin.server.bind-address` | `0.0.0.0` | Dinlenen adres. |
| `asl.admin.server.threads` | `2` | HTTP worker thread sayısı (1–64). İstekler kısa olduğu için yüzlerce replica için ikisi yeterlidir. |
| `asl.admin.server.interval` | `15s` | Kimsenin izlemediği uygulamalardan istenen heartbeat aralığı. |
| `asl.admin.server.watch.interval` | `2s` | Uygulama portalde açıkken istenen aralık. |
| `asl.admin.server.watch.idle-timeout` | `60s` | Portal sorgulamayı bıraktıktan bu süre sonra izleme modu biter. |
| `asl.admin.server.instance.expire-after` | `3 × interval` | Bu süre heartbeat göndermeyen replica, replica düzeyindeki override'ları ve açık komutlarıyla birlikte unutulur. `interval` değerinden büyük olmalıdır. |
| `asl.admin.server.instance.max-instances` | `2000` | İzlenen replica sayısının sert sınırı; üzerindeki kayıt `503` alır. |
| `asl.admin.server.max-body-size` | `64KB` | Kabul edilen en büyük istek (üzerinde `413`). |
| `asl.admin.server.signature-tolerance` | `60s` | Uygulama ile sunucu arasında kabul edilen saat farkı. |
| `asl.admin.server.commands.ttl` | `5m` | Cevaplanmayan komutlar `EXPIRED` olarak raporlanır. |
| `asl.admin.server.commands.max-pending` | `32` | Replica başına açık komut sayısı (üzerinde `429`). |
| `asl.admin.server.commands.max-results` | `50` | Uygulama başına bellekte tutulan tamamlanmış komut sonucu. |
| `asl.admin.server.storage.mode` | `FILE` | `FILE`: telemetri geçmişi, uygulama düzeyi override'lar ve audit tek bir MapDB dosyasında. `MEMORY`: aynı veri heap'te; restart'ta kaybolur, uygulamalar mevcut ayarlarıyla devam eder. İki modda da payload saklanmaz. |
| `asl.admin.server.storage.path` | `data/asl-admin.db` | MapDB dosyası (transaction'lı). ReadWriteOnce volume kullan. |
| `asl.admin.server.storage.max-size` | `64MB` | Dosya bu boyuta ulaşınca yeni geçmiş serisi açılmaz; mevcut seriler dönmeye devam eder, override'lar her zaman yazılır. En az `2MB` (MapDB'nin en küçük dosyası). |
| `asl.admin.server.history.enabled` | `true` | Her adımda her replica ve metodun son ham sayaçlarını saklar. |
| `asl.admin.server.history.resolution` | `1m` | Her adımda bir nokta (en az `1s`). |
| `asl.admin.server.history.retention` | `1h` | Seri başına nokta = retention / resolution (1–100 000); yerinde üzerine yazılan halka. Resolution veya retention değişirse geçmiş silinir. Nokta başına yaklaşık 150 bayt hesaplayın. |
| `asl.admin.server.history.max-series` | `2000` | Bir seri = bir replica × bir metot. Bu sayının üzerinde yeni seri açılmaz; retention boyunca nokta almayan seriler silinir. |
| `asl.admin.server.audit.enabled` | `true` | Override değişikliklerini ve kuyruk komutlarını kaydeder (browse kaydedilmez). |
| `asl.admin.server.audit.max-entries` | `1000` | Bu sayının üzerinde en eski kayıtlar silinir. |
| `asl.admin.server.audit.retention` | `7d` | Daha eski kayıtlar 10 dakikada bir silinir. |
| `asl.admin.server.security.mode` | `BASIC` | `BASIC`: aşağıdaki kullanıcılar. `PROXY_HEADER`: kimlik doğrulayan reverse proxy'nin (SSO) kullanıcı/grup başlıklarına güvenir. `NONE`: yalnız izole demo. |
| `asl.admin.server.security.users` | boş | Virgülle ayrılmış `ad:parola:ROL` girdileri; parola düz metin veya `{sha256}<hex>`, içinde `,` veya `:` olmamalı. Roller: `VIEWER` okur, `OPERATOR` değişiklik de yapar. `ASL_ADMIN_USER` + `ASL_ADMIN_PASSWORD` bu kısıtlar olmadan bir `OPERATOR` ekler. `BASIC` kullanıcı olmadan başlamaz. |
| `asl.admin.server.security.proxy.user-header` | `X-Forwarded-User` | `PROXY_HEADER` modunda doğrulanmış kullanıcıyı taşıyan başlık. |
| `asl.admin.server.security.proxy.groups-header` | `X-Forwarded-Groups` | Virgül veya boşlukla ayrılmış gruplar. |
| `asl.admin.server.security.proxy.operator-group` | `asl-operator` | Bu grubun üyeleri `OPERATOR` olur; diğer doğrulanmış kullanıcılar `VIEWER` olur. |
| `asl.admin.server.security.allowed-origins` | boş | Sunucunun kendi host'u dışında değişiklik göndermesine izin verilen ek origin'ler. Başka bir `Origin`/`Referer` ile gelen değişiklikler `403` alır. |

## MapDB Motoru

### Store ve Yaşam Döngüsü

| Property | Varsayılan | Ne işe yarar, verilmezse ne olur? |
|---|---:|---|
| `asl.async.mapdb.enabled` | `false` | `true` olduğunda MapDB async motorunu oluşturur ve registry’ye bağlar. |
| `asl.async.mapdb.path` | `./data/asl-queue.db` | Database dosya yolu; üst dizin yoksa oluşturulur. |
| `asl.async.mapdb.codec` | `jackson-json` | `jackson-json` veya `java-object-stream`; özel `AsyncPayloadCodec` bean’i önceliklidir. |
| `asl.async.mapdb.worker-shutdown-await-millis` | `10000` | Coordinator, worker ve writer shutdown aşamalarının her biri için en fazla bekleme süresi. |
| `asl.async.mapdb.registration-idle-sleep-millis` | `100` | Kayıtlı lane henüz hazır değilken kullanılan bekleme. |
| `asl.async.mapdb.empty-queue-sleep-millis` | `50` | Coordinator’ın boş kuyrukta en uzun polling aralığı. |
| `asl.async.mapdb.requeue-delay-millis` | `75` | Ertelenen işler için korunan uyumluluk gecikmesi. |
| `asl.async.mapdb.recovered-in-progress-message` | `Recovered stale in-progress invocation after restart` | Sahipsiz claim recovery sırasında kayda eklenen açıklama. |
| `asl.async.mapdb.transactions-enabled` | `true` | Commit/rollback dayanıklılığı ve group commit açılır. `false` güvenlik uyarısı üretir ve yalnızca gözden çıkarılabilir iş yükü içindir. |
| `asl.async.mapdb.memory-mapped-enabled` | `false` | Destekleniyorsa memory-mapped I/O ister; platforma bağlı lock/unmap riskini azaltmak için kapalı gelir. |
| `asl.async.mapdb.corruption-policy` | `FAIL_FAST` | `FAIL_FAST` inceleme için store’u korur; `ARCHIVE_AND_RESET` arşivleyip boş kuyruk açar. |
| `asl.async.mapdb.reset-if-corrupt` | eski alias | `corruption-policy` için geriye uyumluluk alanıdır; yeni ayarlarda kullanılmamalıdır. |

### Kabul ve Kapasite

| Property | Varsayılan | Ne işe yarar, verilmezse ne olur? |
|---|---:|---|
| `asl.async.mapdb.admission.max-total-entries` | `100000` | Bütün store için kalıcı kayıt üst sınırı. |
| `asl.async.mapdb.admission.max-total-payload-bytes` | `536870912` | Bütün store için payload üst sınırı (512 MiB). |
| `asl.async.mapdb.admission.default-lane-max-entries` | `10000` | Override verilmeyen metot için kayıt sınırı. |
| `asl.async.mapdb.admission.default-lane-max-payload-bytes` | `67108864` | Override verilmeyen metot için payload sınırı (64 MiB). |
| `asl.async.mapdb.admission.max-single-payload-bytes` | `1048576` | Tek çağrının encode edilmiş en büyük payload’u (1 MiB). |
| `asl.async.mapdb.admission.max-concurrent-enqueues` | `64` | Serialization ile kalıcı kabulü birlikte sınırlar; request fan-out’un sınırsız byte dizisi tutmasını engeller. |
| `asl.async.mapdb.admission.overflow-policy` | `REJECT` | `REJECT` hemen reddeder; `BLOCK_WITH_TIMEOUT` kapasiteyi süreli bekler. |
| `asl.async.mapdb.admission.enqueue-timeout-millis` | `0` | `BLOCK_WITH_TIMEOUT` bekleme bütçesi; bu modda pozitif olmalıdır. |

Lane bazlı sınır örneği:

`asl.async.mapdb.services` metot bazlı kapasite override map’inin köküdür. Altındaki ilk anahtar governed service ID’si, sonraki `methods` bölümü ise gerçek metot ID/imzasıdır. Yukarıdaki gibi iki anahtar da köşeli parantez içinde yazılır; noktaları veya parantezleri kaybolmuş bir anahtar startup'ı durdurur.

```yaml
asl:
  async:
    mapdb:
      services:
        "[mail.service]":
          methods:
            "[publishAudit(java.lang.String,java.lang.String)]":
              max-entries: 20000
              max-payload-bytes: 134217728
```

### Writer

| Property | Varsayılan | Ne işe yarar, verilmezse ne olur? |
|---|---:|---|
| `asl.async.mapdb.writer.queue-capacity` | `4096` | Bounded komut kuyruğu. Payload taşıyan komutlar ayrıca `max-concurrent-enqueues` ile sınırlıdır. |
| `asl.async.mapdb.writer.commit-batch-size` | `64` | Tek commit’te birleştirilecek en fazla mutasyon. |
| `asl.async.mapdb.writer.commit-batch-window-millis` | `2` | Group commit için komut toplama süresi; sıfır ek beklemeyi kapatır. |
| `asl.async.mapdb.writer.operation-timeout-millis` | `30000` | Caller’ın kalıcı writer sonucunu bekleme sınırı; timeout durumunda sonuç bilinmiyor kabul edilir. |

### Worker ve Lease

| Property | Varsayılan | Ne işe yarar, verilmezse ne olur? |
|---|---:|---|
| `asl.async.mapdb.workers.max-threads` | `max(2, min(availableProcessors, 32))` | Ortak worker havuzunun üst sınırı. Container ortamında açıkça ayarlanması önerilir. |
| `asl.async.mapdb.workers.task-queue-capacity` | `1024` | Scheduler ile worker arasındaki bounded görev kuyruğu. |
| `asl.async.mapdb.workers.lease-duration-millis` | `60000` | Claim’in geçerli kaldığı süre. |
| `asl.async.mapdb.workers.lease-renewal-interval-millis` | `20000` | Lease yenileme aralığı; lease süresinden kısa olmalıdır. Aktif lease'ler tek commit ile yenilenir; canlı bir worker'a ait olmayan IN_PROGRESS kayıtlar lease süresi dolunca kuyruğa geri döner. |
| `asl.async.mapdb.workers.drain-backlog-in-sync-mode` | `true` | Lane SYNC moda geri alındığında kabul edilmiş backlog'u tüketmeye devam eder (consumer thread > 0 gerekir). `false` olursa iş, lane tekrar ASYNC olana kadar kuyrukta bekler. |
| `management.health.aslmapdb.enabled` | `true` | Actuator classpath'teyse `aslMapDb` health bileşenini kaydeder. |
| `asl.async.mapdb.workers.execution-timeout-millis` | `0` | Bu süreyi aşan iş çalıştırmasını interrupt eder ve `TIMEOUT` hatası kaydeder (deneme sayılır). `0` kapalıdır. Interrupt iş birliğine dayalıdır: interrupt'ı yok sayan kod worker thread'ini tutmaya devam eder. |
| `asl.async.mapdb.workers.completion-timeout-millis` | `300000` | ASYNC `CompletableFuture` metotlar: worker'ın metodun döndürdüğü future'ı bekleyeceği en uzun süre. Aşılırsa çalıştırma `AsyncCompletionTimeoutException` ile, `TIMEOUT` kategorisinde başarısız olur (deneme hakkı varsa yeniden denenir) ve metodun future'ı iptal edilir. Pozitif olmalıdır. |
| `asl.async.mapdb.workers.result-wait-timeout-millis` | `600000` | ASYNC `CompletableFuture` metotlar: çağıranın future'ının sonucu en fazla ne kadar bekleyeceği. Aşılırsa future `AsyncResultUnavailableException(WAIT_TIMEOUT)` ile biter; çağrı yine çalışır ve sonucu `AsyncResultHandler`'a gider. Pozitif olmalıdır. |
| `asl.async.mapdb.workers.max-pending-results` | `10000` | ASYNC `CompletableFuture` metotlar: aynı anda sonuç bekleyebilecek çağıran sayısı. Fazlası, kuyruğa hiçbir şey yazılmadan `AsyncAdmissionRejectedException(RESULT_CAPACITY)` ile reddedilir. Pozitif olmalıdır. |

### Retry ve Idempotency

| Property | Varsayılan | Ne işe yarar, verilmezse ne olur? |
|---|---:|---|
| `asl.async.mapdb.retry.max-attempts` | `1` | İlk çalışma dâhil toplam iş denemesi sayısı; varsayılan, iş hatasından sonra otomatik retry yapmaz. Kesintiler deneme tüketmez. |
| `asl.async.mapdb.retry.max-interruptions` | `3` | Graceful shutdown, crash veya süresi dolan lease ile kesilen bir çalıştırmanın deneme tüketmeden kuyruğa geri dönme sayısı. Sonraki kesinti kaydı `FAILED` yapar; böylece process'i sürekli düşüren bir payload sonsuz döngüye girmez. |
| `asl.async.mapdb.retry.initial-backoff-millis` | `250` | İlk retry gecikmesi. |
| `asl.async.mapdb.retry.max-backoff-millis` | `30000` | Üstel beklemenin üst sınırı. |
| `asl.async.mapdb.retry.jitter-ratio` | `0.20` | Beklemeye uygulanan rastgele ± oran; 0.0-1.0 aralığındadır. |
| `asl.async.mapdb.idempotency.retention-millis` | `86400000` | Tamamlanan anahtarın tombstone olarak tutulma süresi (24 saat). |
| `asl.async.mapdb.idempotency.max-entries` | `100000` | Aktif anahtarlar ile saklanan tombstone’ların toplam üst sınırı. |
| `asl.async.mapdb.idempotency.max-key-characters` | `256` | Idempotency anahtarının en büyük karakter sayısı. |

### Payload Güvenliği ve Maskeleme

| Property | Varsayılan | Ne işe yarar, verilmezse ne olur? |
|---|---:|---|
| `asl.async.mapdb.payload.max-summary-characters` | `512` | Store/admin payload özetinin karakter sınırı. |
| `asl.async.mapdb.payload.max-error-characters` | `2048` | Saklanan hata ve tip bilgisinin karakter sınırı. |
| `asl.async.mapdb.payload.summary-policy` | `TYPE_ONLY` | `NONE`, `TYPE_ONLY` veya `CODEC`; codec özeti business data içerebilir. |
| `asl.async.mapdb.payload.error-detail-policy` | `TYPE_ONLY` | `TYPE_ONLY` veya `TYPE_AND_MESSAGE`; hata mesajı hassas değer içerebilir. |
| `asl.async.mapdb.payload.allowed-type-names` | boş | Codec’in kabul edeceği ek tam Java tip adları. |
| `asl.async.mapdb.payload.allowed-type-prefixes` | boş | Kabul edilecek ek package başlangıçları; deserialization yüzeyini büyütmemek için dar tutulmalıdır. |
| `asl.async.mapdb.payload.max-deserialization-depth` | `32` | Java object filter derinlik sınırı. |
| `asl.async.mapdb.payload.max-deserialization-references` | `10000` | Java object filter referans sınırı. |
| `asl.async.mapdb.payload.max-deserialization-array-length` | `100000` | Java object filter dizi uzunluğu sınırı. |
| `asl.async.mapdb.payload.legacy-java-migration-enabled` | `false` | Eski Java-serialized queue kayıtlarını tek seferlik taşımak için açılır; önce yedek alınmalı, işlemden sonra kapatılmalıdır. |

Hazır Jackson güvenli tipleri primitive wrapper’ları, `String`, yaygın sayı/UUID tipleri ve `java.time.*` sınıflarını içerir. Kayıtlı argüman şemaları ayrıca doğrulanır. Özel allowlist’in boş olması uygulamadaki her sınıfın kabul edildiği anlamına gelmez.

## Boyutlandırma ve Operasyon Notları

- `max-concurrent-enqueues × max-single-payload-bytes`, codec ek yükü hariç aktif kabul sırasında tutulabilecek en kötü encode payload zarfıdır. Bu değer heap bütçesinin altında kalmalıdır.
- Global/lane byte sınırları kalıcı payload’u korur; dış business verisini veya MapDB metadata ek yükünü kapsamaz.
- Writer timeout, caller’ın commit sonucunu artık kesin bilemediği anlamına gelir. Böyle bir çağrı yalnızca idempotency anahtarıyla tekrar edilmelidir.
- Consumer thread sayısı, ortak worker havuzu ve metot max concurrency ayrı sınırlardır; gerçek lane paralelliği bu üç değerin en küçüğüdür.
- Admin üzerinden yapılan değişiklikler yalnızca o JVM’de geçerlidir ve cluster konfigürasyonu olarak saklanmaz.
- MapDB transaction’ını kapatmak ve `ARCHIVE_AND_RESET` kullanmak bilinçli risk tercihleridir; performans varsayılanı değildir.
