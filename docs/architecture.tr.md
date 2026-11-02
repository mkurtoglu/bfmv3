# BFM mimarisi

*[English](architecture.md) · Türkçe*

BFM'in nasıl kurulduğu ve neden böyle kurulduğu: parçalar, her birinin ne bildiği, primary'nin kim olduğu konusunda
nasıl anlaştıkları ve hangi mekanizmanın hangi arızayı önlediği. Kurulum için [install-rpm.tr.md](install-rpm.tr.md),
tüm ayarlar için [agent-config.md](agent-config.md) ve [install.md](install.md), konsol için
[user-guide.md](user-guide.md), sunucu başına birden çok kümenin ardındaki kararlar için
[design/multi-cluster.md](design/multi-cluster.md) (bunlar İngilizcedir).

---

## 1. BFM ne yapar, ne yapmaz

BFM, primary'si çöken bir PostgreSQL streaming replikasyon kümesini yazılabilir tutar ve sanal IP'yi (VIP) hangi
node primary ise ona taşır; böylece istemciler tek bir adres kullanır. Ayrıca planlı rol değişikliklerini
(switchover) yürütür, çöken node'ları replica olarak geri getirir (`pg_rewind`, `pg_basebackup`), uyarı ve e-posta
üretir ve bunların hepsini bir web konsolunda ve bir REST API'de gösterir.

Bağlantıları proxy'lemez veya havuzlamaz, yedek almaz, mantıksal replikasyonu yönetmez. Veriyi PostgreSQL'in kendi
streaming replikasyonu taşır; BFM rollere karar verir ve VIP'i taşır.

## 2. Parçalar

```
                     operatörler, betikler
                           │ HTTPS 8443 (konsol, REST API)
              ┌────────────┴────────────┐
              │  bfm-watcher  (1 veya 3)│◄──── Raft 9870 ────► diğer watcher'lar
              │  kararlar, konsol,      │      (paylaşılan durum, lider seçimi)
              │  API, e-posta, CA       │
              └──┬──────────┬────────┬──┘
   HTTPS 8008,   │          │        │      2 sn'de bir sorgu, lease, komutlar
   karşılıklı TLS▼          ▼        ▼
          ┌──────────┐ ┌──────────┐ ┌──────────┐
          │ agent    │ │ agent    │ │ agent    │   minipgagent@<instance>, postgres kullanıcısı
          │ pg-a1    │ │ pg-a2    │ │ pg-dr1   │   her PostgreSQL instance'ı için bir tane
          ├──────────┤ ├──────────┤ ├──────────┤
          │PostgreSQL│►│PostgreSQL│ │PostgreSQL│   streaming replikasyon 5432
          │ primary  │ │ replica  │ │ replica  │
          └────┬─────┘ └──────────┘ └──────────┘
               │ VIP (sudo ip addr add, GARP)
           istemciler
```

| Parça | Nerede çalışır | Ne bilir | Neye karar verir |
| --- | --- | --- | --- |
| **MiniPG agent** (`minipgagent`, `bfm-agent` modülü) | her PostgreSQL instance'ının yanında, `postgres` olarak | kendi PostgreSQL'i (Unix soketi üzerinden `psql` ile), VIP'i, epoch'u, lease'i, fence bayrağı ve küme bağı | küme hakkında hiçbir şeye; komutları yürütür ve yerel kuralları uygular (VIP'siz promote yok, eski epoch'ları ve başka kümeleri reddet, lease dolunca kendini fence et) |
| **Watcher** (`bfm-watcher`; `bfm-core`, `bfm-raft`, `bfm-watcher` modülleri) | kendi sunucusunda (sunucularında), `bfm` olarak | her node'un son durumu, paylaşılan küme durumu, diğer watcher'ların gözlemleri | failover, onarımlar, uyarılar; operatör komutlarını yürütür |
| **Paylaşılan durum** (`LocalStateStore` veya `RaftStateStore`) | watcher(lar)ın içinde | küme başına: epoch, primary, anahtarlar, çalışan işlem, son rol değişikliği, ayarlar, olay günlüğü | bir compare-and-set tüm grup için bir kez ya kazanır ya kaybeder |
| **Konsol ve REST API** | watcher'ın içinde | durumun ve node'ların görünümleri | hiçbir şeye; motordan ister, lider olmayan bir watcher'da motor reddeder |

Watcher asla PostgreSQL'e oturum açmaz ve bir veritabanı sunucusunda kendi başına komut çalıştırmaz: her şey bir
agent'ın API'si üzerinden gider. Böylece bir agent'ın uyguladığı her kural, kim isterse istesin geçerlidir.

## 3. Watcher

```
 bfm-watcher (Spring Boot)
 ├── ClusterHub ─────────────── küme başına bir motor
 │    └── Watcher (bfm-core)    sorgu döngüsü, kararlar, işlemler, uyarılar
 │         ├── Poller           her agent'ta paralel GET /v1/status
 │         ├── rol yürütücüsü   failover / switchover / primary olarak başlat — aynı anda bir tane
 │         ├── node yürütücüsü  yeniden katma / yeniden oluşturma — node başına, rol değişikliğinin yanında
 │         └── ScopedStateStore kümenin paylaşılan durumdaki payı ve olay günlüğü
 ├── StateStore                 LocalStateStore (tek watcher) veya RaftStateStore (Apache Ratis)
 ├── NotificationService        e-posta kuralları, kısma, SMTP (yalnızca lider gönderir)
 ├── EnrollmentService, WatcherCa, CaBootstrap   agent'ların CA'sı ve tek kullanımlık token'lar
 ├── ConsoleService, FleetService                konsol ve API için görünümler
 ├── ui/ (Vaadin 25)            sayfalar, sihirbazlar, diller (i18n/)
 └── api/ (Spring MVC)          REST API, HTTP Basic, JSON hatalar
```

**Küme başına bir motor.** `ClusterHub`, `watcher.yml`'deki (`bfm.clusters`) ve konsolda oluşturulan (paylaşılan
durumun kayıt defterinde tutulan) her küme için bir `Watcher` kurar. Her motorun kendi sorgu döngüsü, iş
parçacıkları, işlemleri, uyarıları ve olay günlüğü vardır; bir kümedeki failover başka bir kümeyi asla beklemez.
Agent'ların TLS depolarıyla kurulan tek bir HTTP istemcisi hepsine hizmet eder.

**Sorgu döngüsü** her `poll-interval`'da (2 sn) çalışır. Lider olsun olmasın her watcher'da:

1. Her agent'a `/v1/status` sorulur (rol, timeline, LSN'ler, replikasyon, slot'lar, VIP, lease, fence, system
   identifier, kimlik). `agent-timeout` içinde yanıt vermeyen agent ulaşılamaz sayılır.
2. Kimlik kontrol edilir: agent bu kümeye bağlı mı, sertifikası bu node'un adını taşıyor mu, veritabanı kümenin
   system identifier'ına sahip mi. Kontrolü geçemeyen node *Yanlış kimlik* olur ve bundan sonra ona dokunulmaz.
3. Bu watcher'ın gözlemi (primary'den ne gördüğü) paylaşılan duruma yazılır; Raft modunda lider bunları çoğunluk
   kontrolü için sayar.
4. Uyarılar: gecikme, pasif slot'lar, düşen veya geri gelen node'lar, kaybolan watcher'lar; kurallara göre e-posta
   (yalnızca lider gönderir).

Ardından yalnızca **lider** watcher'da:

5. Primary'nin lease'i yenilenir (Raft modunda önce liderlik çoğunlukla doğrulanır).
6. Primary sınıflandırılır: `OK`, `AGENT_UNREACHABLE`, `PG_DOWN`, `FENCED` veya `NOT_PRIMARY`. Art arda
   `failure-threshold` (3) kötü sorgudan sonra kayıp sayılır.
7. Bu sırayla harekete geçilir: agent'ı yanıt veren ama PostgreSQL'i çökmüş bir primary bir kez yerinde yeniden
   başlatılır (`restart-primary-first`); değilse her kapı açıksa otomatik failover başlatılır (bölüm 6); durumda
   başka bir node yazılıyken primary olarak çalışan node fence edilir; primary olmayan bir node'daki VIP kaldırılır;
   durmuş, streaming yapmayan veya eski bir timeline'daki replica'lar yeniden yönlendirilir ya da yeniden katılır
   (otomatik katma `pg_rewind` ile; otomatik yeniden oluşturma açıksa `pg_basebackup` ile).

**İşlemler**, adım planı olan nesnelerdir (`OperationProgress`); konsolda canlı gösterilir ve geçmişte tutulur. Rol
değişiklikleri çalışırken küme durumunu tutar (durumdaki `operation`); geri alınamayan her adımdan önce bu
watcher'ın hâlâ lider olduğunu ve kümeyi hâlâ tuttuğunu kontrol eder. Watcher arada ölürse, sonraki lider artık
hiçbir iş parçacığının çalıştırmadığı bir işlem görür, kümeyi yeni bir epoch'la serbest bırakır ve bunu günlüğe
yazar.

## 4. Agent

```
 minipgagent@<instance>  (yalnızca JDK, ~200 KB)
 ├── AgentHttpServer    HTTPS, istemci sertifikası zorunlu, tls.allowed.clients, küme bağı, epoch kontrolü
 ├── AgentService       döngüler ve komutlar
 │    ├── probe         her 1 sn: PgProbe (tek psql ifadesi) + durmuşken pg_controldata
 │    ├── lease tick    her 0,5 sn: fence eden lease dolduysa fence et
 │    ├── watchdog      isteğe bağlı /dev/watchdog, primary iken kurulu
 │    └── DAD öz-sınaması her 60 sn, kendi iş parçacığında (vip.dad.probe)
 ├── pg/   PgProbe, PgController (pg_ctl start/stop/promote, pg_rewind, pg_basebackup), Psql
 ├── vip/  VipManager: sudo ip addr add/del, arping -D (çakışma kontrolü), arping -U (GARP)
 ├── state/ EpochStore, FenceState, LeaseGuard (kalıcı), ClusterBinding, InterruptedOperation,
 │          ExclusiveLock (durum dizini başına bir agent), Watchdog
 └── setup/ minipgagent setup / remove: keşif, kayıt, hazırlık kontrolleri, sudo kuralı, systemd
```

Agent bilerek küçük tutulur ve JDK dışında bağımlılığı yoktur. Durum dizini yeniden başlatmalardan sonra da
kalır: gördüğü en yüksek epoch, fence edilip edilmediği, duvar saati bitiş zamanıyla lease (ağdan kopukken yeniden
başlatılan bir agent da zamanında fence eder), ait olduğu küme ve yarıda kalmış bir `pg_rewind` veya
`pg_basebackup` işareti (böyle bir veri dizini, yeniden oluşturulana kadar asla başlatılmaz).

Her harici komut bir şablondur (`pg.start.command`, `vip.add.command`, …); yer tutucular doldurulmadan önce
boşluklardan bölünür, hiçbir kabuk kullanılmaz. Sudo kuralı yalnızca tek bir arayüzdeki tek bir adres için VIP
komutlarına ve tek bir komşuya karşı öz-sınamaya izin verir.

## 5. Paylaşılan durum ve kimin hareket ettiği

Paylaşılan durum küme başına küçük bir belge tutar — `epoch`, `primary`, anahtarlar (`autoFailover`,
`maintenance`, `mailEnabled`, `autoReinit`), çalışan `operation`, kimin ne zaman değiştirdiği — ayrıca son rol
değişikliğini, e-posta ayarlarını, her watcher'ın gözlemlerini ve olay günlüğünü.

**Her değişiklik** bu belgenin sürümü üzerinde bir **compare-and-set**'tir. Aynı anda failover yapmaya çalışan iki
watcher ikisi birden kazanamaz: ikincisinin yazması başarısız olur ve durur. Tek watcher'da depo yerel bir dosyadır
(`LocalStateStore`); üç watcher'da bir Apache Ratis grubudur (`RaftStateStore`): yazmalar Raft günlüğünden geçer,
bu yüzden çoğunluğu olmayan bir watcher hiçbir şeyi değiştiremez; okumalar lider üzerinden doğrusallaştırılır
(linearizable).

**Hareket eden watcher** Raft lideridir (ya da tek watcher). Takipçiler sorgular, gördüklerini yayınlar ve konsolu
lidere bağlantıyla salt okunur gösterir; takipçideki operatör komutları liderin adıyla reddedilir.

**Epoch'lar.** Her rol değişikliği önce küme epoch'unu artırır (compare-and-set içinde) ve bunu ulaşabildiği her
agent'a duyurur. Agent'lar gördükleri en yüksek epoch'u hatırlar ve daha eski epoch'lardan gelen komutları —
fence dahil — reddeder; böylece bir failover'ın ortasında liderliği kaybeden watcher artık bir node'u
yönlendiremez veya durduramaz. Durumdan daha yeni bir epoch bildiren agent, kümeyi başka bir watcher'ın
yönlendirdiği anlamına gelir; o zaman otomatik eylemler bir operatör harekete geçene kadar duraklatılır.

**Lease.** Lider watcher primary'ye her sorguda bir lease (`lease-ttl`, 30 sn) verir. Self-fencing açıkken bu,
fence eden bir lease'tir: yenileme almayan primary süre dolunca VIP'ini bırakır ve PostgreSQL'i durdurur; bir
failover da promote etmeden önce eski primary'nin hâlâ tutabileceği her lease'in dolmasını bekler (TTL + pay). Raft
modunda lider her yenilemeden önce liderliğini çoğunlukla doğrular; böylece diğerlerinden kopmuş bir lider bir
primary'yi canlı tutamaz.

## 6. Otomatik failover

Sırasıyla kapılar. Kapalı herhangi bir kapı, failover olmaması, nedenini söyleyen bir olay ve bir sonraki sorguda
yeniden bakılması demektir.

| Kapı | Ne zaman geçer |
| --- | --- |
| Primary kayıp | art arda `failure-threshold` sorguda primary `OK` değil; agent'ı yanıt veriyorsa önce yerinde yeniden başlatma denendi |
| Silahlı | otomatik failover açık (*Otomatik failover* kolonunda en az bir düğüm işaretli), bakım modu kapalı, çalışan işlem yok, başarısız bir denemeden sonraki bekleme bitti |
| Replica'lar hemfikir | ulaşılabilen her replica'ya (`/v1/peer-check`) primary'nin PostgreSQL portuna hâlâ ulaşıp ulaşmadığı ya da ondan streaming yapıp yapmadığı sorulur; tek bir "evet" engeller |
| Watcher'lar hemfikir (Raft) | watcher'ların çoğunluğu primary'nin gittiğini `observation-fresh` içinde gördü |
| Aday var | agent'ı yanıt veren, recovery'de olan, fence edilmemiş veya meşgul olmayan, primary'nin son timeline'ında olan, konsolun *Otomatik failover* kolonunda izin verilmiş (orada seçim yapılmadıysa: `no-failover` olmayan), eski primary'nin veri merkezinde olan (kapsam `DC_LOCAL`) ve primary'de görülen en yeni WAL konumunun en fazla `max-lag` gerisinde olan bir replica; alınan WAL'e, sonra önceliğe, sonra ada göre en iyisi |
| Katı replikasyon | yalnız katı (strict) senkron replikasyonda: en az `standbys - n + 1` senkron replica'nın WAL konumu biliniyor (bir commit'in beklediği her `n`'li standby kümesinde biri var) ve aday bunların en yenisine sahip. Agent'ı yanıt veren ama PostgreSQL'i durmuş bir senkron replica, konumu bilinsin diye replica olarak başlatılır (asla primary olarak değil); kapıya bir sonraki sorguda yeniden bakılır |

Ardından `FailoverOperation`:

```
 doğrula ─► seç ─► epoch ─► eskiyi fence et ─► lease bekle ─► VIP aç ─► promote ─► kaydet ─► replica'ları yönlendir
           (kapılar  (CAS +   (agent'ı          (self-fence:  (arping -D,  (yalnızca  (durum:   (follow, slot'lar)
            yeniden)  duyuru)  yanıt veriyorsa)  TTL+pay)      ip addr add, VIP burada) primary,
                                                              GARP)                   epoch)
```

VIP aday node'da açılamazsa hiçbir şey promote edilmez: failover başarısız olur, küme serbest bırakılır, o node
bir süre tercih edilmez ve bir sonraki deneme `failover-retry` kadar bekler. Promote net bir yanıt olmadan biterse
watcher node'un neye dönüştüğünü görmeyi bekler; bu bilinmezken VIP'i asla başka yere taşımaz veya eski primary'yi
yeniden başlatmaz. Başarılı bir failover'dan sonra e-posta ve konsolun rol değişikliği kartı veri kaybı
kontrolünü bildirir: yeni primary eski primary'de görülen son WAL konumunu almış mıydı, yoksa ne kadar gerideydi.

Eski primary geri döndüğünde, daha yeni bir node zaten kayıtlıyken primary olarak çalışır: fence edilir (VIP
bırakılır, PostgreSQL durdurulur) ve yeni primary'nin replica'sı olarak `pg_rewind` ile yeniden katılır.

## 7. Planlı işlemler

**Switchover** (operatör; ön kontrollerden ve kümenin adı yazıldıktan sonra):

```
 failover'ı duraklat + epoch ─► CHECKPOINT ─► primary'yi düzgünce durdur, VIP'i kaldır ─► hedef son LSN'i uygular
   ─► VIP boş mu? (arping -D) ─► VIP'i hedefte aç ─► promote ─► kaydet ─► eski primary replica olarak başlar
   ─► diğer replica'lar takip eder ─► failover'ı yeniden aç
```

Düzgün durdurma bir kapanış checkpoint'i yazar; hedef ancak onu uyguladıktan sonra promote edilir, böylece
durdurmadan önce yazılan hiçbir şey kaybolmaz. VIP hedefte açılamazsa hedef replica olarak kalır ve eski primary
VIP'iyle birlikte yeniden primary olarak başlar.

**Node onarımları** rol değişikliklerinin yanında çalışır ve failover'ı duraklatmaz: *Yeniden katıl* (durdur,
`pg_rewind`, replica olarak başlat), *Yeniden oluştur* (`pg_basebackup` ile yeni bir dizine; kopya bitene kadar
eskisi kenarda tutulur), *Replica'yı yeniden başlat*. **Primary olarak başlat**, çalışan primary'si olmayan bir
küme içindir.

**Konsolda küme eklemek**: node'larıyla bir taslak, tek kullanımlık kayıt token'ları, her sunucuda
`minipgagent setup` (sertifika, ayarlar, sudo kuralı, systemd), node'lar arası kontroller, ardından *Benimse*
(çalışan primary'yi kaydet) veya *Kur* (bir node'u primary olarak başlat ve diğerlerini ondan yeniden oluştur).

## 8. Hangi mekanizma hangi arızayı önler

| Arıza | Mekanizma |
| --- | --- |
| Failover sonrası iki primary (split brain) | epoch'lar; geri dönen veya yetkisiz primary'nin fence edilmesi; failover öncesi replica ve watcher doğrulaması; self-fencing ile fence eden lease ve lease beklemesi |
| VIP'i iki sunucunun tutması | VIP yoksa promote yok; açmadan önce `arping -D` ve canlı bir komşuya karşı öz-sınaması (çakışmaları gizleyen ağlarda VIP kapalı kalır); primary olmayan bir node'da bulunan VIP kaldırılır |
| Yerini kaybetmiş bir watcher'ın hareket etmesi | durum üzerinde compare-and-set; agent'ların kontrol ettiği epoch'lar; her lease yenilemesinden ve geri alınamayan her adımdan önce liderliğin doğrulanması |
| Veri kaybetmiş bir replica'nın promote edilmesi | aday kuralları (timeline, gecikme sınırı, önce alınan WAL); switchover'ın son LSN'i beklemesi; bildirilen veri kaybı kontrolü |
| Yanlış node'a veya kümeye komut vermek | tek bir küme kimliğine bağlı agent'lar; watcher'ın her sertifikanın node'unun adını taşıdığını kontrol etmesi; system identifier kontrolü; *Yanlış kimlik* node'lara dokunulmaz |
| Başka bir veritabanı sunucusunun agent'a komut vermesi | BFM CA'sıyla karşılıklı TLS ve watcher sertifikalarıyla sınırlı `tls.allowed.clients` |
| Yarısı yeniden yazılmış bir veri dizininin başlaması | agent durumundaki yarıda kalmış işlem işareti; böyle bir node yalnızca yeniden oluşturulur |
| Bir node'un kendi başına primary olarak başlaması | agent, watcher izin vermedikçe PostgreSQL'i yalnızca replica olarak (`standby.signal`) başlatır; PostgreSQL servisi devre dışıdır |
| Bilinmeyen bir sonucun başarısızlık sayılması | uzun komut zaman aşımları; belirsiz promote sonuçları izlenir, başka yerde yeniden denenmez |

## 9. Zamanlama

| Ayar | Varsayılan | Etkisi |
| --- | --- | --- |
| `poll-interval` | 2 sn | her agent'a ne sıklıkla sorulduğu |
| `failure-threshold` | 3 sorgu | primary yaklaşık 6 sn sonra kayıp sayılır |
| `agent-timeout` | 3 sn | tek bir durum çağrısı |
| `lease-ttl` / `lease-margin` | 30 sn / 5 sn | bir primary'nin yenileme almadan ne kadar yaşadığı (self-fencing) ve promote öncesi ek bekleme |
| `observation-fresh` | 10 sn | çoğunluk kontrolü için başka bir watcher'ın gözleminin en fazla ne kadar eski olabileceği |
| `max-lag` | 16 MB | daha gerideki replica'lar otomatik promote edilmez |
| `failover-retry` | 60 sn | başarısız veya engellenen bir failover'dan sonraki bekleme (bir replica'nın doğrulamasının durdurduğu deneme için değil: o bir sonraki sorguda yeniden bakılır) |
| `switchover-catchup` | 60 sn | switchover'ın hedefin son LSN'i uygulamasını ne kadar beklediği |
| agent `probe.interval.ms` | 1 sn | agent durumunun ne kadar taze olduğu |

Bir bulut ağındaki üç PostgreSQL 16 sunucusunda ölçüldü (VIP yok, self-fencing yok). Failover'ın kendisi — epoch,
promote, kayıt, yeniden yönlendirme — yaklaşık 10 sn sürer; tespit `failure-threshold` sorgu, yaklaşık 6–15 sn sürer
(ulaşılamayan bir agent her sorguda `agent-timeout` kadar zaman alır). Gerisini replica doğrulaması belirler.
Primary'nin sunucusu çökmek yerine yalnızca ağdan koparsa replica'lara hiçbir TCP sıfırlaması ulaşmaz: WAL
alıcıları, PostgreSQL'in `wal_receiver_timeout` süresi bitirene kadar stream'i sürüyor gösterir ve biri bunu yaptıkça
failover bekler. Yalnızca bu doğrulamanın durdurduğu bir failover henüz hiçbir şey yapmamıştır (epoch artmamıştır);
bu yüzden watcher bir sonraki sorguda yeniden bakar ve boş denemenin kaydını tutmaz. Başarısız veya engellenen diğer
her deneme `failover-retry` kadar bekler. `minipgagent setup`, `wal_receiver_timeout = 10s` önerir (varsayılan 60
sn). Bir ağ kesintisi böylece toplamda 37 sn sürdü; varsayılan 60 sn ve eski 60 sn'lik bekleme ile 62–105 sn
sürmüştü. Self-fencing açıkken lease beklemesi, son yenilemeden itibaren en fazla `lease-ttl` + `lease-margin`
ekler; eski primary ölmemiş, yalnızca kopmuşsa iki primary olasılığını dışlamanın bedeli budur.

Bu testlerde geri dönen eski primary, yeniden yanıt vermeye başladıktan sonraki 3 sn içinde fence edildi ve
yaklaşık 35 sn'de `pg_rewind` ile yeniden katıldı; hiçbir veri kaybolmadı (eski primary'de görülen son WAL konumu
yeni primary'ye ulaşmıştı).

Üç watcher ve self-fencing ile, aynı sunucularda (VIP her node'da `ip addr` ile, saniyede bir örneklenerek kontrol
edildi; bu ağ VIP'i istemcilere taşımaz):

- **Lider durur**: 4 sn sonra başka bir watcher liderdi; küme sağlıklı kaldı, lease'i yeni lider yeniledi.
- **Primary ağdan kopar**: lease'i dolunca 29 sn sonra kendini durdurdu; watcher'lar yeni primary VIP'i almadan önce o
  lease'i bekledi, VIP 9 sn sonra yeni primary'deydi; failover kesintiden 43 sn sonra bitti. VIP hiçbir zaman iki
  node'da birden değildi. Ağa dönen eski primary `pg_rewind` ile yeniden katıldı; veri kaybı yok.
- **Üç watcher'dan ikisi durur**: çoğunluk olmadan hiçbir watcher hareket edemez; lease yenilenmedi ve primary 33 sn
  sonra kendini durdurdu — yazma yok, ama ikinci bir primary de yok. Watcher'lar dönünce lider onu agent'ı yanıt veren
  ama fence edilmiş halde buldu ve yerinde yeniden başlattı; watcher'lar başlatıldıktan 49 sn sonra küme sağlıklıydı.

## 10. Güvenlik

- **Agent'lar** yalnızca BFM CA'sının imzaladığı bir istemci sertifikasıyla HTTPS konuşur; `tls.allowed.clients`
  bunu watcher sertifikalarına daraltır. Her komut küme kimliğini ve epoch'u taşır.
- **CA** paylaşılan durumda yaşar; anahtarı `secret.key` ile (AES-GCM) şifrelidir. `bfm-watcher setup` onu
  oluşturur ya da onun yerine `bfm-make-certs` ile çevrimdışı bir CA kullanılır. Veritabanı sunucuları 30 dakika ve
  tek bir node için geçerli tek kullanımlık token'larla kaydolur; kayıt komutu konsol sertifikasının parmak izini
  sabitler, böylece token başka hiçbir yere gitmez.
- **Konsol ve API**: HTTPS, `watcher.yml`'de bcrypt özetleriyle kullanıcılar, iki rol (ADMIN işlem yapar, VIEWER
  okur). Her operatör eylemi kullanıcı adıyla olay günlüğüne yazılır. API durum kodlarıyla JSON yanıt verir ve
  asla oturum açma sayfasına yönlendirmez.
- **Saklanan gizli bilgiler**: SMTP parolası ve CA anahtarı `secret.key` ile şifrelidir; anahtar deposu parolaları
  `watcher.yml`'de değil, `/etc/bfm` altında kendi dosyalarındadır. Replikasyon rolü SCRAM doğrulayıcısıyla
  oluşturulur; parolanın kendisi hiçbir komut satırında veya günlükte görünmez.
- **Veritabanı sunucularında root**: agent `postgres` olarak çalışır; sudo kuralı yalnızca tek bir arayüzde tek bir
  VIP için `ip addr add/del`'e, o VIP ve öz-sınama komşusu için `arping`'e izin verir, başka hiçbir şeye değil.
  `pg_rewind` süper kullanıcı olarak değil, gereken fonksiyon yetkileri verilmiş replikasyon rolüyle çalışır.

## 11. Kurulum biçimleri

| Biçim | Ne zaman | Notlar |
| --- | --- | --- |
| Tek watcher | bir lab ya da watcher kapalıyken otomatik failover olmamasını kabul eden bir ortam | replica doğrulaması yine her failover'ı korur |
| Self-fencing ile üç watcher (Raft) | üretim | çoğunluk hemfikir olmalı; kopmuş bir primary kendini durdurur; lider saniyeler içinde değişir |
| Bir DR veri merkezi | uzaktaki replica'lar | `DC_LOCAL` kapsamı otomatik failover'ı primary'nin veri merkezinde tutar; bir DR replica'sı ancak operatör kararıyla primary olur; her veri merkezinin kendi VIP'i vardır |
| Watcher grubu başına birden çok küme | çok sayıda küme | küme başına bir motor, Filo ve Sunucular sayfalarıyla tek bir konsol |
| Sunucu başına birden çok küme | paylaşılan veritabanı sunucuları | PostgreSQL instance'ı başına bir agent instance'ı (`minipgagent@<instance>`), kendi portu, durumu ve sudo kuralı; bir kümenin iki node'unun aynı sunucuda olması reddedilir |

## 12. Konsol ve diller

Konsol (Vaadin 25, sunucu tarafı Java) her şeyi `ConsoleService` ve `FleetService` üzerinden okur ve doğrudan
hiçbir şeyi değiştirmez; her eylem bir API çağrısı gibi motordan geçer. İngilizcedir ve başka diller için
kurulmuştur: görünümlerin kendi metinleri `i18n/messages*.properties`'ten gelir, `i18n/languages` dilleri listeler
(bugün yalnız İngilizce). Bir dil kod değişmeden eklenir: etiketi `i18n/languages`'a, tüm anahtarlarıyla
`messages_<etiket>.properties` ve isteğe bağlı `runtime-<etiket>.json`; sağdan sola yazılan bir dil konsolu sağdan
sola dizer. Motorun ürettiği metinler (sağlık, olaylar, işlem adımları, kontroller, hatalar) durumda, API'de ve
e-postalarda İngilizce kalır; bir dilin `runtime-<etiket>.json` dosyası onları ekran için çevirir (`{node} is down`
gibi şablonlar; katalogun bilmediği bir metin İngilizce gösterilir). Böylece farklı dillerdeki iki operatör aynı olguları görür; günlükler
ve e-postalar aranabilir kalır.

## 13. Kodda neyin nerede olduğu

| Modül | Paketler | Bakılacak yer |
| --- | --- | --- |
| `bfm-common` | `model`, `json`, `tls` | agent API'sinin kayıtları (`NodeStatus`, `ApiResult`), JSON kodlayıcı, LSN aritmetiği, keytool ve TLS yardımcıları |
| `bfm-agent` | `http`, `pg`, `vip`, `state`, `setup`, `exec` | `AgentService` (döngüler, komutlar), `PgProbe`, `VipManager`, `LeaseGuard`, `SetupCommand`, `Readiness` |
| `bfm-core` | `decision`, `ops`, `state`, `monitor`, `notify`, `event`, `agent`, `config` | `Watcher` (sorgu döngüsü), `CandidateSelector`, `PrimaryHealth`, `FailoverOperation`, `SwitchoverOperation`, `NodeRepairOperation`, `ClusterState`, `StateStore` |
| `bfm-raft` | — | `RaftStateStore`, Ratis durum makinesi |
| `bfm-watcher` | `service`, `ui`, `api`, `i18n`, `security`, `setup`, `mail`, `config` | `ClusterHub`, `ConsoleService`, `EnrollmentService`, `WatcherCa`, Vaadin sayfaları, `WatcherSetup` |

Testler: `mvn verify`, motoru agent'ın kurallarına uyan bellek içi bir kümeye karşı (`bfm-core` `WatcherTest`), üç
süreç içi Raft üyesini, REST API'yi ve konsol metinlerini çalıştırır; `AgentPostgresIT` ve `WatcherPostgresIT`
gerçek PostgreSQL kullanır (README'ye bakın).
