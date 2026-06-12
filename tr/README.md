# Async Service Library

**Language:** [English](../README.md) | Turkish

Async Service Library (ASL), Java ve Spring Boot uygulamalarında servis metotlarını çalışma anında yönetilebilir hale getirir. Belirli metotları durdurabilir, tekrar açabilir, eş zamanlı çalışma limitlerini değiştirebilir, uygun `void` metotları kuyruğa alabilir ve bu davranışı admin arayüzünden izleyebilirsin.

ASL bir workflow motoru değildir. Ama yalnızca normal servis çağrısı da değildir. Uygulamanın içine gömülebilecek kadar hafif, operasyon sırasında müdahale edilebilecek kadar kontrollü bir ara katmandır.

## Neler Sağlar?

- Reflection ağırlıklı dispatch yerine derleme zamanında üretilen wrapper sınıfları kullanır.
- Metot bazında başlatma, durdurma ve maksimum concurrency kontrolü sağlar.
- `void` metotlar için kuyruk tabanlı `ASYNC` çalışma modunu destekler.
- Kuyruk kayıtlarını görüntüleme, silme, temizleme ve başarısız işleri tekrar çalıştırma imkânı verir.
- Consumer thread sayısını çalışma anında değiştirebilir.
- Spring Boot admin arayüzünü varsayılan olarak `/asl` altında açar.
- REST tabanlı kontrol yüzeyini varsayılan olarak `/asl/api` altında sunar.

## Ne Zaman Kullanılır?

ASL şu senaryolarda anlamlıdır:

- Belirli bir servis metodunu redeploy yapmadan geçici olarak durdurmak istiyorsan.
- Dış sistem çağrısı, bildirim gönderimi veya event publish gibi riskli akışları kontrollü şekilde yavaşlatmak istiyorsan.
- Seçilmiş `void` metotları gerektiğinde senkron çalıştırıp gerektiğinde kuyruğa almak istiyorsan.
- Queue depth, failed entry, in-progress iş ve pressure bilgisini tek panelden görmek istiyorsan.
- Kafka, Temporal veya ayrı bir workflow platformu kurmadan önce daha hafif bir uygulama içi kontrol katmanı istiyorsan.

## Bilerek Koyulan Sınırlar

- Runtime sırasında `SYNC -> ASYNC` geçişi yalnızca `void` metotlar için desteklenir.
- Response dönen metotlar yönetilebilir, durdurulabilir ve concurrency limitine alınabilir; fakat async moda geçirilemez.
- Her governed interface için Spring tarafında tek bir stereotype implementasyonu beklenir.
- Admin endpoint güvenliği otomatik kurulmaz. Production ortamında `/asl` ve `/asl/api` mutlaka uygulamanın kendi security katmanıyla korunmalıdır.

## Hızlı Örnek

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 4)
    String send(String payload);

    @GovernedMethod(asyncCapable = true, initialMaxConcurrency = 2, initialConsumerThreads = 0)
    void publish(String event);
}
```

```java
@Service
public class MailServiceImpl implements MailService {
    @Override
    public String send(String payload) {
        return "sent:" + payload;
    }

    @Override
    public void publish(String event) {
        // Kuyruğa alınmaya uygun iş mantığı.
    }
}
```

```yaml
asl:
  admin:
    enabled: true
    path: /asl
    api-path: /asl/api
  async:
    mapdb:
      enabled: true
      path: ./data/asl-queue.db
      codec: jackson-json
```

## Modüller

- `asl-annotations`: Kullanıcıya açık annotation tanımları.
- `asl-core`: Runtime governance modeli.
- `asl-processor`: Derleme zamanında wrapper üreten annotation processor.
- `asl-mapdb`: Kalıcı async kuyruk motoru.
- `asl-spring-boot-starter`: Spring Boot auto-configuration, admin UI ve REST yüzeyi.

Örnek ve doğrulama amaçlı modüller:

- `asl-sample`
- `asl-consumer-sample`
- `asl-coverage`

## Nereden Başlamalı?

- Proje özeti: [English](../README.md) | Turkish
- Kullanım rehberi: [English](../USAGE_GUIDE.md) | [Turkish](USAGE_GUIDE.md)
- Spring Boot sample rehberi: [English](../asl-consumer-sample/README.md) | [Turkish](asl-consumer-sample/README.md)
- Benchmark raporları: [English](../reports/README.md) | [Turkish](reports/README.md)

Kullanım rehberinde dependency ekleme, GitHub Packages ayarı, annotation processor kurulumu, runtime ayarları, tüm override edilebilir property’ler ve default davranışlar anlatılır.

## Dağıtım

- GitHub repository: [github.com/esasmer-dou/async-service-library](https://github.com/esasmer-dou/async-service-library)
- GitHub Packages: [maven.pkg.github.com/esasmer-dou/async-service-library](https://maven.pkg.github.com/esasmer-dou/async-service-library)
- Release sayfası: [github.com/esasmer-dou/async-service-library/releases](https://github.com/esasmer-dou/async-service-library/releases)

## Build

```powershell
mvn verify
```
