# Admin server ve portal

**Dil:** [English](../../docs/admin-server.md) | Türkçe · [Dokümantasyon ana sayfası](../README.md#dokümantasyon)

Admin server, operatörlere tüm uygulamalar ve replica'lar için tek bir portal veren küçük bir process'tir. Bir config server gibi çalışır: telemetriyi gösterir, ayarları ve komutları aktarır; iş mantığı içermez. Uygulamalar admin endpoint'i açmaz; server'a kendileri bağlanır.

## Nasıl çalışır

```
replica ── kayıt, sonra her 15 sn'de heartbeat (portal açıkken 2 sn) ──> admin server <── tarayıcı (portal)
        <── ayarlar ve komutlar heartbeat cevabında ──────────────────────
```

| Parça | Nerede çalışır | Ne tutar |
|---|---|---|
| `asl-admin-server` | ayrı bir process (`java -jar …-exec.jar`), web framework yok | bellekte: bağlı replica'lar, son sayaçları, açık komutlar. Tek bir MapDB dosyasında: telemetri geçmişi, uygulama düzeyindeki override'lar, audit log |
| `asl-spring-boot-admin-client-starter` | her uygulamanın içinde, tek bir daemon thread | hiçbir şey; arayüz yok, dışarıdan erişilen endpoint yok |

- **Trafik küçüktür.** Heartbeat, yalnızca değişen değerleri taşıyan imzalı bir ikili mesajdır (HMAC-SHA256, ±60 sn saat farkı). Değişiklik yokken 10–15 bayttır.
- **Uygulamalar ona bağımlı değildir.** Server'a ulaşılamazsa uygulama son ayarlarını korur, beklemeyi artırır (en fazla 60 sn) ve telemetriyi biriktirmeden atar. Yönetilen çağrılar admin bağlantısını hiç beklemez.
- **Payload pod'dan çıkmaz.** Kuyruk kayıtları replica'nın kendi kuyruğunda kalır. Replica, yalnızca `asl.admin.client.queue-browse=true` ise kayıt özetlerini ve hata metinlerini gönderir; server bunları sadece bellekte tutar.

## Server'ı çalıştırın

Lokalde, ortam değişkenleriyle:

```bash
ASL_ADMIN_SECRET=degistirin-en-az-16-karakter \
ASL_ADMIN_USER=admin ASL_ADMIN_PASSWORD=degistirin \
./asl-admin-server.sh            # veya: java @asl-admin-server.jvmopts -jar asl-admin-server-<surum>-exec.jar
```

`http://localhost:8080/asl-admin/` adresini açıp giriş yapın.

`asl-admin-server.sh`, `.cmd` ve `.jvmopts` dosyaları `asl-admin-server/src/main/dist` altındadır; jar'ın yanında tutun. Options dosyası az kaynak kullanan bir JVM ayarlar (varsayılan 16 MB heap). Bellek yetmezse server durur, böylece Kubernetes onu yeniden başlatır.

Ayarlar çalışma dizinindeki `asl-admin.properties` dosyasından (veya `--config=<yol>`), JVM property'lerinden veya `--anahtar=değer` argümanlarından da gelebilir. Hepsi [yapılandırma referansındadır](configuration.md#admin-server).

### Kubernetes

- MapDB dosyası için küçük bir ReadWriteOnce volume ile tek replica (Deployment). Ya da `storage.mode=MEMORY` verip volume kullanmayın; o zaman override'lar ve geçmiş restart'ta kaybolur, uygulamalar mevcut ayarlarını korur.
- Portalı (`/asl-admin/`) TLS'li ingress üzerinden yayınlayın; server düz HTTP konuşur.
- `/asl/v1/*` (heartbeat endpoint'i) yalnızca cluster içinden erişilebilir kalsın.
- Server, `ASL_ADMIN_SERVER_PORT=tcp://…` gibi Kubernetes service-link değişkenlerini yok sayar. `enableServiceLinks: false` bunları tamamen kaldırır.

## Bir uygulamayı bağlayın

```yaml
asl:
  admin:
    client:
      enabled: true
      server-url: http://asl-admin-server:8080
      secret: ${ASL_ADMIN_SECRET}     # server ile aynı değer
      queue-browse: true              # isteğe bağlı: portalın kuyruk kayıtlarını listelemesine izin verir
```

Uygulama adı varsayılan olarak `spring.application.name`, replica adı ise `HOSTNAME`'dir (Kubernetes'te pod adı).

## Portalın kullanımı

### Uygulamalar ve metotlar

- **Sol menü:** uygulamalar. Ada göre veya "ilgilenilmesi gerekenler"e (ayar hatası, başarısız kuyruk kaydı, ayarı henüz uygulamayan replica) göre filtrelenebilir.
- **Metot listesi:** tüm replica'ların toplamı (çalışan, başarılı, hatalı, reddedilen, kuyruk). Ada, servise, replica'ya veya duruma (durdurulmuş, async, kuyruk dolu, başarısız kayıt, hata, override'lı, replica'lar farklı) göre filtrelenir. Bir replica seçerseniz yalnız onun sayıları görünür.
- **Metot detayı** (satırı açın): her replica için bir satır ve güncel değerleri (enabled, mod, max, consumer'lar, rate limit, çalışan, hata, kuyruk), ayrıca saklanan geçmişten trend grafikleri.
- **Etiketler:**
  - *All: …* uygulama düzeyinde bir override'dır.
  - *Replica: …* replica düzeyinde bir override'dır.
  - *Replicas differ*, replica'ların aynı ayarlarla çalışmadığını gösterir.
  - Saat simgesi süreli bir override'ı işaretler ve kalan süreyi gösterir.

### Tune: bir metodu değiştirmek

**Tune** (tüm replica'lar) veya **Tune this replica** ayar penceresini açar:

| Alan | Etkisi | Anlatıldığı yer |
|---|---|---|
| Method | Running / Stopped | [Enabled](concepts.md#enabled) |
| Execution mode | SYNC / ASYNC (yalnız async-capable metotlar) | [SYNC ve ASYNC](concepts.md#sync-ve-async) |
| Max concurrency | replica başına paralel çağrı | [Max concurrency](concepts.md#max-concurrency) |
| Consumer threads | replica başına paralel kuyruk işi; 0 kuyruğu bekletir | [Consumer'lar](concepts.md#consumerlar) |
| Rate limit (calls/s) | replica başına saniyede başlayan çağrı; 0 sınırı kaldırır | [Rate limit](concepts.md#rate-limit) |
| Keep for | Permanent veya 15 dk / 1 sa / 4 sa / 24 sa | [Süreli override'lar](#süreli-overridelar) |

Boş alanlar uygulamanın kendi değerini korur. **Remove override**, metodu yml veya annotation değerine döndürür. Replica'lar değişikliği bir sonraki heartbeat'te uygular; portal açıkken bu yaklaşık 2 saniye sürer. Bir replica son ayarları uyguladığında *in sync* görünür.

### Süreli override'lar

**Keep for** alanında bir süre seçin. Süre dolunca metot önceki override'a döner; önceden override yoksa yml değerine döner. Bu dönüş audit log'a `system` kullanıcısıyla `override.expired` olarak yazılır.

- Süreli bir override tekrar süreli değiştirilirse ilk "önceki" değer korunur.
- Kalıcı bir değişiklik veya **Remove override** zamanlayıcıyı iptal eder.
- Uygulama düzeyindeki zamanlayıcılar server restart'ından etkilenmez; replica zamanlayıcıları replica ile birlikte gider.

### Kuyruk işlemleri

Bir metodun **queue** butonu (veya *failed* etiketi) kuyruk penceresini açar. Pencere replica'lardan kayıtlarını ister; cevaplar bir sonraki heartbeat ile gelir.

| İşlem | Ne yapar |
|---|---|
| Browse / Refresh | kayıtları listeler: durum, deneme sayısı, kuyruğa alınma zamanı, payload özeti, hata kategorisi ve son hata |
| Replay (tek kayıt) | başarısız bir kaydı kuyruğa geri koyar |
| Delete (tek kayıt) | çalışmayan bir kaydı siler |
| **Bulk…** | bir filtreye uyan tüm kayıtları replay eder veya siler (durumlar, hata kategorisi, zaman aralığı, replica başına en fazla N, üst sınır 10.000). Kayıtları replica'nın kendisi seçer; bu yüzden listede görünmeyen kayıtlara da ulaşır. Önce kapsamı tam olarak gösteren bir onay gelir. |
| Clear queue | çalışmayan tüm kayıtları siler |

Çalışan kayıtlara hiç dokunulmaz; replay yalnızca başarısız kayıtlarda çalışır. Tek bir toplu komut en fazla 50.000 kayda bakar ve tek commit yapar.

Payload özeti, uygulamanın `asl.async.mapdb.payload.summary-policy` ile sakladığı değerdir: varsayılanda payload tipi, `CODEC` ile argümanların kısa metni. `CODEC`'i yalnızca bu metnin gösterilmesi güvenliyse kullanın.

### Komutlar ve audit

- **Recent commands** her komutu, replica'yı, sonucu ve kimin gönderdiğini gösterir. Cevapsız komutlar 5 dakika sonra süresi dolmuş sayılır.
- **Audit log**, her override değişikliğini, süre dolumunu ve kuyruk komutunu (browse hariç) kullanıcı ve zamanla listeler. Varsayılan olarak 7 gün tutulur.

## Güvenlik

| `security.mode` | Kullanım |
|---|---|
| `BASIC` (varsayılan) | kullanıcılar `security.users`'tan (`ad:şifre:ROL`) veya `ASL_ADMIN_USER` + `ASL_ADMIN_PASSWORD`'den gelir. `VIEWER` rolü okur, `OPERATOR` değişiklik de yapar. |
| `PROXY_HEADER` | kimlik doğrulayan bir proxy (SSO) arkasında; kullanıcı ve gruplar header'lardan gelir, `asl-operator` grubunun üyeleri `OPERATOR` olur. |
| `NONE` | yalnızca izole lokal demo. |

- Yabancı bir `Origin`'den gelen değişiklikler reddedilir (`403`).
- Heartbeat'ler ortak secret ister.
- Server TLS sonlandırmaz; TLS'li bir ingress arkasına koyun.

## Boyutlandırma

Bellek, istek hızına değil replica × metot çiftlerinin sayısına bağlıdır. Replica başına 50 metotla ölçülmüştür:

| Replica | GC sonrası heap | `-Xmx` | Server CPU |
|---:|---:|---:|---:|
| 300 | ~7 MB | `16m` (varsayılan) | ~0,5 çekirdek |
| 1.000 | ~19 MB | `48m` | ~0,7 çekirdek |

Filo yaklaşık 300 replica × 50 metodu geçmeden `-Xmx`'i artırın. Yukarıdaki ölçümler 2 sn heartbeat ile yapıldı; varsayılan 15 sn'de yük çok daha düşüktür.

Geçmiş, her replica ve metot için sabit boyutlu bir halkadır (`history.retention / history.resolution` nokta). Bu yüzden dosya bir noktadan sonra büyümez. Nokta başına yaklaşık 150 bayt planlayın.

## Portal API'si

Portal, `/asl-admin/api` altında küçük bir JSON API'si kullanır. Scriptler aynı girişle kullanabilir. Değişiklik yapan çağrılar `OPERATOR` rolü ister.

| Endpoint | Amaç |
|---|---|
| `GET /apps` | bağlı uygulamalar |
| `GET /apps/{app}` | metotlar, son sayaçlarıyla replica'lar, override'lar, zamanlayıcılar, komut sonuçları |
| `GET /apps/{app}/history?serviceId=&methodId=` | replica başına saklanan geçmiş |
| `POST /apps/{app}/overrides` | form: `serviceId`, `methodId`, isteğe bağlı `instance`, `enabled`, `async`, `maxConcurrency`, `consumerThreads`, `rateLimit`, `durationSeconds` (10 sn – 7 gün) |
| `DELETE /apps/{app}/overrides?serviceId=&methodId=[&instance=]` | override'ı kaldırır |
| `POST /apps/{app}/commands` | form: `type`, `serviceId`, `methodId`, isteğe bağlı `instance` (boşsa tüm replica'lar), `entryId`, `limit`; `commandIds` döner |
| `GET /audit?limit=100` | audit log |
| `GET /server` | server sayaçları |

Komut tipleri: `BROWSE_QUEUE`, `REPLAY_ENTRY`, `DELETE_ENTRY`, `CLEAR_QUEUE`, `RESET_OBSERVATIONS`, `REPLAY_MATCHING`, `DELETE_MATCHING`. İki toplu tip ayrıca şu alanları alır: `states` (`FAILED`, `PENDING`, `RETRY_WAIT`; varsayılan `FAILED`), `category` (kategorisizler için `-`), `from` ve `to` (epoch ms) ve `limit` (replica başına 1–10.000).

```powershell
$api = "http://localhost:8080/asl-admin/api/apps/uygulamam"
Invoke-RestMethod -Method Post "$api/overrides" -Credential $cred -Body @{
  serviceId = "mail.service"; methodId = "send(java.lang.String)"; maxConcurrency = "2"; durationSeconds = "3600" }
```
