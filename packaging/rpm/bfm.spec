# BFM RPM packages for Rocky / RHEL 8, 9 and 10. Built by packaging/build.sh from the jars that mvn package
# produced; the jars are pure Java, so one noarch build serves every architecture and the distributions differ only
# in the dist tag. Scriptlets are written out in plain shell (not %systemd_* macros) so the spec builds on any host
# that has rpmbuild, Rocky 9 included, for every target.
#
# The packages own no file under /etc. The settings are made from the examples in /usr/share/bfm/examples when they
# are missing (%posttrans, through /usr/share/bfm/pkg/<package>.sh) and are not touched again: an upgrade leaves no
# .rpmnew or .rpmsave behind, a removal keeps them, and a reinstall uses them and says so (bfm-watcher setup asks
# before replacing them).

%global bfm_home %{_datadir}/bfm
%{!?_unitdir: %global _unitdir /usr/lib/systemd/system}
%global debug_package %{nil}

Name:           bfm
Version:        %{?bfm_version}%{!?bfm_version:0.1.0}
Release:        %{?bfm_release}%{!?bfm_release:1}%{?dist}
Summary:        PostgreSQL failover manager
License:        Apache-2.0
URL:            https://github.com/mkurtoglu/bfmv3
BuildArch:      noarch

Source0:        minipgagent.jar
Source1:        bfm-watcher.jar
Source2:        minipgagent
Source3:        bfm-watcher
Source4:        bfm-make-certs
Source5:        minipgagent.service
Source6:        bfm-watcher.service
Source7:        agent.properties.example
Source8:        watcher.yml.example
Source9:        sudoers-minipgagent
Source10:       README.md
Source11:       minipgagent@.service
Source12:       LICENSE
Source13:       NOTICE
Source14:       minipgagent-pkg.sh
Source15:       bfm-watcher-pkg.sh

%description
Automatic failover for PostgreSQL streaming replication clusters, with a virtual IP that follows the primary.
An agent runs next to every PostgreSQL server; one or three watchers decide and serve the web console.

# ----------------------------------------------------------------------------------------------------- agent

%package -n minipgagent
Summary:        MiniPG agent: PostgreSQL role changes and the virtual IP on one node
Requires:       java-21-headless
Requires:       iproute
Requires:       iputils
Requires:       sudo
Requires:       util-linux
Requires:       systemd
Requires:       coreutils
Requires:       sed
Requires(pre):  shadow-utils

%description -n minipgagent
Runs as the postgres user next to one PostgreSQL server. It reports the node's state to the watchers, brings the
virtual IP up and down with sudo ip addr, promotes only while the VIP is up on this node, and fences itself when
the primary lease from the watchers runs out.

Set it up with minipgagent setup. Settings: /etc/bfm/minipgagent/; several PostgreSQL instances on one host run
one agent each, minipgagent@<instance>.service. The settings stay when the package is removed.

# --------------------------------------------------------------------------------------------------- watcher

%package watcher
Summary:        BFM watcher: failover decisions, web console and REST API
Requires:       java-21-headless
Requires:       openssl
Requires:       systemd
Requires:       coreutils
Requires:       sed
Requires(pre):  shadow-utils

%description watcher
Polls the agents, keeps the primary's lease, decides failovers and runs switchovers, and serves the web console
and the REST API. One watcher works alone; three form an Apache Ratis (Raft) group.

Set it up with bfm-watcher setup. Settings: /etc/bfm/watcher.yml; state: /var/lib/bfm-watcher. Both stay when the
package is removed.

# -----------------------------------------------------------------------------------------------------------

%prep
# nothing to unpack: the sources are the built jars and plain files

%build
# the jars were built by Maven

%install
install -d -m 0755 %{buildroot}%{bfm_home}/examples %{buildroot}%{bfm_home}/pkg
install -d -m 0755 %{buildroot}%{_bindir}
install -d -m 0755 %{buildroot}%{_sysconfdir}/bfm
install -d -m 0750 %{buildroot}%{_sysconfdir}/bfm/minipgagent
install -d -m 0755 %{buildroot}%{_unitdir}
install -d -m 0700 %{buildroot}/var/lib/minipgagent
install -d -m 0700 %{buildroot}/var/lib/bfm-watcher

install -p -m 0644 %{SOURCE0} %{buildroot}%{bfm_home}/minipgagent.jar
install -p -m 0644 %{SOURCE1} %{buildroot}%{bfm_home}/bfm-watcher.jar
install -p -m 0755 %{SOURCE2} %{buildroot}%{_bindir}/minipgagent
install -p -m 0755 %{SOURCE3} %{buildroot}%{_bindir}/bfm-watcher
install -p -m 0755 %{SOURCE4} %{buildroot}%{_bindir}/bfm-make-certs
install -p -m 0644 %{SOURCE5} %{buildroot}%{_unitdir}/minipgagent.service
install -p -m 0644 %{SOURCE11} %{buildroot}%{_unitdir}/minipgagent@.service
install -p -m 0644 %{SOURCE6} %{buildroot}%{_unitdir}/bfm-watcher.service
install -p -m 0644 %{SOURCE7} %{buildroot}%{bfm_home}/examples/agent.properties.example
install -p -m 0644 %{SOURCE8} %{buildroot}%{bfm_home}/examples/watcher.yml.example
install -p -m 0644 %{SOURCE9} %{buildroot}%{bfm_home}/examples/sudoers-minipgagent.example
install -p -m 0755 %{SOURCE14} %{buildroot}%{bfm_home}/pkg/minipgagent.sh
install -p -m 0755 %{SOURCE15} %{buildroot}%{bfm_home}/pkg/bfm-watcher.sh
install -p -m 0644 %{SOURCE10} %{buildroot}%{bfm_home}/README.md
install -p -m 0644 %{SOURCE12} %{buildroot}%{bfm_home}/LICENSE
install -p -m 0644 %{SOURCE13} %{buildroot}%{bfm_home}/NOTICE

# ----------------------------------------------------------------------------------------------- scriptlets
# $1 in %pre/%post: 1 installation, 2 upgrade. In %preun/%postun: 0 removal, 1 upgrade.

%pre -n minipgagent
# The postgres user normally exists already (PostgreSQL packages create it with uid 26); create it the same way
# when the agent is installed first.
if ! getent group postgres >/dev/null; then
    groupadd -r -g 26 postgres 2>/dev/null || groupadd -r postgres
fi
if ! getent passwd postgres >/dev/null; then
    useradd -r -N -g postgres -u 26 -d /var/lib/pgsql -s /bin/bash -c "PostgreSQL Server" postgres 2>/dev/null \
        || useradd -r -N -g postgres -d /var/lib/pgsql -s /bin/bash -c "PostgreSQL Server" postgres
fi
if [ $1 -eq 1 ]; then
    # an installation, not an upgrade: %%posttrans names the settings an earlier installation left
    : > /run/bfm-pkg-minipgagent.install 2>/dev/null || :
fi
exit 0

%post -n minipgagent
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload >/dev/null 2>&1 || :
fi
if [ $1 -eq 1 ]; then
    # first installation: apply the preset policy (disabled unless the site enables it)
    systemctl --no-reload preset minipgagent.service >/dev/null 2>&1 || :
fi
exit 0

%preun -n minipgagent
if [ $1 -eq 0 ]; then
    # removal, not upgrade. KillMode=process keeps PostgreSQL running.
    %{bfm_home}/pkg/minipgagent.sh stop
    %{bfm_home}/pkg/minipgagent.sh notice-removed
fi
exit 0

%postun -n minipgagent
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload >/dev/null 2>&1 || :
fi
if [ $1 -ge 1 ]; then
    # An upgrade does not restart the agent: a restart would interrupt a running pg_basebackup or pg_rewind.
    echo "minipgagent: restart the agents when the node is idle: systemctl restart 'minipgagent*'"
fi
exit 0

%posttrans -n minipgagent
# After the whole transaction: an upgrade from a package that still owned the settings has moved an edited one
# to .rpmsave by now, and seed puts it back.
%{bfm_home}/pkg/minipgagent.sh seed
if %{bfm_home}/pkg/minipgagent.sh take-install-mark; then
    %{bfm_home}/pkg/minipgagent.sh notice-kept
fi
exit 0

%pre watcher
if ! getent group bfm >/dev/null; then
    groupadd -r bfm
fi
if ! getent passwd bfm >/dev/null; then
    useradd -r -N -g bfm -d /var/lib/bfm-watcher -s /sbin/nologin -c "BFM watcher" bfm
fi
if [ $1 -eq 1 ]; then
    : > /run/bfm-pkg-bfm-watcher.install 2>/dev/null || :
fi
exit 0

%post watcher
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload >/dev/null 2>&1 || :
fi
if [ $1 -eq 1 ]; then
    systemctl --no-reload preset bfm-watcher.service >/dev/null 2>&1 || :
fi
exit 0

%preun watcher
if [ $1 -eq 0 ]; then
    %{bfm_home}/pkg/bfm-watcher.sh stop
    %{bfm_home}/pkg/bfm-watcher.sh notice-removed
fi
exit 0

%postun watcher
if [ -d /run/systemd/system ]; then
    systemctl daemon-reload >/dev/null 2>&1 || :
fi
if [ $1 -ge 1 ]; then
    # An upgrade does not restart the watcher: it would interrupt a running switchover or re-initialize.
    echo "bfm-watcher: restart it when no operation runs: systemctl restart bfm-watcher"
fi
exit 0

%posttrans watcher
%{bfm_home}/pkg/bfm-watcher.sh seed
if %{bfm_home}/pkg/bfm-watcher.sh take-install-mark; then
    %{bfm_home}/pkg/bfm-watcher.sh notice-kept
fi
exit 0

# ---------------------------------------------------------------------------------------------------- files

%files -n minipgagent
%doc %{bfm_home}/README.md
%license %{bfm_home}/LICENSE
%license %{bfm_home}/NOTICE
%dir %{bfm_home}
%dir %{bfm_home}/examples
%dir %{bfm_home}/pkg
%{bfm_home}/minipgagent.jar
%{bfm_home}/examples/agent.properties.example
%{bfm_home}/examples/sudoers-minipgagent.example
%{bfm_home}/pkg/minipgagent.sh
%{_bindir}/minipgagent
%dir %{_sysconfdir}/bfm
%attr(0750,root,postgres) %dir %{_sysconfdir}/bfm/minipgagent
%attr(0700,postgres,postgres) %dir /var/lib/minipgagent
%{_unitdir}/minipgagent.service
%{_unitdir}/minipgagent@.service

%files watcher
%doc %{bfm_home}/README.md
%license %{bfm_home}/LICENSE
%license %{bfm_home}/NOTICE
%dir %{bfm_home}
%dir %{bfm_home}/examples
%dir %{bfm_home}/pkg
%{bfm_home}/bfm-watcher.jar
%{bfm_home}/examples/watcher.yml.example
%{bfm_home}/pkg/bfm-watcher.sh
%{_bindir}/bfm-watcher
%{_bindir}/bfm-make-certs
%dir %{_sysconfdir}/bfm
%attr(0700,bfm,bfm) %dir /var/lib/bfm-watcher
%{_unitdir}/bfm-watcher.service

%changelog
* Thu Nov 05 2026 BFM <bfm@localhost> - 0.1.0-4
- Rocky / RHEL 10 as well; built on Rocky 9 for every target
- the packages own no file under /etc: settings come from /usr/share/bfm/examples when missing, an upgrade leaves no
  .rpmnew or .rpmsave, a removal keeps them, a reinstall names them; settings an older package had moved to
  .rpmsave are put back
- the start scripts pick a Java 21 runtime when the default java is an older one (BFM_JAVA overrides)
- bfm-watcher setup asks before replacing settings an earlier installation left, not about the example
* Mon Nov 02 2026 BFM <bfm@localhost> - 0.1.0-3
- Apache License 2.0; LICENSE and NOTICE installed
- bfm-watcher setup gives the data directory to the bfm user (the service could not read secret.key)
- minipgagent setup: the packaged example settings no longer count as a running agent; checks listen_addresses;
  creates the replication role with the grants pg_rewind needs and rewinds as it; pg_hba.conf lines for every
  node, itself included; the duplicate-check self-test neighbour on the VIP's interface
- the services end as "inactive", not "failed", when stopped
- a failover held back only by a replica that still reached the old primary is looked at again at the next poll
  (not after failover-retry); setup proposes wal_receiver_timeout = 10s: a network cut failed over in 37 s, not 105 s
- snapshot releases carry the build time, so a later build upgrades an earlier one on the same day
- bfm-watcher setup says that a watcher which ran alone keeps its state out of a new Raft group; "keep" for an
  existing secret.key
* Sat Oct 31 2026 BFM <bfm@localhost> - 0.1.0-2
- minipgagent@.service for several PostgreSQL instances per host; agents bind to one cluster
- minipgagent setup / remove and bfm-watcher setup; enrollment with one-time tokens
* Wed Oct 28 2026 BFM <bfm@localhost> - 0.1.0-1
- First packages: agent and watcher for Rocky / RHEL 8 and 9
