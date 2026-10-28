package io.pgvip.core.notify;

import io.pgvip.common.json.Json;
import io.pgvip.common.json.JsonMap;
import io.pgvip.common.json.JsonWritable;
import io.pgvip.core.event.MailRule;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Mail settings, edited on the notifications screen and kept in the shared store so every watcher mails the same.
 *
 * @param smtp           SMTP server
 * @param recipients     recipient groups
 * @param rules          per rule: on/off and throttle window
 * @param lagAlertBytes  replica lag that raises a warning
 * @param slotAlertBytes WAL held by an inactive slot that raises a warning
 */
public record NotificationSettings(Smtp smtp, List<Recipient> recipients, Map<MailRule, Rule> rules,
                                   long lagAlertBytes, long slotAlertBytes) implements JsonWritable {

    public static final String KEY = "notifications";

    public enum Security { STARTTLS, SMTPS, NONE }

    /** Which events a recipient group gets. */
    public enum Filter { ALL, CRITICAL, ROLE_CHANGES }

    /**
     * @param passwordSecret the password encrypted with the watcher key ({@link SecretBox}), null when none
     */
    public record Smtp(String host, int port, Security security, String user, String passwordSecret, String from) {
        public boolean configured() {
            return host != null && !host.isBlank() && from != null && !from.isBlank();
        }
    }

    public record Recipient(String name, String address, Filter filter) {
    }

    public record Rule(boolean enabled, long throttleMs) {
    }

    public NotificationSettings {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        Map<MailRule, Rule> full = new EnumMap<>(MailRule.class);
        for (MailRule r : MailRule.values()) {
            Rule given = rules == null ? null : rules.get(r);
            full.put(r, given == null ? new Rule(r.defaultEnabled(), r.defaultThrottleMs()) : given);
        }
        rules = Map.copyOf(full);
        if (smtp == null) {
            smtp = new Smtp(null, 587, Security.STARTTLS, null, null, null);
        }
    }

    public static NotificationSettings defaults() {
        return new NotificationSettings(null, List.of(), null, 512L * 1024, 1024L * 1024 * 1024);
    }

    public Rule rule(MailRule r) {
        return rules.get(r);
    }

    public NotificationSettings withSmtp(Smtp s) {
        return new NotificationSettings(s, recipients, rules, lagAlertBytes, slotAlertBytes);
    }

    public NotificationSettings withRecipients(List<Recipient> r) {
        return new NotificationSettings(smtp, r, rules, lagAlertBytes, slotAlertBytes);
    }

    public NotificationSettings withRules(Map<MailRule, Rule> r) {
        return new NotificationSettings(smtp, recipients, r, lagAlertBytes, slotAlertBytes);
    }

    public NotificationSettings withThresholds(long lag, long slot) {
        return new NotificationSettings(smtp, recipients, rules, lag, slot);
    }

    @Override
    public Object toJsonValue() {
        Map<String, Object> rs = new java.util.LinkedHashMap<>();
        rules.forEach((k, v) -> rs.put(k.name(), JsonMap.create().put("enabled", v.enabled())
                .put("throttleMs", v.throttleMs()).raw()));
        List<Object> rc = new ArrayList<>();
        for (Recipient r : recipients) {
            rc.add(JsonMap.create().put("name", r.name()).put("address", r.address()).put("filter", r.filter()).raw());
        }
        return JsonMap.create()
                .put("smtp", JsonMap.create()
                        .put("host", smtp.host())
                        .put("port", smtp.port())
                        .put("security", smtp.security())
                        .put("user", smtp.user())
                        .put("passwordSecret", smtp.passwordSecret())
                        .put("from", smtp.from()).raw())
                .put("recipients", rc)
                .put("rules", rs)
                .put("lagAlertBytes", lagAlertBytes)
                .put("slotAlertBytes", slotAlertBytes)
                .raw();
    }

    public static NotificationSettings fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        JsonMap s = m.obj("smtp");
        Smtp smtp = s == null ? null : new Smtp(s.str("host"), s.integer("port", 587),
                s.enumValue("security", Security.class, Security.STARTTLS), s.str("user"), s.str("passwordSecret"),
                s.str("from"));
        List<Recipient> rc = m.objList("recipients", r -> new Recipient(r.str("name"), r.str("address"),
                r.enumValue("filter", Filter.class, Filter.ALL)));
        Map<MailRule, Rule> rules = new EnumMap<>(MailRule.class);
        JsonMap rs = m.obj("rules");
        if (rs != null) {
            for (MailRule r : MailRule.values()) {
                JsonMap e = rs.obj(r.name());
                if (e != null) {
                    rules.put(r, new Rule(e.bool("enabled", r.defaultEnabled()), e.lng("throttleMs", r.defaultThrottleMs())));
                }
            }
        }
        return new NotificationSettings(smtp, rc, rules, m.lng("lagAlertBytes", 512L * 1024),
                m.lng("slotAlertBytes", 1024L * 1024 * 1024));
    }
}
