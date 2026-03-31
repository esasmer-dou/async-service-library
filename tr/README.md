# Async Service Library

Async Service Library (ASL), servis metotlarini derleme zamaninda yoneten ve Spring Boot control plane uzerinden runtime'da isletebilen bir Java kutuphanesidir.

Bu kutuphane, yalnizca `@Async` seviyesinden daha fazla kontrol isteyen ama her isi bastan harici bir broker platformuna tasimak istemeyen ekipler icin tasarlandi.

## Ne Saglar

- reflection agirlikli dispatch yerine derleme zamaninda uretilen wrapper'lar
- runtime'da metot durdurma/baslatma ve concurrency limiti
- `void` metotlar icin queue tabanli async calisma
- replay, delete, clear ve consumer-thread kontrolu
- `/asl` altinda Spring Boot admin arayuzu
- `/asl/api` altinda REST control plane

## Ne Zaman Uygun

ASL su durumlarda iyi oturur:

- belirli servis metotlarini redeploy etmeden yavaslatmak veya durdurmak istiyorsan
- secili `void` metotlari yonetilebilir async lane'e cevirmek istiyorsan
- queue depth, failed entry ve pressure bilgisini tek panelden gormek istiyorsan
- async davranisi uygulama siniri icinde tutmak istiyorsan

## Mevcut Sinirlar

- runtime async gecisi sadece `void` metotlar icin desteklenir
- governed interface basina tek Spring stereotype implementasyonu beklenir
- admin security kutuphane tarafinda otomatik kurulmaz

## Hizli Ornek

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

## Moduller

- `asl-annotations`
- `asl-core`
- `asl-processor`
- `asl-mapdb`
- `asl-spring-boot-starter`

Referans ve dogrulama icin sample moduller de vardir:

- `asl-sample`
- `asl-consumer-sample`
- `asl-coverage`

## Buradan Basla

- kullanim rehberi: [USAGE_GUIDE.md](E:\ReactorRepository\async-service-library\tr\USAGE_GUIDE.md)
- Spring Boot sample: [asl-consumer-sample/README.md](E:\ReactorRepository\async-service-library\tr\asl-consumer-sample\README.md)
- Ingilizce README: [README.md](E:\ReactorRepository\async-service-library\README.md)

## Public Dagitim

- GitHub repo: [github.com/esasmer-dou/async-service-library](https://github.com/esasmer-dou/async-service-library)
- GitHub Packages: [maven.pkg.github.com/esasmer-dou/async-service-library](https://maven.pkg.github.com/esasmer-dou/async-service-library)
- Release sayfasi: [github.com/esasmer-dou/async-service-library/releases](https://github.com/esasmer-dou/async-service-library/releases)

## Build

```powershell
mvn verify
```
