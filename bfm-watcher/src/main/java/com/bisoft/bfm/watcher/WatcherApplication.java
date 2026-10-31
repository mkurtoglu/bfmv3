package com.bisoft.bfm.watcher;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * {@code java -jar bfm-watcher.jar --spring.config.additional-location=/etc/bfm/watcher.yml}
 *
 * <p>{@code java -jar bfm-watcher.jar hash-password} prints a password hash for {@code bfm.users};
 * {@code java -jar bfm-watcher.jar setup} writes a first configuration.
 */
@SpringBootApplication
@ColorScheme(ColorScheme.Value.LIGHT_DARK)
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("styles.css")
public class WatcherApplication implements AppShellConfigurator {

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "hash-password".equals(args[0])) {
            hashPassword();
            return;
        }
        if (args.length > 0 && "setup".equals(args[0])) {
            System.exit(com.bisoft.bfm.watcher.setup.WatcherSetup.main(java.util.Arrays.copyOfRange(args, 1,
                    args.length)));
            return;
        }
        SpringApplication.run(WatcherApplication.class, args);
    }

    @Override
    public void configurePage(AppShellSettings settings) {
        settings.setPageTitle("BFM");
        settings.addFavIcon("icon", "icons/bfm.svg", "32x32");
    }

    private static void hashPassword() throws IOException {
        Console console = System.console();
        String pw;
        if (console != null) {
            char[] a = console.readPassword("Password: ");
            char[] b = console.readPassword("Again:    ");
            if (a == null || b == null || !new String(a).equals(new String(b))) {
                System.err.println("Passwords do not match");
                System.exit(1);
                return;
            }
            pw = new String(a);
        } else {
            pw = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
        if (pw == null || pw.length() < 8) {
            System.err.println("Use at least 8 characters");
            System.exit(1);
            return;
        }
        System.out.println(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(pw));
    }
}
