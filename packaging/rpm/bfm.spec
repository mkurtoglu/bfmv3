# BFM RPM packages for Rocky / RHEL 8 and 9. Built by packaging/rpm/build.sh from the jars that mvn package
# produced; the jars are pure Java, so one noarch build serves every architecture and both distributions differ
# only in the dist tag. Scriptlets are written out in plain shell (not %systemd_* macros) so the spec builds on
# any host that has rpmbuild.

%global bfm_home %{_datadir}/bfm
%{!?_unitdir: %global _unitdir /usr/lib/systemd/system}
%global debug_package %{nil}

Name:           bfm
Version:        %{?bfm_version}%{!?bfm_version:0.1.0}
Release:        %{?bfm_release}%{!?bfm_release:1}%{?dist}
Summary:        PostgreSQL failover manager
License:        Proprietary
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
Requires:       systemd
Requires(pre):  shadow-utils

%description -n minipgagent
Runs as the postgres user next to one PostgreSQL server. It reports the node's state to the watchers, brings the
virtual IP up and down with sudo ip addr, promotes only while the VIP is up on this node, and fences itself when
the primary lease from the watchers runs out.

Settings: /etc/bfm/minipgagent/agent.properties. Sudo rule for the VIP: /etc/sudoers.d/minipgagent (set the
address and the interface). Disable the PostgreSQL unit and let the agent start PostgreSQL.

Several PostgreSQL instances on one host: one agent instance each, minipgagent@<instance>.service, with its
settings in /etc/bfm/minipgagent/<instance>/ and its state in /var/lib/minipgagent/<instance>/.

# --------------------------------------------------------------------------------------------------- watcher

%package watcher
Summary:        BFM watcher: failover decisions, web console and REST API
Requires:       java-21-headless
Requires:       openssl
Requires:       systemd
Requires(pre):  shadow-utils

%description watcher
Polls the agents, keeps the primary's lease, decides failovers and runs switchovers, and serves the web console
and the REST API. One watcher works alone; three form an Apache Ratis (Raft) group.

Settings: /etc/bfm/watcher.yml. Password hashes for the console users: bfm-watcher hash-password.
Certificates for mutual TLS: bfm-make-certs.

# -----------------------------------------------------------------------------------------------------------

%prep
# nothing to unpack: the sources are the built jars and plain files

%build
# the jars were built by Maven

%install
install -d -m 0755 %{buildroot}%{bfm_home}
install -d -m 0755 %{buildroot}%{_bindir}
install -d -m 0755 %{buildroot}%{_sysconfdir}/bfm
install -d -m 0750 %{buildroot}%{_sysconfdir}/bfm/minipgagent
install -d -m 0750 %{buildroot}%{_sysconfdir}/sudoers.d
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
install -p -m 0600 %{SOURCE7} %{buildroot}%{_sysconfdir}/bfm/minipgagent/agent.properties
install -p -m 0640 %{SOURCE8} %{buildroot}%{_sysconfdir}/bfm/watcher.yml
install -p -m 0440 %{SOURCE9} %{buildroot}%{_sysconfdir}/sudoers.d/minipgagent
install -p -m 0644 %{SOURCE10} %{buildroot}%{bfm_home}/README.md

# ----------------------------------------------------------------------------------------------- scriptlets

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
exit 0

%post -n minipgagent
systemctl daemon-reload >/dev/null 2>&1 || :
if [ $1 -eq 1 ]; then
    # first installation: apply the preset policy (disabled unless the site enables it)
    systemctl --no-reload preset minipgagent.service >/dev/null 2>&1 || :
fi
# An upgrade does not restart the agent: a restart would interrupt a running pg_basebackup or pg_rewind.
# Restart it when the node is idle: systemctl restart minipgagent

%preun -n minipgagent
if [ $1 -eq 0 ]; then
    # removal, not upgrade. KillMode=process keeps PostgreSQL running.
    systemctl --no-reload disable --now minipgagent.service >/dev/null 2>&1 || :
    for u in $(systemctl list-units --all --plain --no-legend 'minipgagent@*.service' 2>/dev/null \
            | awk '{print $1}'); do
        systemctl --no-reload disable --now "$u" >/dev/null 2>&1 || :
    done
fi

%postun -n minipgagent
systemctl daemon-reload >/dev/null 2>&1 || :

%pre watcher
if ! getent group bfm >/dev/null; then
    groupadd -r bfm
fi
if ! getent passwd bfm >/dev/null; then
    useradd -r -N -g bfm -d /var/lib/bfm-watcher -s /sbin/nologin -c "BFM watcher" bfm
fi
exit 0

%post watcher
systemctl daemon-reload >/dev/null 2>&1 || :
if [ $1 -eq 1 ]; then
    systemctl --no-reload preset bfm-watcher.service >/dev/null 2>&1 || :
fi
# An upgrade does not restart the watcher: restart it when no operation is running: systemctl restart bfm-watcher

%preun watcher
if [ $1 -eq 0 ]; then
    systemctl --no-reload disable --now bfm-watcher.service >/dev/null 2>&1 || :
fi

%postun watcher
systemctl daemon-reload >/dev/null 2>&1 || :

# ---------------------------------------------------------------------------------------------------- files

%files -n minipgagent
%doc %{bfm_home}/README.md
%dir %{bfm_home}
%{bfm_home}/minipgagent.jar
%{_bindir}/minipgagent
%dir %{_sysconfdir}/bfm
%attr(0750,root,postgres) %dir %{_sysconfdir}/bfm/minipgagent
%config(noreplace) %attr(0600,postgres,postgres) %{_sysconfdir}/bfm/minipgagent/agent.properties
%config(noreplace) %attr(0440,root,root) %{_sysconfdir}/sudoers.d/minipgagent
%attr(0700,postgres,postgres) %dir /var/lib/minipgagent
%{_unitdir}/minipgagent.service
%{_unitdir}/minipgagent@.service

%files watcher
%doc %{bfm_home}/README.md
%dir %{bfm_home}
%{bfm_home}/bfm-watcher.jar
%{_bindir}/bfm-watcher
%{_bindir}/bfm-make-certs
%dir %{_sysconfdir}/bfm
%config(noreplace) %attr(0640,root,bfm) %{_sysconfdir}/bfm/watcher.yml
%attr(0700,bfm,bfm) %dir /var/lib/bfm-watcher
%{_unitdir}/bfm-watcher.service

%changelog
* Sat Oct 31 2026 BFM <bfm@localhost> - 0.1.0-2
- minipgagent@.service for several PostgreSQL instances per host; agents bind to one cluster
* Wed Oct 28 2026 BFM <bfm@localhost> - 0.1.0-1
- First packages: agent and watcher for Rocky / RHEL 8 and 9
