package com.bisoft.bfm.watcher.setup;

import com.bisoft.bfm.common.tls.Keytool;
import com.bisoft.bfm.core.notify.SecretBox;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * {@code bfm-watcher setup}: the first start of a watcher without hand-editing. Asks for the watcher id, data
 * directory, console port and addresses, the first admin, how agent certificates are made, and the Raft peers; then
 * writes {@code watcher.yml}, {@code watcher.env} and the certificates, and enables the service.
 *
 * <p>With the watcher CA (the default) it makes the CA, the console's certificate and, for a Raft group, the other
 * watchers' certificates; the CA moves into the shared state on the first start, so database hosts can enroll. With
 * {@code files} it uses key stores from {@code bfm-make-certs}.
 */
public final class WatcherSetup {

    private static final String USAGE = """
            bfm-watcher setup [--answers FILE] [--dry-run] [--yes] [--etc DIR] [--no-systemd]
              --answers FILE  answers for every question (keys: watcher-id, data-dir, time-zone, port, names,
                              admin, admin-password, certificates=ca|files, keystore, truststore, peers, proceed)
              --etc DIR       write the settings there instead of /etc/bfm (a lab)""";

    private final Properties answers;
    private final BufferedReader in;
    private final PrintStream out;
    private final boolean dryRun;
    private final boolean yes;
    private final boolean systemd;
    private final Path etc;

    WatcherSetup(Properties answers, BufferedReader in, PrintStream out, boolean dryRun, boolean yes, boolean systemd,
                 Path etc) {
        this.answers = answers;
        this.in = in;
        this.out = out;
        this.dryRun = dryRun;
        this.yes = yes;
        this.systemd = systemd;
        this.etc = etc;
    }

    public static int main(String[] args) {
        Properties answers = new Properties();
        boolean dry = false;
        boolean yes = false;
        boolean systemd = true;
        Path etc = Path.of("/etc/bfm");
        String file = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dry = true;
                case "--yes" -> yes = true;
                case "--no-systemd" -> systemd = false;
                case "--answers" -> file = args[++i];
                case "--etc" -> etc = Path.of(args[++i]);
                case "--help" -> {
                    System.out.println(USAGE);
                    return 0;
                }
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.err.println(USAGE);
                    return 64;
                }
            }
        }
        if (file != null) {
            try (InputStream s = Files.newInputStream(Path.of(file))) {
                answers.load(s);
            } catch (IOException e) {
                System.err.println("cannot read " + file + ": " + e.getMessage());
                return 66;
            }
        }
        BufferedReader in = file == null ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                : null;
        try {
            return new WatcherSetup(answers, in, System.out, dry, yes, systemd, etc).run();
        } catch (IllegalStateException | IllegalArgumentException | IOException | Keytool.KeytoolException e) {
            System.out.println();
            System.out.println(" ✖ " + e.getMessage());
            return 1;
        }
    }

    int run() throws IOException {
        out.println();
        out.println(" BFM watcher setup" + (dryRun ? "   (dry run: nothing is changed)" : ""));
        if (Files.exists(etc.resolve("watcher.yml")) && !confirm("overwrite", etc.resolve("watcher.yml")
                + " exists. Replace it (the old one is kept as .bak)?", false)) {
            out.println(" Nothing was changed.");
            return 2;
        }

        out.println();
        out.println(" 1 · This watcher");
        String id = ask("watcher-id", "Watcher id:", "w1");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("watcher ids are letters, digits, '.', '_' and '-'");
        }
        Path data = Path.of(ask("data-dir", "Data directory:", "/var/lib/bfm-watcher"));
        String zone = ask("time-zone", "Time zone for the console and mails:", ZoneId.systemDefault().getId());
        ZoneId.of(zone);
        int port = Integer.parseInt(ask("port", "Console port:", "8443"));
        String names = ask("names", "Names and addresses operators use for the console (comma separated):",
                String.join(",", localNames()));

        out.println();
        out.println(" 2 · First admin");
        String admin = ask("admin", "User name:", "admin");
        String password = password();

        out.println();
        out.println(" 3 · Certificates");
        out.println("   ca:    this watcher group keeps a CA; database hosts enroll with one-time tokens (recommended)");
        out.println("   files: key stores made with bfm-make-certs (the CA stays offline)");
        String mode = ask("certificates", "Certificates (ca/files):", "ca");

        out.println();
        out.println(" 4 · Watchers");
        String peers = ask("peers", "Raft peers as id=address, this one included (empty: this watcher alone):", "");
        Map<String, String> peerMap = new LinkedHashMap<>();
        for (String p : peers.split(",")) {
            if (p.isBlank()) {
                continue;
            }
            String[] kv = p.trim().split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("peers are id=address: " + p);
            }
            peerMap.put(kv[0].trim(), kv[1].trim());
        }
        if (!peerMap.isEmpty() && !peerMap.containsKey(id)) {
            throw new IllegalArgumentException("the peers must include this watcher (" + id + ")");
        }
        if (!peerMap.isEmpty() && peerMap.size() < 3) {
            throw new IllegalArgumentException("a Raft group needs three (or five) watchers");
        }

        Path keystore = etc.resolve(id + ".p12");
        Path keyPass = etc.resolve(id + ".pass");
        Path trust = etc.resolve("ca.p12");
        Path trustPass = etc.resolve("ca.pass");
        if (mode.equals("files")) {
            keystore = Path.of(ask("keystore", "This watcher's key store (bfm-make-certs NAME.p12):",
                    keystore.toString()));
            keyPass = Path.of(keystore.toString().replaceAll("\\.p12$", ".pass"));
            trust = Path.of(ask("truststore", "The CA store (ca.p12):", trust.toString()));
            trustPass = Path.of(trust.toString().replaceAll("\\.p12$", ".pass"));
        } else if (!mode.equals("ca")) {
            throw new IllegalArgumentException("certificates are 'ca' or 'files'");
        }

        String yml = yml(id, data, zone, port, keystore, keyPass, trust, trustPass, admin,
                PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password), peerMap);

        out.println();
        out.println(" 5 · Review");
        out.println("   This will:");
        out.println("     write   " + etc.resolve("watcher.yml") + "   (watcher " + id + ", console on port " + port + ")");
        out.println("     write   " + etc.resolve("watcher.env") + "   (the console key store's password)");
        if (mode.equals("ca")) {
            out.println("     create  a CA; " + keystore + " for this watcher (" + names + ")");
            for (String p : peerMap.keySet()) {
                if (!p.equals(id)) {
                    out.println("     create  " + etc.resolve(p + ".p12") + " for watcher " + p + " (copy it there)");
                }
            }
            out.println("     write   " + trust + "   (the CA every watcher and agent trusts)");
            out.println("     write   " + data.resolve("ca-import.p12") + "   (moves into the shared state on the"
                    + " first start)");
        }
        out.println("     create  " + data.resolve("secret.key") + "   (encrypts stored passwords; back it up)");
        out.println("     " + (systemd ? "run    " : "(skip) ") + " systemctl enable --now bfm-watcher");
        if (dryRun) {
            out.println();
            out.println(" Dry run: nothing was changed. watcher.yml would be:");
            out.println(yml.replaceAll("password-hash: \".*\"", "password-hash: \"(hidden)\""));
            return 0;
        }
        if (!confirm("proceed", "Proceed?", false)) {
            out.println(" Nothing was changed.");
            return 2;
        }

        Files.createDirectories(etc);
        Files.createDirectories(data);
        Path work = Files.createTempDirectory("bfm-setup", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        String consolePass;
        List<String> copies = new ArrayList<>();
        try {
            if (mode.equals("ca")) {
                String caPass = Keytool.randomPassword();
                Path ca = work.resolve("ca.p12");
                Keytool.createCa(ca, caPass, "BFM CA");
                String caPem = Keytool.exportCertificate(ca, caPass, "bfm-ca");
                String trustPw = Keytool.randomPassword();
                Path trustTmp = work.resolve("trust.p12");
                Keytool.importTrusted(trustTmp, trustPw, "bfm-ca", caPem, work);
                write(trust, Files.readAllBytes(trustTmp), "640");
                write(trustPass, (trustPw + "\n").getBytes(StandardCharsets.UTF_8), "640");
                consolePass = issue(work, ca, caPass, caPem, id, names, keystore, keyPass);
                for (Map.Entry<String, String> p : peerMap.entrySet()) {
                    if (!p.getKey().equals(id)) {
                        issue(work, ca, caPass, caPem, p.getKey(), p.getValue(), etc.resolve(p.getKey() + ".p12"),
                                etc.resolve(p.getKey() + ".pass"));
                        copies.add(p.getKey());
                    }
                }
                write(data.resolve("ca-import.p12"), Files.readAllBytes(ca), "600");
                write(data.resolve("ca-import.pass"), (caPass + "\n").getBytes(StandardCharsets.UTF_8), "600");
            } else {
                consolePass = Files.readString(keyPass, StandardCharsets.UTF_8).trim();
            }
        } finally {
            try (var s = Files.walk(work)) {
                for (Path p : s.sorted(Collections.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        SecretBox.fromKeyFile(data.resolve("secret.key"));
        write(etc.resolve("watcher.yml"), yml.getBytes(StandardCharsets.UTF_8), "640");
        write(etc.resolve("watcher.env"), ("BFM_KEYSTORE_PASSWORD=" + consolePass + "\n")
                .getBytes(StandardCharsets.UTF_8), "640");
        owner(data, "bfm");
        if (systemd) {
            Process p = new ProcessBuilder("systemctl", "enable", "--now", "bfm-watcher").inheritIO().start();
            try {
                if (p.waitFor() != 0) {
                    out.println("   ! systemctl enable --now bfm-watcher failed; start it by hand");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        out.println();
        out.println(" 6 · Done");
        String first = names.split(",")[0].trim();
        out.println("   Console: https://" + first + ":" + port + "   user " + admin);
        out.println("   Its certificate: " + Keytool.fingerprint(Keytool.firstCertificate(keystore,
                consolePass.toCharArray())) + " (browsers trust it once " + trust.getParent().resolve("ca.p12")
                + "'s CA is trusted; the enrollment commands pin it)");
        if (!copies.isEmpty()) {
            out.println("   Copy to each other watcher, then run 'bfm-watcher setup' there with certificates=files:");
            for (String p : copies) {
                out.println("     scp " + etc.resolve(p + ".p12") + " " + etc.resolve(p + ".pass") + " " + trust + " "
                        + trustPass + " " + data.resolve("secret.key") + " root@" + peerMap.get(p) + ":/tmp/");
            }
        }
        out.println("   Back up " + data.resolve("secret.key") + " and keep the CA key safe: it lives in the shared"
                + " state, encrypted with secret.key.");
        return 0;
    }

    /** A key store for a watcher: its key, its certificate signed by the CA (console and agent client), the CA. */
    private String issue(Path work, Path ca, String caPass, String caPem, String alias, String names, Path store,
                         Path pass) throws IOException {
        String pw = Keytool.randomPassword();
        Path tmp = work.resolve(alias + ".p12");
        Keytool.createKeyPair(tmp, pw, alias, alias);
        String csr = Keytool.certificateRequest(tmp, pw, alias);
        String leaf = Keytool.sign(ca, caPass, csr, altNames(names), alias, work);
        Keytool.importTrusted(tmp, pw, "bfm-ca", caPem, work);
        Keytool.importChain(tmp, pw, alias, leaf.trim() + "\n" + caPem, work);
        write(store, Files.readAllBytes(tmp), "640");
        write(pass, (pw + "\n").getBytes(StandardCharsets.UTF_8), "640");
        return pw;
    }

    static String altNames(String names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names.split(",")) {
            String v = n.trim();
            if (v.isEmpty() || !v.matches("[A-Za-z0-9.:_-]+")) {
                continue;
            }
            out.add((v.matches("[0-9.]+") || v.contains(":") ? "IP:" : "DNS:") + v);
        }
        return String.join(",", out);
    }

    String yml(String id, Path data, String zone, int port, Path keystore, Path keyPass, Path trust, Path trustPass,
               String admin, String hash, Map<String, String> peers) {
        StringBuilder b = new StringBuilder();
        b.append("# Written by bfm-watcher setup. Every setting: packaging/watcher.yml.example\n");
        b.append("server:\n  port: ").append(port).append('\n');
        b.append("  ssl:\n    key-store: file:").append(keystore).append('\n');
        b.append("    key-store-type: PKCS12\n    key-store-password: ${BFM_KEYSTORE_PASSWORD}\n\n");
        b.append("bfm:\n  watcher-id: ").append(id).append('\n');
        b.append("  data-dir: ").append(data).append('\n');
        b.append("  time-zone: ").append(zone).append("\n\n");
        b.append("  # Clusters: add them under clusters (see packaging/watcher.yml.example).\n");
        b.append("  clusters: []\n\n");
        b.append("  agent:\n    tls:\n      enabled: true\n");
        b.append("      keystore: ").append(keystore).append('\n');
        b.append("      keystore-password-file: ").append(keyPass).append('\n');
        b.append("      truststore: ").append(trust).append('\n');
        b.append("      truststore-password-file: ").append(trustPass).append("\n\n");
        if (!peers.isEmpty()) {
            b.append("  raft:\n    enabled: true\n    tls: true\n    group-name: bfm\n    peers:\n");
            for (Map.Entry<String, String> p : peers.entrySet()) {
                b.append("      - { id: ").append(p.getKey()).append(", host: ").append(p.getValue())
                        .append(", port: 9870, url: https://").append(p.getValue()).append(':').append(port)
                        .append(" }\n");
            }
            b.append('\n');
        }
        b.append("  users:\n    - name: ").append(admin).append("\n      password-hash: \"").append(hash)
                .append("\"\n      role: ADMIN\n");
        return b.toString();
    }

    private void write(Path file, byte[] content, String mode) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(file, content);
        StringBuilder rwx = new StringBuilder();
        for (char c : mode.toCharArray()) {
            int d = c - '0';
            rwx.append((d & 4) != 0 ? 'r' : '-').append((d & 2) != 0 ? 'w' : '-').append((d & 1) != 0 ? 'x' : '-');
        }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(rwx.toString()));
        owner(file, "bfm");
    }

    /** Group (and for the data directory, owner) bfm, when that user exists; a lab skips it. */
    private static void owner(Path p, String name) {
        try {
            var lookup = p.getFileSystem().getUserPrincipalLookupService();
            GroupPrincipal g = lookup.lookupPrincipalByGroupName(name);
            PosixFileAttributeView v = Files.getFileAttributeView(p, PosixFileAttributeView.class);
            v.setGroup(g);
            if (Files.isDirectory(p)) {
                v.setOwner(lookup.lookupPrincipalByName(name));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // no bfm user here (a lab): the files stay the caller's
        }
    }

    private static List<String> localNames() {
        List<String> out = new ArrayList<>();
        try {
            out.add(InetAddress.getLocalHost().getHostName());
            for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (i.isLoopback() || !i.isUp()) {
                    continue;
                }
                for (InetAddress a : Collections.list(i.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (IOException e) {
            out.add("localhost");
        }
        return out;
    }

    private String password() {
        String a = answers.getProperty("admin-password");
        if (a != null) {
            if (a.length() < 8) {
                throw new IllegalArgumentException("use at least 8 characters for the admin password");
            }
            return a;
        }
        Console c = System.console();
        if (c == null) {
            throw new IllegalStateException("no terminal to ask the admin password: give admin-password in --answers");
        }
        char[] p1 = c.readPassword(" Password (8 or more characters): ");
        char[] p2 = c.readPassword(" Again: ");
        if (p1 == null || p2 == null || !new String(p1).equals(new String(p2)) || p1.length < 8) {
            throw new IllegalArgumentException("the passwords differ or are shorter than 8 characters");
        }
        return new String(p1);
    }

    private String ask(String key, String question, String dflt) {
        String a = answers.getProperty(key);
        if (a != null) {
            out.println(" " + question + " " + a + "   (" + key + " from the answers)");
            return a.trim();
        }
        if (in == null) {
            if (dflt != null) {
                out.println(" " + question + " " + dflt);
                return dflt;
            }
            throw new IllegalStateException("no answer for " + key);
        }
        out.print(" " + question + (dflt == null || dflt.isEmpty() ? " " : " [" + dflt + "] "));
        out.flush();
        try {
            String line = in.readLine();
            return line == null || line.isBlank() ? dflt : line.trim();
        } catch (IOException e) {
            return dflt;
        }
    }

    private boolean confirm(String key, String question, boolean dflt) {
        String a = answers.getProperty(key);
        if (a != null) {
            return a.trim().equalsIgnoreCase("yes") || a.trim().equalsIgnoreCase("true");
        }
        if (yes) {
            return true;
        }
        if (in == null) {
            return dflt;
        }
        out.print(" " + question + (dflt ? " [Y/n] " : " [y/N] "));
        out.flush();
        try {
            String line = in.readLine();
            return line == null || line.isBlank() ? dflt : line.trim().toLowerCase().startsWith("y");
        } catch (IOException e) {
            return dflt;
        }
    }
}
