# Raporlar

Üretilen raporlar repoya commit edilmez; yerelde yazılır ya da CI artifact'i olarak yüklenir.

## Kurtarma stres testi (MapDB abuse)

- Çıktı kökü: `reports/mapdb-abuse/<zaman>/` (Türkçe özet `tr/reports/mapdb-abuse/` altında)
- Çalıştırıcı: [run-mapdb-abuse-suite.ps1](../../scripts/run-mapdb-abuse-suite.ps1) (PowerShell 7)
- Gece CI: [mapdb-abuse-nightly.yml](../../.github/workflows/mapdb-abuse-nightly.yml)

Test, merkezi `asl-admin-server`'ı ve admin client'ı açık consumer sample'ı başlatır. Her senaryo sample'ı
sert biçimde öldürür, yeniden başlatır ve kuyruktaki işin kaybolmadığını kontrol eder. Mod ve consumer
değişiklikleri, kuyruk listeleme ve replay, bir operatörün kullandığı yoldan, admin server üzerinden yapılır.

| Senaryo | Neyi kanıtlar |
| --- | --- |
| `pending-survives-force-kill` | Bekleyen iş sert kapatmadan sonra kalır ve yeniden başlatınca çalışır |
| `failed-queue-replay-after-force-kill` | Başarısız kayıt yeniden başlatmadan sonra kalır ve replay edilebilir |
| `in-progress-recovery-after-force-kill` | Çalışırken kesilen iş korunur ve yeniden başlatınca teslim edilir |
| `repeated-pending-force-kill-loop` | Üç ardışık kapatmada bekleyen iş doğru birikir |
| `header-corruption-startup-recovery` | Bozuk depo açılışta arşivlenir ve yeniden oluşturulur |

Her çalıştırma `mapdb-abuse-suite-summary.json` ve `mapdb-abuse-suite-summary.md` üretir; her sample
başlatması ve admin server için birer log çifti yazar.

## Veri yolu performansı

[data-plane-performance.yml](../../.github/workflows/data-plane-performance.yml) JMH çıktılarını
`asl-benchmarks/target/performance-gate` altından yükler.
