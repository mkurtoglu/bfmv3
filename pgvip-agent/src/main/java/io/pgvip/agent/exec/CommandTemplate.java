package io.pgvip.agent.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a command template such as {@code sudo -n /usr/sbin/ip addr add {vip}/{prefix} dev {iface}} into an
 * argument vector. The template is split on whitespace before placeholders are filled, so a value can never
 * add extra arguments.
 */
public final class CommandTemplate {

    private CommandTemplate() {
    }

    public static List<String> expand(String template, Map<String, String> values) {
        List<String> argv = new ArrayList<>();
        for (String token : template.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            String out = token;
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
            if (out.contains("{") && out.contains("}") && out.matches(".*\\{[a-z]+}.*")) {
                throw new IllegalArgumentException("Unfilled placeholder in '" + token + "' of template: " + template);
            }
            argv.add(out);
        }
        return argv;
    }
}
