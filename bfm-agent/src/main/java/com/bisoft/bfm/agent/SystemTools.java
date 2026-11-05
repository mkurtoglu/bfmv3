package com.bisoft.bfm.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Where the host keeps the programs the agent runs through sudo. The sudo rule names a program by its full path and
 * the agent must call it by exactly that path, so both come from here: RHEL and Rocky install {@code arping} in
 * {@code /usr/sbin}, Debian and Ubuntu in {@code /usr/bin}.
 */
public final class SystemTools {

    private static final List<String> DIRS = List.of("/usr/sbin", "/usr/bin", "/sbin", "/bin");

    private SystemTools() {
    }

    /** {@code ip} from iproute. */
    public static String ip() {
        return find("ip");
    }

    /** {@code arping} from iputils. */
    public static String arping() {
        return find("arping");
    }

    /** The first executable {@code name} in the usual directories, or {@code /usr/sbin/name} when there is none. */
    static String find(String name) {
        return find(name, DIRS);
    }

    static String find(String name, List<String> dirs) {
        for (String d : dirs) {
            Path p = Path.of(d, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return "/usr/sbin/" + name;
    }
}
