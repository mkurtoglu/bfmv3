# RPM paketlerini derlemek

*[English](build-rpm.md) · Türkçe*

`packaging/rpm/build.sh`, iki paketi (`bfm-watcher` ve `minipgagent`) Rocky Linux / RHEL 8 ve 9 için üretir.
Paketler `noarch` (Java) olduğu için tek bir derleme makinesi hem `el8` hem `el9` paketlerini üretir; herhangi bir
Linux olabilir. Kurulumu [install-rpm.tr.md](install-rpm.tr.md) anlatır.

## Derleme makinesinde gerekenler

| Araç | Sürüm | Rocky / RHEL 9 | Debian / Ubuntu |
| --- | --- | --- | --- |
| Java (JDK) | 21 | `dnf install java-21-openjdk-devel` | `apt install openjdk-21-jdk-headless` |
| Maven | 3.9 | [apache-maven-3.9.x-bin.tar.gz](https://maven.apache.org/download.cgi), açılıp `PATH`'e eklenir | aynısı |
| rpmbuild | herhangi | `dnf install rpm-build` | `apt install rpm` |
| git | herhangi | `dnf install git` | `apt install git` |

Maven ilk derlemede bağımlılıkları Maven Central'dan indirir (`~/.m2` altında yaklaşık 300 MB). Proxy
arkasındaysanız `~/.m2/settings.xml` içinde tanımlayın.

## Komutlar

Deponun kök dizininden:

```bash
git clone https://gitlab.bisoft.com.tr/bfm/bfmv3.git && cd bfmv3
```

İki dağıtım için birden:

```bash
packaging/rpm/build.sh
```

Yalnızca EL9:

```bash
packaging/rpm/build.sh --dist el9
```

Yalnızca EL8:

```bash
packaging/rpm/build.sh --dist el8
```

Her çalıştırma önce `target/rpm`'yi temizler: ikisini birden saklamak için tek seferde derleyin (ilk komut).

Jar'lar zaten derlenmişse (`mvn package` daha önce çalıştıysa) Maven adımını atlamak için:

```bash
packaging/rpm/build.sh --skip-maven
```

Betik `mvn -DskipTests package` ile derler. Önce testleri çalıştırmak için:

```bash
mvn -B verify && packaging/rpm/build.sh --skip-maven
```

## Sonuç

```
target/rpm/el8/bfm-watcher-0.1.0-0.1.202611030915git767f1d4.el8.noarch.rpm
target/rpm/el8/minipgagent-0.1.0-0.1.202611030915git767f1d4.el8.noarch.rpm
target/rpm/el8/bfm-0.1.0-0.1.202611030915git767f1d4.el8.src.rpm
target/rpm/el9/bfm-watcher-0.1.0-0.1.202611030915git767f1d4.el9.noarch.rpm
target/rpm/el9/minipgagent-0.1.0-0.1.202611030915git767f1d4.el9.noarch.rpm
target/rpm/el9/bfm-0.1.0-0.1.202611030915git767f1d4.el9.src.rpm
```

- **Sürüm ve release.** `pom.xml`'deki sürüm `-SNAPSHOT` olduğu sürece release
  `0.1.<UTC tarih ve saat>git<commit>` olur; böylece sonraki her derleme, aynı gün içinde bile, öncekini
  `dnf upgrade` ile günceller. Release sürümü (`-SNAPSHOT` olmadan) release `1` alır.
- **EL8 ve EL9.** Paketler yalnızca `dist` etiketinde ayrılır; ikisi de EL8'deki rpm 4.14'ün okuyabildiği xz ve
  SHA-256 kullanır.
- **Dağıtmadan önce paketi kontrol edin:**

```bash
rpm -qpi target/rpm/el9/bfm-watcher-*.el9.noarch.rpm
```

```bash
rpm -qpl target/rpm/el9/minipgagent-*.el9.noarch.rpm
```

## GitLab CI'da

`.gitlab-ci.yml`, `main`'e her push'ta, merge request'lerde ve tag'lerde testleri çalıştırır ve paketleri derler.
RPM'ler `rpm` işinin artifact'larıdır (Build → Pipelines → pipeline → `rpm` → Download artifacts) ve 30 gün
saklanır; tag'li derlemeler kalıcıdır. İşler `maven:3.9.9-eclipse-temurin-21` imajında çalışır (Ubuntu üzerinde
Java 21 ve Maven 3.9; `rpm` iş içinde kurulur). Bu yüzden projenin bu imajı çekebilen, Docker executor'lı bir
GitLab Runner'ı olmalı; çekemiyorsa `CI_BUILD_IMAGE` değişkenini kendi registry'nizdeki bir kopyaya yöneltin.

Maven indirme önbelleği pipeline'lar arasında saklanır (`.m2/repository`). Gerçek PostgreSQL ile çalışan entegrasyon
testleri (`AgentPostgresIT`, `WatcherPostgresIT`) CI'da atlanır: PostgreSQL programlarına ihtiyaç duyarlar
(`BFM_IT_PGBIN`).
