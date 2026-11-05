# Paketleri derlemek

*[English](build-packages.md) · Türkçe*

Tek bir derleme bütün paketleri Rocky Linux 9 üzerinde üretir (derleme imajı Rocky Linux 9.7):

| Paket | Hedefler | Klasör |
| --- | --- | --- |
| RPM `bfm-watcher`, `minipgagent` | Rocky / RHEL 8, 9, 10 | `target/packages/el8/`, `el9/`, `el10/` |
| DEB `bfm-watcher`, `minipgagent` | Ubuntu 22.04, 24.04, 26.04 | `target/packages/ubuntu22.04/`, `ubuntu24.04/`, `ubuntu26.04/` |

Paketler `noarch` / `all`'dır: kod Java 21 olduğu için tek derleme her mimariye yeter. Her paket dağıtımın Java 21
çalışma ortamına bağımlıdır (EL'de `java-21-headless`, Ubuntu'da `openjdk-21-jre-headless`). Başlatma betikleri,
sunucunun varsayılan `java`'sı daha eski bir sürüm olsa bile bu Java 21'i seçer; başka bir Java'yı
`/etc/bfm/watcher.env` ya da `/etc/bfm/minipgagent/agent.env` içinde `BFM_JAVA` ile verebilirsiniz. Kurulum
[install-rpm.tr.md](install-rpm.tr.md)'de anlatılıyor.

## Derleme imajı

`packaging/Containerfile.build`: Rocky Linux 9.7 üzerinde Java 21 (`java-21-openjdk-devel`), Maven 3.9
(`maven:3.9` modülü, `maven-openjdk21`), `rpm-build`, `git`, `tar`, `xz` ve `gzip`; hepsi Rocky'nin kendi
depolarından. Derleme yetki, systemd ya da konteyner içinde konteyner çalıştırıcısı istemez; bu yüzden Docker ya
da Podman'da olduğu gibi AWS Fargate görevi olarak da çalışır. `dpkg` de gerekmez: `.deb`, `debian-binary`,
`control.tar.xz` ve `data.tar.xz` dosyalarından oluşan bir `ar` arşividir ve `packaging/build.sh` onu `dpkg-deb`
gibi yazar.

```bash
docker build -f packaging/Containerfile.build -t bfm-build .
```

TLS'i inceleyen bir proxy arkasındaysanız, onun CA sertifikasını imaj derlemesine verin:

```bash
docker build --secret id=extra-ca,src=sirket-ca.crt -f packaging/Containerfile.build -t bfm-build .
```

Kaynak bağlanmış olarak derleme (paketler `target/packages/` altına düşer):

```bash
docker run --rm -v "$PWD:/src" bfm-build
```

**AWS Fargate'te:** imajı ECR'a gönderin ve ondan bir görev çalıştırın. Görev depoyu `/src` altına çeksin (ya da
bunu yapan bir CI sisteminin Fargate runner'ı), `packaging/build.sh`'yi çalıştırsın ve `target/packages/`'i
paketlerin gideceği yere (bir S3 bucket'ı, bir paket deposu) kopyalasın. Görevin Git sunucusuna ve Maven
Central'a (ya da sizin Maven aynanıza) dışarı erişimi olmalı; 4 GB bellek fazlasıyla yeter.

## Komutlar

Deponun kök dizininden, derleme imajında ya da aynı araçlara sahip herhangi bir Linux'ta:

Bütün paketler:

```bash
packaging/build.sh
```

Yalnızca RPM'ler ya da bir kısmı:

```bash
packaging/build.sh --deb none --rpm "el9 el10"
```

Yalnızca DEB'ler ya da bir kısmı:

```bash
packaging/build.sh --rpm none --deb "24.04 26.04"
```

Jar'lar zaten derlenmişse (`mvn package` daha önce çalıştıysa):

```bash
packaging/build.sh --skip-maven
```

Önce testler:

```bash
mvn -B verify && packaging/build.sh --skip-maven
```

`BFM_MAINTAINER="Ad <adres>"` paketleme iletişim bilginizi paketlere yazar (DEB maintainer ve changelog, RPM
packager); verilmezse paketlerde `lintian`'in uyardığı `BFM <bfm@localhost>` yazar. `--out KLASÖR` çıktıyı `target/packages` yerine başka bir yere yazar; her çalıştırma önce kendi çıktı klasörünü
boşaltır. Eski `packaging/rpm/build.sh [--dist "el8 el9"]` hâlâ çalışır ve yalnızca RPM'leri `target/rpm/`
altına üretir.

## Sonuç

```
target/packages/el9/bfm-watcher-0.1.0-0.1.202611050713git3aed7ff.el9.noarch.rpm
target/packages/el9/minipgagent-0.1.0-0.1.202611050713git3aed7ff.el9.noarch.rpm
target/packages/el9/bfm-0.1.0-0.1.202611050713git3aed7ff.el9.src.rpm
target/packages/ubuntu24.04/bfm-watcher_0.1.0~snap.202611050713.g3aed7ff-1~ubuntu24.04_all.deb
target/packages/ubuntu24.04/minipgagent_0.1.0~snap.202611050713.g3aed7ff-1~ubuntu24.04_all.deb
...
target/packages/SHA256SUMS
```

- **Sürümler.** `pom.xml`'deki sürüm `-SNAPSHOT` olduğu sürece derleme, UTC zamanı ve commit ile numaralanır
  (RPM'de `0.1.<zaman>git<commit>`, DEB'de `<sürüm>~snap.<zaman>.g<commit>-1~ubuntu<sürüm>`). Böylece her yeni
  derleme, aynı gün içinde bile, öncekini günceller; release sürümü (`-SNAPSHOT` olmadan) hepsini günceller.
- **EL8–EL10.** RPM'ler yalnızca `dist` etiketinde ayrılır; xz sıkıştırma ve SHA-256 özetlerini EL8'deki rpm 4.14
  de EL10'daki rpm 4.19 da okur.
- **Ubuntu.** DEB'ler sürüm ekinde ve systemd unit'lerinin yerinde ayrılır (22.04 ve 24.04'te
  `/lib/systemd/system`, 26.04'te `/usr/lib/systemd/system`). Ubuntu `arping`'i `/usr/bin`'de, RHEL
  `/usr/sbin`'de tutar: ajan ve sudo kuralı sunucuda hangisi varsa onu kullanır.
- **Dağıtmadan önce paketi kontrol edin:**

```bash
rpm -qpi target/packages/el9/bfm-watcher-*.el9.noarch.rpm
```

```bash
dpkg-deb --info target/packages/ubuntu24.04/minipgagent_*_all.deb
```

## Güncelleme, kaldırma ve yeniden kurulum

Paketler `/etc` altında hiçbir dosyanın sahibi değildir. Ayarlar eksikse `/usr/share/bfm/examples/` içindeki
örneklerden oluşturulur, sonra paketler onlara bir daha dokunmaz:

| | RPM (`dnf`) | DEB (`apt`) |
| --- | --- | --- |
| **Kurulum** | `/etc/bfm/watcher.yml`, `/etc/bfm/minipgagent/agent.properties` ve sudo kuralı `/etc/sudoers.d/minipgagent` örneklerden oluşturulur (kural yalnızca `visudo` kabul ederse); `bfm` ve `postgres` kullanıcıları | aynısı |
| **Güncelleme** | ayarlar olduğu gibi kalır; `.rpmnew` ya da `.rpmsave` yok, geride hiçbir şey kalmaz; servisler yeniden başlatılmaz (yeniden başlatma süren bir `pg_basebackup`, `pg_rewind` ya da switchover'ı keserdi), güncelleme bunu söyler | aynısı; `.dpkg-dist` ya da `.dpkg-old` yok, değişen ayarlar için soru da yok |
| **Kaldırma** | servisler durdurulur ve devre dışı bırakılır (PostgreSQL çalışmaya devam eder); `/etc/bfm` içindeki ayarlar, sudo kuralları ve `/var/lib` içindeki durum kalır | aynısı (`apt remove`) |
| **Yeniden kurulum** | önceki ayarları bulur, kullanır ve adlarıyla söyler; `bfm-watcher setup` onları değiştirmeden önce sorar, bir ajanı sıfırdan kurmak için `minipgagent remove <instance>` ve ardından `minipgagent setup` | önceki ayarların kullanılıp kullanılmayacağını **sorar** (debconf). Evet: korunur. Hayır: `/etc/bfm/backup/<paket>-<zaman>/` altına taşınır ve örnekler kurulur. Terminal yoksa (`DEBIAN_FRONTEND=noninteractive`) cevap evettir; Hayır'ı önceden vermek için `echo "bfm-watcher bfm-watcher/use-existing-config boolean false" \| debconf-set-selections` |
| **Purge** | — | `apt purge` ayarları, durumu (`secret.key` dahil) ve yedekleri siler |

`dnf install` soru soramaz: RPM betiklerinin terminali yoktur, terminal bekleyen bir kurulum da Ansible'ı ve diğer
otomasyonları durdururdu. Bu yüzden RPM'de soruyu kurulum sihirbazları sorar.

0.1.0-3'e kadarki paketler ayarların sahibiydi. Böyle bir paketten güncellerken, onun `.rpmsave`'e taşıyacağı
ayarlar yerine geri konur.

**`dnf remove minipgagent`, "would result in removing protected packages: sudo" diyerek reddediyorsa:** `sudo`
sunucuya yalnızca ajanın bağımlılığı olarak gelmiştir; dnf onu ajanla birlikte kaldırmak ister, RHEL ise korur.
`sudo`'yu bırakmak için:

```bash
dnf remove --noautoremove minipgagent
```

## GitLab CI'da

`.gitlab-ci.yml`, `main`'e her push'ta, merge request'lerde ve tag'lerde testleri çalıştırır ve bütün paketleri
derler. Derleme, derleme imajındaki paketlerle Rocky Linux 9.7 imajında yapılır. Paketler `packages` işinin
artifact'larıdır (Build → Pipelines → pipeline → `packages` → Download artifacts) ve 30 gün saklanır; tag'li
derlemeler kalıcıdır. Projenin `rockylinux/rockylinux:9.7` imajını çekebilen, Docker executor'lı bir GitLab
Runner'ı olmalı; çekemiyorsa `CI_BUILD_IMAGE` değişkenini kendi registry'nizdeki bir kopyaya yöneltin. Gerçek
PostgreSQL ile çalışan entegrasyon testleri (`AgentPostgresIT`, `WatcherPostgresIT`) CI'da atlanır: PostgreSQL
programlarına ihtiyaç duyarlar (`BFM_IT_PGBIN`).

## Ubuntu'da henüz olmayan

Paketler Ubuntu'da temiz kurulur, güncellenir ve kaldırılır; watcher orada çalışır. Ancak ajan PostgreSQL'i
`pg_ctl -D <veri dizini>` ile başlatır ve `postgresql.conf`'u o dizinde bekler; RHEL'deki PGDG paketleri böyle
yerleştirir (Ubuntu'da da PostgreSQL bu şekilde kurulduysa böyledir). Ubuntu'nun kendi PostgreSQL paketleri ise
ayarları `/etc/postgresql/<sürüm>/<küme>/` altında tutar ve bütün kümeleri `postgresql.service` ile başlatır;
`minipgagent setup` böyle bir kümeyi henüz devralamıyor. Bu gelene kadar Ubuntu sunucularındaki ajanları yalnızca
ayarları veri dizininde duran kümelerde çalıştırın.
