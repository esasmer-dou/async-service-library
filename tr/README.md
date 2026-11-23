# Async Service Library (ASL)

**Dil:** [English](../README.md) | Türkçe

ASL, seçtiğiniz Spring servis metotlarını yeniden deploy etmeden, çalışma anında yönetmenizi sağlar. Her metot için şunları yapabilirsiniz:

- metodu **durdurup başlatmak**,
- aynı anda kaç çağrının çalışacağını veya saniyede kaç çağrının başlayacağını **sınırlamak**,
- metodu hemen çalıştırma (SYNC) ile kalıcı kuyruktan çalıştırma (ASYNC) arasında **geçirmek**,
- kuyrukta başarısız olan çağrıları **yeniden çalıştırmak veya silmek**.

Bunların hepsini, tüm uygulamalar ve replica'lar için tek bir merkezi admin portalından yaparsınız.

ASL uygulamanızın içinde çalışır. Wrapper'ları derleme anında üretir (reflection yok); her kuyruk ve her limit varsayılan olarak sınırlıdır.

## Gereksinimler

| | Sürüm |
|---|---|
| Java | 21 veya üstü |
| Spring Boot | 3.3.x |
| Maven | 3.9.x |

## Modüller

Yalnızca ihtiyacınız olanı ekleyin.

| İhtiyaç | Eklenecek | MapDB getirir |
|---|---|:---:|
| Metotları durdurma/sınırlama, metrik | `asl-spring-boot-core-starter` | hayır |
| Kalıcı ASYNC kuyruk | `asl-spring-boot-mapdb-starter` (core'u içerir) | evet |
| Uygulamayı admin portalından yönetme | `asl-spring-boot-admin-client-starter` | hayır |
| Hepsi | `asl-spring-boot-starter` | evet |
| Admin portalın kendisi (ayrı process) | `asl-admin-server` | evet |

Her uygulamada ayrıca `asl-annotations` ve derleme anı processor'ü `asl-processor` gerekir. Hiçbir modül uygulamanıza web framework eklemez.

## Beş dakikada

```java
@GovernedService(id = "mail.service")
public interface MailService {

    @GovernedMethod(initialMaxConcurrency = 4)          // aynı anda en fazla 4 çağrı
    String send(String mailId);

    @GovernedMethod(asyncCapable = true)                // kalıcı kuyruğa alınabilir
    void publishAudit(@AsyncIdempotencyKey String eventId, String mailId);
}
```

Interface'i normal bir `@Service` ile implement edin; `MailService`'i (implementation'ı değil) inject edip çağırın. Kontrolleri üretilen wrapper uygular. Ayarları daha sonra `application.yml`'de veya portalda değiştirebilirsiniz.

Adım adım: [Başlarken](docs/getting-started.md).

## Dokümantasyon

| Sayfa | Ne için |
|---|---|
| [Başlarken](docs/getting-started.md) | ASL'yi bir servise ekleyip ilk yönetilen metodu çalıştırmak |
| [Kavramlar](docs/concepts.md) | her kontrolü anlamak: enabled, max concurrency, SYNC/ASYNC, consumer'lar, rate limit, retry, kuyruk durumları |
| [Senaryolar](docs/scenarios.md) | gerçek bir durum için doğru ayarı bulmak: yavaş bağımlılık, partner kotası, trafik artışı, kesinti, düzeltme sonrası replay |
| [Admin server ve portal](docs/admin-server.md) | merkezi portalı çalıştırmak ve kullanmak |
| [Yapılandırma referansı](docs/configuration.md) | her property'yi, varsayılanını ve etkisini bulmak |
| [Operasyon](docs/operations.md) | production'a çıkmak: varsayılanlar, limitler, metrikler, health, kurtarma, Kubernetes |
| [Sorun giderme](docs/troubleshooting.md) | bir belirtiyi nedeniyle eşleştirmek |
| [Örnek uygulama](asl-consumer-sample/README.md) | her şeyi lokalde denemek |

## Önemli sınırlar

- **Process başına bir kuyruk.** ASYNC kuyruk lokal bir MapDB dosyasıdır. Her replica'nın kendi kuyruğu vardır; bir kuyruk dosyası pod'lar arasında asla paylaşılmaz. Replica'lar arasında paylaşılması gereken iş bir message broker'a aittir.
- **At-least-once.** Kuyruktaki bir çağrı, bir çökme sonrasında yeniden çalışabilir. Kuyruğa alınan metotları idempotent yapın.
- **Limitler replica başınadır.** Üç replica'da `max-concurrency: 20` toplamda 60 çağrıya izin verir.

## Derleme ve doğrulama

```powershell
mvn -B -ntp clean verify
```

Sürüm kontrolleri (API uyumluluğu, performans, SBOM) [Operasyon](docs/operations.md#sürüm-kontrolleri) sayfasındadır.

## Dağıtım

- [GitHub deposu](https://github.com/esasmer-dou/async-service-library)
- [GitHub Packages](https://maven.pkg.github.com/esasmer-dou/async-service-library) (kurulum: [Başlarken](docs/getting-started.md#1-depoyu-ekleyin))
- [Sürümler](https://github.com/esasmer-dou/async-service-library/releases)
