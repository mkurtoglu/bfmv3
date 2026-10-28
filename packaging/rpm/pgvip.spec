# pgVIP RPM packages for Rocky / RHEL 8 and 9. Built by packaging/rpm/build.sh from the jars that mvn package
# produced; the jars are pure Java, so one noarch build serves every architecture and both distributions differ
# only in the dist tag. Scriptlets are written out in plain shell (not %systemd_* macros) so the spec builds on
# any host that has rpmbuild.

%global pgvip_home %{_datadir}/pgvip
%{!?_unitdir: %global _unitdir /usr/lib/systemd/system}
%global debug_package %{nil}

Name:           pgvip
Version:        %{?pgvip_version}%{!?pgvip_version:0.1.0}
Release:        %{?pgvip_release}%{!?pgvip_release:1}%{?dist}
Summary:        PostgreSQL VIP failover manager
License:        Proprietary
URL:            https://github.com/mkurtoglu/bfmv3
BuildArch:      noarch

Source0:        pgvip-agent.jar
Source1:        pgvip-watcher.jar
Source2:        pgvip-agent
Source3:        pgvip-watcher
Source4:        pgvip-make-certs
Source5:        pgvip-agent.service
Source6:        pgvip-watcher.service
Source7:        agent.properties.example
Source8:        watcher.yml.example
Source9:        sudoers-pgvip
Source10:       README.md

%description
Automatic failover for PostgreSQL streaming replication clusters, with a virtual IP that follows the primary.
An agent runs next to every PostgreSQL server; one or three watchers decide and serve the web console.

# ----------------------------------------------------------------------------------------------------- agent

%package agent
Summary:        pgVIP agent: PostgreSQL role changes and the virtual IP on one node
Requires:       java-21-headless
Requires:       iproute
Requires:       iputils
Requires:       sudo
Requires:       systemd
Requires(pre):  shadow-utils

%description agent
Runs as the postgres user next to one PostgreSQL server. It reports the node's state to the watchers, brings the
virtual IP up and down with sudo ip addr, promotes only while the VIP is up on this node, and fences itself when
the primary lease from the watchers runs out.

Settings: /etc/pgvip/agent.properties. Sudo rule for the VIP: /etc/sudoers.d/pgvip (set the address and the
interface). Disable the PostgreSQL unit and let the agent start PostgreSQL.

# --------------------------------------------------------------------------------------------------- watcher

%package watcher
Summary:        pgVIP watcher: failover decisions, web console and REST API
Requires:       java-21-headless
Requires:       openssl
Requires:       systemd
Requires(pre):  shadow-utils

%description watcher
Polls the agents, keeps the primary's lease, decides failovers and runs switchovers, and serves the web console
and the REST API. One watcher works alone; three form an Apache Ratis (Raft) group.

Settings: /etc/pgvip/watcher.yml. Password hashes for the console users: pgvip-watcher hash-password.
Certificates for mutual TLS: pgvip-make-certs.

# -----------------------------------------------------------------------------------------------------------

%prep
# nothing to unpack: the sources are the built jars and plain files

%build
# the jars were built by Maven

%install
install -d -m 0755 %{buildroot}%{pgvip_home}
install -d -m 0755 %{buildroot}%{_bindir}
install -d -m 0755 %{buildroot}%{_sysconfdir}/pgvip
install -d -m 0750 %{buildroot}%{_sysconfdir}/sudoers.d
install -d -m 0755 %{buildroot}%{_unitdir}
install -d -m 0700 %{buildroot}/var/lib/pgvip
install -d -m 0700 %{buildroot}/var/lib/pgvip-watcher

install -p -m 0644 %{SOURCE0} %{buildroot}%{pgvip_home}/pgvip-agent.jar
install -p -m 0644 %{SOURCE1} %{buildroot}%{pgvip_home}/pgvip-watcher.jar
install -p -m 0755 %{SOURCE2} %{buildroot}%{_bindir}/pgvip-agent
install -p -m 0755 %{SOURCE3} %{buildroot}%{_bindir}/pgvip-watcher
install -p -m 0755 %{SOURCE4} %{buildroot}%{_bindir}/pgvip-make-certs
install -p -m 0644 %{SOURCE5} %{buildroot}%{_unitdir}/pgvip-agent.service
install -p -m 0644 %{SOURCE6} %{buildroot}%{_unitdir}/pgvip-watcher.service
install -p -m 0600 %{SOURCE7} %{buildroot}%{_sysconfdir}/pgvip/agent.properties
install -p -m 0640 %{SOURCE8} %{buildroot}%{_sysconfdir}/pgvip/watcher.yml
install -p -m 0440 %{SOURCE9} %{buildroot}%{_sysconfdir}/sudoers.d/pgvip
install -p -m 0644 %{SOURCE10} %{buildroot}%{pgvip_home}/README.md

# ----------------------------------------------------------------------------------------------- scriptlets

%pre agent
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

%post agent
systemctl daemon-reload >/dev/null 2>&1 || :
if [ $1 -eq 1 ]; then
    # first installation: apply the preset policy (disabled unless the site enables it)
    systemctl --no-reload preset pgvip-agent.service >/dev/null 2>&1 || :
fi
# An upgrade does not restart the agent: a restart would interrupt a running pg_basebackup or pg_rewind.
# Restart it when the node is idle: systemctl restart pgvip-agent

%preun agent
if [ $1 -eq 0 ]; then
    # removal, not upgrade. KillMode=process keeps PostgreSQL running.
    systemctl --no-reload disable --now pgvip-agent.service >/dev/null 2>&1 || :
fi

%postun agent
systemctl daemon-reload >/dev/null 2>&1 || :

%pre watcher
if ! getent group pgvip >/dev/null; then
    groupadd -r pgvip
fi
if ! getent passwd pgvip >/dev/null; then
    useradd -r -N -g pgvip -d /var/lib/pgvip-watcher -s /sbin/nologin -c "pgVIP watcher" pgvip
fi
exit 0

%post watcher
systemctl daemon-reload >/dev/null 2>&1 || :
if [ $1 -eq 1 ]; then
    systemctl --no-reload preset pgvip-watcher.service >/dev/null 2>&1 || :
fi
# An upgrade does not restart the watcher: restart it when no operation is running: systemctl restart pgvip-watcher

%preun watcher
if [ $1 -eq 0 ]; then
    systemctl --no-reload disable --now pgvip-watcher.service >/dev/null 2>&1 || :
fi

%postun watcher
systemctl daemon-reload >/dev/null 2>&1 || :

# ---------------------------------------------------------------------------------------------------- files

%files agent
%doc %{pgvip_home}/README.md
%dir %{pgvip_home}
%{pgvip_home}/pgvip-agent.jar
%{_bindir}/pgvip-agent
%dir %{_sysconfdir}/pgvip
%config(noreplace) %attr(0600,postgres,postgres) %{_sysconfdir}/pgvip/agent.properties
%config(noreplace) %attr(0440,root,root) %{_sysconfdir}/sudoers.d/pgvip
%attr(0700,postgres,postgres) %dir /var/lib/pgvip
%{_unitdir}/pgvip-agent.service

%files watcher
%doc %{pgvip_home}/README.md
%dir %{pgvip_home}
%{pgvip_home}/pgvip-watcher.jar
%{_bindir}/pgvip-watcher
%{_bindir}/pgvip-make-certs
%dir %{_sysconfdir}/pgvip
%config(noreplace) %attr(0640,root,pgvip) %{_sysconfdir}/pgvip/watcher.yml
%attr(0700,pgvip,pgvip) %dir /var/lib/pgvip-watcher
%{_unitdir}/pgvip-watcher.service

%changelog
* Tue Oct 28 2026 pgVIP <pgvip@localhost> - 0.1.0-1
- First packages: agent and watcher for Rocky / RHEL 8 and 9
