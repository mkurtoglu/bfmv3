package com.bisoft.bfm.agent.setup;

/** The sudo rule of one agent instance: exactly its VIP, on exactly its interface. */
public final class Sudoers {

    private Sudoers() {
    }

    public static String rule(String vip, String prefix, String iface) {
        if (vip == null || !vip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            throw new Prompter.SetupException("the VIP must be an IPv4 address: " + vip);
        }
        if (prefix == null || !prefix.matches("\\d{1,2}") || Integer.parseInt(prefix) < 1
                || Integer.parseInt(prefix) > 32) {
            throw new Prompter.SetupException("the prefix length must be 1 to 32: " + prefix);
        }
        if (iface == null || !iface.matches("[A-Za-z0-9_.:-]{1,15}")) {
            throw new Prompter.SetupException("not an interface name: " + iface);
        }
        String a = vip + "/" + prefix;
        String pad = " ".repeat("postgres ALL=(root) NOPASSWD: ".length());
        return "# Written by minipgagent setup. This agent instance may move exactly this address on exactly this"
                + " interface.\n"
                + "postgres ALL=(root) NOPASSWD: /usr/sbin/ip addr add " + a + " dev " + iface + ", \\\n"
                + pad + "/usr/sbin/ip addr del " + a + " dev " + iface + ", \\\n"
                + pad + "/usr/sbin/arping -D -q -c 2 -w 3 -I " + iface + " " + vip + ", \\\n"
                + pad + "/usr/sbin/arping -U -q -c 3 -I " + iface + " " + vip + "\n";
    }
}
