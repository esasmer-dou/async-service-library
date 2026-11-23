# Operasyon

**Dil:** [English](../../docs/operations.md) | Türkçe · [Dokümantasyon ana sayfası](../README.md#dokümantasyon)

Production'dan önce neyin kontrol edileceği, neyin izleneceği ve işler ters gittiğinde ASL'nin nasıl davrandığı.

## Güvenli varsayılanlar

ASL en güvenli yapılandırmasıyla başlar; geri kalan her şeyi siz açarsınız.

| Alan | Varsayılan |
|---|---|
| Kalıcı kuyruk | kapalı (`asl.async.mapdb.enabled=false`) |
| Admin client | kapalı. Açılınca en az 16 baytlık ortak bir secret ister ve port açmaz. |
| Admin portal | giriş gerekir (`BASIC`); kendi TLS'i yoktur, TLS'li bir ingress arkasında çalıştırın |
| Kuyruk boyutu | sınırlı (100.000 kayıt, 512 MiB; metot başına 10.000 kayıt); fazlası reddedilir |
| Tekrar deneme | toplam bir deneme. Yalnızca idempotent metotlarda artırın. |
| Dayanıklılık | MapDB transaction'ları açık. Bozuk bir depo açılışı durdurur (`FAIL_FAST`). |
| Hata ayrıntısı | yalnızca exception tipi, mesaj yok; payload özetleri yalnızca tipi içerir |
| Payload tipleri | özel sınıflar allowlist'e eklenmeli veya kaydedilmelidir |

## Production kontrol listesi

- [ ] Kuyruğa alınan (ASYNC) metotlar idempotent. Teslim at-least-once biçimindedir.
- [ ] Kuyruk dosyası bu replica'ya ait kalıcı bir volume'da (ReadWriteOnce), asla paylaşılmıyor.
- [ ] Kuyruk limitleri volume'a ve heap'e sığıyor ([Boyutlandırma kuralları](configuration.md#boyutlandırma-kuralları)).
- [ ] `asl.async.mapdb.workers.max-threads` container'ın CPU limitine uygun.
- [ ] Graceful shutdown en az `worker-shutdown-await-millis` (10 sn) kadar sürüyor; böylece çalışan çağrılar bitebilir.
- [ ] `asl.runtime.unwrapped-governed-bean-policy: FAIL`; eksik bir wrapper yönetimi atlayamaz.
- [ ] Admin portal TLS arkasında ve rollü `BASIC` kullanıcıları veya SSO ile `PROXY_HEADER` kullanıyor.
- [ ] Aşağıda *alarm* olarak işaretlenen metrikler için alarm var.

## Deployment sınırları

- **Kuyruk dosyası başına tek sahip.** Aynı dosyayı açan ikinci process `MapDbStoreLockedException` alır. Ağ dosya sistemleri (NFS, SMB, EFS, Azure Files) kilidi güvenilir uygulamayabilir; her replica için bir ReadWriteOnce volume kullanın.
- **Replica'lar arası kuyruk yok.** Her replica yalnızca kendi kabul ettiği işi işler. Replica'lar arasında paylaşılması veya tek bir mantıksal kuyruk üzerinde HPA ile ölçeklenmesi gereken iş bir message broker'a aittir.
- **Limitler replica başınadır.** Genel bir kotayı replica sayısına bölün.

## Metrikler

Bir `MeterRegistry` varsa Micrometer üzerinden yayınlanır (`asl.observability.enabled`, varsayılan açık). Servis ve metot birer tag'dir.

| Metrik | Anlamı | |
|---|---|---|
| `asl.method.success`, `.errors`, `.rejected` | sonuca göre çağrılar | artan red oranı için alarm |
| `asl.method.in.flight`, `.peak.in.flight` | şu an çalışan / görülen en yüksek çağrı | |
| `asl.method.max.concurrency`, `.enabled`, `.async.mode`, `.consumer.threads` | güncel ayarlar | |
| `asl.method.duration` | gecikme timer'ı (`asl.observability.method-latency-enabled` ile açılır) | |
| `asl.mapdb.lane.pending`, `.in.progress`, `.retry.wait`, `.failed` | metot başına kuyruk kayıtları | `failed` > 0 için alarm |
| `asl.mapdb.lane.oldest.pending.age` | bekleyen en eski çağrının yaşı (ms) | **alarm**: kuyruk derinliğinden daha iyi bir gösterge |
| `asl.mapdb.engine.healthy`, `asl.mapdb.writer.accepting` | 1 = sorun yok, 0 = kuyruk bir depolama hatası sonrası durdu | 0 için **alarm**; restart gerekir |
| `asl.mapdb.execution.timeouts`, `.leases.reclaimed`, `.released.on.shutdown` | kesilen veya kurtarılan çalıştırmalar | |
| `asl.mapdb.writer.*`, `asl.mapdb.enqueue.*`, `asl.mapdb.worker.*` | writer, kabul ve worker iç metrikleri | |

Yönetilen metot sayısı `asl.observability.max-method-meters`'ı (2000) aşarsa açılış durur; böylece metrik kardinalitesi kazara büyüyemez. Yayınlanan sayaçlar hiç sıfırlanmaz; portaldaki *Reset statistics* yalnızca portalda gösterileni değiştirir.

## Health

Spring Boot Actuator varsa `aslMapDb` health bileşeni:

- writer durduysa, depo hata verdiyse veya coordinator 60 sn çalışmadıysa **DOWN** olur;
- aksi halde **UP** olur. Kuyruk birikmesi yalnızca ayrıntı olarak gösterilir; bekleyen sonuçlar, timeout'lar ve rate limit nedeniyle ertelenen işler de orada görünür.

`management.health.aslmapdb.enabled=false` ile kapatılır.

## Hata ve kurtarma

| Olay | Ne olur |
|---|---|
| Çağrılar çalışırken çökme veya kill | restart sonrası kuyruğa dönüp tekrar çalışırlar (at-least-once); `retry.max-interruptions` (3) sonrasında `FAILED` olarak işaretlenirler |
| Graceful shutdown | çalışan çağrılar `worker-shutdown-await-millis` içinde bitebilir; kalanlar kuyruğa döner |
| Bir çalıştırma takılır | `workers.execution-timeout-millis` (varsayılan kapalı) onu keser ve `TIMEOUT` kaydeder |
| Uzun bir duraklamadan sonra eski worker | lease'inin süresi dolmuştur; geç gelen sonucu yeni sahibin kaydını ezemez |
| Aynı dosyada ikinci process | `MapDbStoreLockedException` ile reddedilir |
| Bozuk depo | `FAIL_FAST` açılışı durdurur ve dosyayı korur. `ARCHIVE_AND_RESET` bozuk dosyaları kenara alıp boş başlar: bilinçli bir veri kaybı tercihi. |
| Disk dolu, yetki, I/O hataları | ölümcül sayılır, asla bozulma olarak ele alınmaz |
| Admin server'a ulaşılamaz | uygulamalar son ayarlarını korur; çağrılar etkilenmez |

Her gece çalışan bir CI testi, örnek uygulamayı bekleyen, başarısız ve çalışan işler varken zorla öldürür ve hiçbir şeyin kaybolmadığını kontrol eder ([raporlar](../reports/README.md)).

## Kubernetes

- CPU ve bellek request/limit'lerini verin; `workers.max-threads`'i CPU limitine göre ayarlayın.
- Kuyruk için her replica'ya bir ReadWriteOnce volume (StatefulSet) verin ya da pod ile birlikte kuyruğun kaybolmasını kabul edin.
- `terminationGracePeriodSeconds` shutdown bekleme süresinden uzun olsun.
- Tek bir admin server'ı küçük bir ReadWriteOnce volume ile çalıştırın; bkz. [Admin server ve portal](admin-server.md#kubernetes).

## Sürüm kontrolleri

```powershell
mvn -B -ntp clean verify                                          # testler ve modül başına coverage alt sınırı
.\scripts\run-api-compatibility-gate.ps1                          # public API, son sürüm etiketine karşı
.\scripts\run-data-plane-performance-gate.ps1 -Threads 1,8,32,128 # JMH: yönetilen ve doğrudan çağrılar
mvn -B -ntp -Psbom -DskipTests package                            # CycloneDX SBOM: target/bom.json|xml
```

CI, Windows ve Linux üzerinde Temurin ve Semeru (OpenJ9) Java 21 ile çalışır. Pull request'lerde bağımlılık incelemesi yapılır (yüksek önemli açıkta başarısız olur); Dependabot haftalık güncelleme önerir. Performans kontrolü gerilemeleri yakalar ama sıfır ek yük iddia etmez. Yönetilen bir çağrı her zaman policy'sini okur ve sayaçları günceller; başarılı bir SYNC çağrı ise fazladan nesne oluşturmaz.
