# Async Service Library

**Dil:** [English](../README.md) | Türkçe

Async Service Library (ASL), Java 21 ve Spring Boot uygulamalarındaki servis metotları için süreç içi bir yönetim katmanıdır. Servis sarmalayıcılarını derleme sırasında üretir; seçilen metotların reflection tabanlı çağrı yönlendirmesi veya ayrı bir iş akışı ürünü olmadan durdurulmasını, sınırlandırılmasını, kuyruğa alınmasını, yeniden denenmesini ve izlenmesini sağlar.

Tasarımın temel ilkesi sınırsız kaynak kullanmamaktır. Async kabulü, payload boyutu, writer kuyruğu, worker havuzu, retry kayıtları, idempotency saklama süresi ve admin erişimi açık sınırlar veya güvenli varsayılanlarla yönetilir.

## Sağladığı Yetenekler

- Derleme sırasında üretilen servis sarmalayıcıları.
- Metot bazında başlatma, durdurma ve eşzamanlılık sınırı.
- Yalnızca açıkça işaretlenmiş `void` metotlar için kalıcı async çalışma.
- FIFO indeksleri, lease, retry, idempotency ve kalıcı onay içeren sınırlı MapDB kuyruğu.
- Cardinality sınırı ayarlanabilen Micrometer metrikleri.
- İsteğe bağlı merkezi admin server: tüm uygulama ve replica'lar için tek, hafif portal; pod'larda yönetim ucu yoktur.
- JMH, API uyumluluğu, SBOM, JVM/işletim sistemi matrisi ve yıpratıcı recovery testleri.

## Yalnızca İhtiyacın Olan Modülü Seç

| İhtiyaç | Artifact | MapDB getirir mi? | Web/Thymeleaf getirir mi? |
|---|---|---:|---:|
| Yalnız senkron metot yönetimi | `asl-spring-boot-core-starter` | Hayır | Hayır |
| Kalıcı async kuyruk | `asl-spring-boot-mapdb-starter` | Evet | Hayır |
| Uygulamayı merkezi admin server'a bağlama | `asl-spring-boot-admin-client-starter` | Hayır | Hayır |
| Tüm uygulama ve replica'lar için merkezi portal (ayrı jar, web framework'süz) | `asl-admin-server` | Evet (telemetri geçmişi) | Hayır |
| Hepsi bir arada kurulum (core + MapDB + admin client) | `asl-spring-boot-starter` | Evet | Hayır |

Annotation processor yalnızca derleme sırasında kullanılır. Sadece senkron yönetim isteyen bir uygulama MapDB taşımaz. Hiçbir ASL artifact'i uygulamaya Spring MVC veya Thymeleaf eklemez; admin client `asl.admin.client.enabled=true` yapılana kadar kapalıdır.

Uygulama içi admin portalı (`asl-spring-boot-admin-starter`, `/asl` ve `/asl/api`) kaldırıldı. Yerine merkezi admin server kullanılır; başlangıç override'ları `asl.runtime.services.*` altına taşındı.

## En Küçük Kurulum

```xml
<properties>
    <asl.version>YAYINLANAN_SURUM</asl.version>
</properties>

<dependency>
    <groupId>com.reactor.asl</groupId>
    <artifactId>asl-spring-boot-core-starter</artifactId>
    <version>${asl.version}</version>
</dependency>
<dependency>
    <groupId>com.reactor.asl</groupId>
    <artifactId>asl-annotations</artifactId>
    <version>${asl.version}</version>
</dependency>
```

`asl-processor` artifact’ını `maven-compiler-plugin.annotationProcessorPaths` altında tanımladıktan sonra servis sözleşmesini işaretle:

```java
@GovernedService(id = "mail.service")
public interface MailService {
    @GovernedMethod(initialMaxConcurrency = 4)
    String send(String payload);

    @GovernedMethod(asyncCapable = true, initialConsumerThreads = 2)
    void publish(@AsyncIdempotencyKey String eventId, String payload);
}
```

Yanıt döndüren metotlar senkron kalır; bu metotları kuyruğa almak çağrının dönüş sözleşmesini bozar. Runtime sırasında async moda yalnızca `asyncCapable=true` olarak tanımlanmış `void` metotlar geçirilebilir.

## Production Varsayılanları

- Admin client başlangıçta kapalıdır. Açıldığında en az 16 baytlık ortak bir HMAC anahtarı ister ve pod'da port açmaz.
- Admin server portalı varsayılan olarak giriş ister (`BASIC`); TLS sonlandırmaz, TLS'li bir ingress arkasında çalışmalıdır.
- MapDB başlangıçta kapalıdır; bozulma politikası `FAIL_FAST` değerindedir.
- Kuyruk ve payload sınırı aşıldığında varsayılan davranış belleği büyütmek değil, işi reddetmektir.
- MapDB transaction desteği açıktır. Kapatılması rollback ve çökme dayanıklılığını zayıflatır ve log’a uyarı yazar.
- Payload özeti ile senkron/async hata ayrıntısı varsayılan olarak yalnızca tip bilgisini gösterir ve kesin uzunluk sınırları içinde tutulur.
- Özel Jackson/Java tipleri allowlist’e eklenmeli veya açık bir şema ile kaydedilmelidir.
- Varsayılan retry değeri toplam bir denemedir. Retry sayısı artırılıyorsa iş mantığı idempotent olmalıdır.

## Dağıtım Sınırı

MapDB motoru tek JVM’e aittir. Uygulama tek replica ve local/PVC tabanlı kuyrukla çalıştırılabilir ya da her replica kendi bağımsız kuyruğunu kullanabilir. Aynı MapDB dosyası birden fazla pod tarafından kesinlikle paylaşılmamalıdır. Tek sahiplik `.asl.lock` dosyası işletim sistemi file lock'u kullanır; ağ dosya sistemleri (NFS, SMB, EFS veya Azure Files gibi ReadWriteMany volume'lar) bu kilidi güvenilir biçimde uygulamayabilir. Her replica için ReadWriteOnce volume kullan. ASL küme genelinde teslimat sağlamaz ve broker adapter'ı içermez; replica'lar arasında paylaşılması gereken iş bir mesaj kuyruğu sistemine aittir.

Birden fazla replica'yı tek noktadan yönetmek için merkezi admin server kullanılır: her replica `asl-spring-boot-admin-client-starter` ile çalışır (pod'da arayüz ve dışarıdan erişilen endpoint yoktur) ve tek bağlantıyı kendisi açar. Ayrıntı: [Merkezi admin server](USAGE_GUIDE.md#merkezi-admin-server-ve-birden-fazla-replica).

Async teslimat at-least-once biçimindedir. Graceful shutdown, crash veya süresi dolan lease ile kesilen bir çalıştırma retry denemesi tüketmeden kuyruğa geri döner (`retry.max-interruptions` ile sınırlı). JVM, iş yan etkisi tamamlandıktan sonra fakat kalıcı tamamlanma kaydı yazılmadan önce kapanabilir; bu nedenle consumer iş mantığı idempotent olmalıdır. `@AsyncIdempotencyKey` aynı işin kuyruğa birden fazla kez kabul edilmesini önler, ancak dış sistemdeki yan etkiyi transaction hâline getirmez.

## Dokümanlar

- Kullanım rehberi: [English](../USAGE_GUIDE.md) | [Türkçe](USAGE_GUIDE.md)
- Tüm ayarların açıklaması: [English](../CONFIGURATION_REFERENCE.md) | [Türkçe](CONFIGURATION_REFERENCE.md)
- Çalıştırılabilir örnek: [English](../asl-consumer-sample/README.md) | [Türkçe](asl-consumer-sample/README.md)
- Recovery ve yıpratma testleri: [English](../asl-consumer-sample/CHAOS_TEST_GUIDE.md) | [Türkçe](asl-consumer-sample/CHAOS_TEST_GUIDE.md)
- Benchmark raporları: [English](../reports/README.md) | [Türkçe](reports/README.md)

## Doğrulama

```powershell
mvn -B -ntp clean verify
.\scripts\run-api-compatibility-gate.ps1
.\scripts\run-data-plane-performance-gate.ps1 -Threads 1,8,32,128
mvn -B -ntp -Psbom -DskipTests package
```

Release akışı source/Javadoc artifact’larının yanında CycloneDX JSON/XML SBOM dosyalarını da üretir. CI matrisi Windows ve Linux üzerinde Temurin ile Semeru/OpenJ9 JVM’lerini doğrular.

## Dağıtım Kanalları

- [GitHub deposu](https://github.com/esasmer-dou/async-service-library)
- [GitHub Packages](https://maven.pkg.github.com/esasmer-dou/async-service-library)
- [Yayınlar](https://github.com/esasmer-dou/async-service-library/releases)

GitHub Packages kimlik doğrulaması ve eksiksiz Maven kurulumu [kullanım rehberinde](USAGE_GUIDE.md) bulunur.
