package com.bisoft.bfm.agent.setup;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Interfaces and their IPv4 networks, from {@code ip -o -4 addr show}; subnet and free-port checks. */
public final class Network {

    private static final Pattern ADDR = Pattern.compile("^\\d+:\\s+(\\S+?)(?:@\\S+)?\\s+inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)"
            + "/(\\d+)");

    private Network() {
    }

    public record Iface(String name, String address, int prefix) {
        public boolean contains(String ip) {
            try {
                return sameNetwork(address, ip, prefix);
            } catch (UnknownHostException e) {
                return false;
            }
        }
    }

    public static List<Iface> parse(String ipAddrOutput) {
        List<Iface> out = new ArrayList<>();
        for (String line : ipAddrOutput.split("\n")) {
            Matcher m = ADDR.matcher(line.trim());
            if (m.find() && !m.group(1).equals("lo")) {
                out.add(new Iface(m.group(1), m.group(2), Integer.parseInt(m.group(3))));
            }
        }
        return out;
    }

    /**
     * The default gateway on {@code iface} from {@code ip -4 route show default} ("default via 10.0.0.1 dev eth0
     * ..."), or null: a gateway on another interface is no neighbour of the VIP's (cloud hosts route the default over
     * a public interface and carry the VIP's subnet on a private one).
     */
    public static String defaultGateway(String ipRouteOutput, String iface) {
        Matcher m = Pattern.compile("^default via (\\d{1,3}(?:\\.\\d{1,3}){3}) dev (\\S+)", Pattern.MULTILINE)
                .matcher(ipRouteOutput == null ? "" : ipRouteOutput);
        while (m.find()) {
            if (m.group(2).equals(iface)) {
                return m.group(1);
            }
        }
        return null;
    }

    static boolean sameNetwork(String a, String b, int prefix) throws UnknownHostException {
        byte[] x = InetAddress.getByName(a).getAddress();
        byte[] y = InetAddress.getByName(b).getAddress();
        if (x.length != 4 || y.length != 4) {
            return false;
        }
        int ix = ((x[0] & 0xff) << 24) | ((x[1] & 0xff) << 16) | ((x[2] & 0xff) << 8) | (x[3] & 0xff);
        int iy = ((y[0] & 0xff) << 24) | ((y[1] & 0xff) << 16) | ((y[2] & 0xff) << 8) | (y[3] & 0xff);
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        return (ix & mask) == (iy & mask);
    }

    /** True when nothing listens on {@code port} on any address of this host. */
    public static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
