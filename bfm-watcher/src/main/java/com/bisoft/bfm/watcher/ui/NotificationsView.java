package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.core.event.MailRule;
import com.bisoft.bfm.core.notify.MailMessage;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.watcher.service.NotificationService;
import jakarta.annotation.security.PermitAll;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SMTP server, recipients, alert thresholds and per-event mail rules. */
@Route(value = "notifications", layout = MainLayout.class)
@PageTitle("Mail notifications · BFM")
@PermitAll
public class NotificationsView extends Div {

    private static final Map<String, Long> THROTTLES = new LinkedHashMap<>();

    static {
        THROTTLES.put("None", 0L);
        THROTTLES.put("5 min", 5 * 60_000L);
        THROTTLES.put("15 min", 15 * 60_000L);
        THROTTLES.put("1 h", 60 * 60_000L);
        THROTTLES.put("4 h", 4 * 60 * 60_000L);
        THROTTLES.put("24 h", 24 * 60 * 60_000L);
    }

    private final NotificationService service;
    private final boolean admin;
    private final String user;

    private final TextField host = new TextField("Host");
    private final IntegerField port = new IntegerField("Port");
    private final Select<NotificationSettings.Security> security = new Select<>();
    private final TextField smtpUser = new TextField("User name");
    private final PasswordField password = new PasswordField("Password");
    private final EmailField from = new EmailField("From address");
    private final List<NotificationSettings.Recipient> recipients = new ArrayList<>();
    private final Grid<NotificationSettings.Recipient> recipientGrid = new Grid<>();
    private final NumberField lagKb = new NumberField("Lag alert (KB)");
    private final NumberField slotGb = new NumberField("Slot WAL alert (GB)");
    private final Map<MailRule, NotificationSettings.Rule> rules = new EnumMap<>(MailRule.class);
    private final Grid<MailRule> ruleGrid = new Grid<>();
    private final Pre preview = new Pre();

    public NotificationsView(NotificationService service, AuthenticationContext auth) {
        this.service = service;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        addClassName("pg-page");
        H1 title = new H1("Mail notifications");
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", "Settings"), title));
        if (!admin) {
            add(Ui.banner("You can look at the mail settings; only admins can change them.", Ui.Tone.INFO));
        }
        add(Ui.div("pg-grid-auto", smtpCard(), recipientsCard()), rulesCard(), previewCard());
        load();
    }

    private Div smtpCard() {
        Div card = Ui.card("SMTP server");
        security.setLabel("Security");
        security.setItems(NotificationSettings.Security.values());
        security.setItemLabelGenerator(s -> switch (s) {
            case STARTTLS -> "STARTTLS";
            case SMTPS -> "SMTPS (TLS)";
            case NONE -> "None";
        });
        host.setPlaceholder("smtp.example.com");
        port.setMin(1);
        port.setMax(65535);
        password.setPlaceholder("Stored encrypted");
        FormLayout form = new FormLayout(host, port, security, smtpUser, password, from);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("360px", 2));
        form.setColspan(host, 2);
        form.setColspan(from, 2);
        Button save = new Button("Save", e -> {
            if (host.isEmpty()) {
                host.setInvalid(true);
                host.setErrorMessage("Enter the SMTP host first.");
                return;
            }
            if (Ui.attempt(() -> service.saveSmtp(new NotificationService.SmtpChange(host.getValue(),
                    port.getValue() == null ? 587 : port.getValue(), security.getValue(), smtpUser.getValue(),
                    password.isEmpty() ? null : password.getValue(), from.getValue()), user))) {
                password.clear();
                Ui.ok("SMTP settings saved");
                load();
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button test = new Button("Send test mail", e -> {
            try {
                List<String> to = service.sendTest(user);
                Ui.ok("Test mail accepted by " + host.getValue() + " for " + String.join(", ", to));
            } catch (Exception ex) {
                Ui.error("Test mail failed: " + ex.getMessage());
            }
        });
        save.setEnabled(admin);
        test.setEnabled(admin);
        card.add(form, Ui.div("pg-row", test, save),
                Ui.text("pg-note", "Save first, then send a test mail to every recipient."));
        return card;
    }

    private Div recipientsCard() {
        Div card = Ui.card("Recipients");
        recipientGrid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_COMPACT);
        recipientGrid.setAllRowsVisible(true);
        recipientGrid.addComponentColumn(r -> {
            Div d = new Div(Ui.text(null, r.name()), new Div(Ui.text("pg-note", r.address())));
            d.getChildren().findFirst().ifPresent(c -> c.getElement().setAttribute("style",
                    "font-weight:700;color:var(--pg-ink)"));
            return d;
        }).setHeader("Group").setFlexGrow(1);
        recipientGrid.addComponentColumn(r -> Ui.chip(switch (r.filter()) {
            case ALL -> "All events";
            case CRITICAL -> "Critical only";
            case ROLE_CHANGES -> "Role changes";
        }, switch (r.filter()) {
            case ALL -> Ui.Tone.OK;
            case CRITICAL -> Ui.Tone.CRIT;
            case ROLE_CHANGES -> Ui.Tone.INFO;
        })).setHeader("Gets").setAutoWidth(true);
        recipientGrid.addComponentColumn(r -> {
            Button rm = new Button(VaadinIcon.TRASH.create(), e -> {
                recipients.remove(r);
                saveRecipients();
            });
            rm.setAriaLabel("Remove " + r.name());
            rm.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            rm.setEnabled(admin);
            return rm;
        }).setAutoWidth(true).setFlexGrow(0);

        TextField name = new TextField("Group name");
        TextField address = new TextField("Address(es)");
        address.setHelperText("Several addresses: separate with commas");
        Select<NotificationSettings.Filter> filter = new Select<>();
        filter.setLabel("Gets");
        filter.setItems(NotificationSettings.Filter.values());
        filter.setItemLabelGenerator(f -> switch (f) {
            case ALL -> "All events";
            case CRITICAL -> "Critical only";
            case ROLE_CHANGES -> "Role changes";
        });
        filter.setValue(NotificationSettings.Filter.ALL);
        Button add = new Button("Add group", e -> {
            if (address.isEmpty()) {
                address.setInvalid(true);
                address.setErrorMessage("Enter an address");
                return;
            }
            recipients.add(new NotificationSettings.Recipient(name.getValue(), address.getValue(), filter.getValue()));
            if (saveRecipients()) {
                name.clear();
                address.clear();
            }
        });
        add.setEnabled(admin);
        FormLayout form = new FormLayout(name, address, filter);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("420px", 3));

        lagKb.setMin(1);
        slotGb.setMin(0.1);
        slotGb.setStep(0.1);
        Button saveThresholds = new Button("Save thresholds", e -> {
            if (lagKb.getValue() == null || slotGb.getValue() == null) {
                Ui.error("Enter both thresholds");
                return;
            }
            if (Ui.attempt(() -> service.saveThresholds(Math.round(lagKb.getValue() * 1024),
                    Math.round(slotGb.getValue() * 1024 * 1024 * 1024), user))) {
                Ui.ok("Thresholds saved");
            }
        });
        saveThresholds.setEnabled(admin);
        FormLayout thresholds = new FormLayout(lagKb, slotGb);
        thresholds.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));
        recipientGrid.setEmptyStateText("No recipients yet. Add a group below.");
        card.add(recipientGrid, form, Ui.div("pg-row", add), new H2("Alert thresholds"), thresholds,
                Ui.div("pg-row", saveThresholds));
        return card;
    }

    private boolean saveRecipients() {
        if (Ui.attempt(() -> service.saveRecipients(recipients, user))) {
            Ui.ok("Recipients saved");
            load();
            return true;
        }
        load();
        return false;
    }

    private Div rulesCard() {
        Div card = Ui.card(null);
        card.add(Ui.div("pg-spread", new H2("Event rules"),
                Ui.text("pg-note", "The same event is not mailed again within its throttle window")));
        ruleGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_WRAP_CELL_CONTENT);
        ruleGrid.setAllRowsVisible(true);
        ruleGrid.addColumn(MailRule::label).setHeader("Event").setFlexGrow(1);
        ruleGrid.addComponentColumn(r -> Ui.chip(switch (r.severity()) {
            case CRITICAL -> "Critical";
            case WARNING -> "Warning";
            case INFO -> "Info";
        }, Ui.tone(r.severity()))).setHeader("Severity").setAutoWidth(true);
        ruleGrid.addComponentColumn(r -> {
            Select<String> s = new Select<>();
            s.setItems(THROTTLES.keySet());
            s.setValue(label(rules.get(r).throttleMs()));
            s.setAriaLabel("Throttle for " + r.label());
            s.setReadOnly(!admin);
            s.addValueChangeListener(e -> rules.put(r, new NotificationSettings.Rule(rules.get(r).enabled(),
                    THROTTLES.get(e.getValue()))));
            return s;
        }).setHeader("Throttle").setAutoWidth(true);
        ruleGrid.addComponentColumn(r -> {
            Checkbox c = new Checkbox(rules.get(r).enabled());
            c.setAriaLabel("Mail for " + r.label());
            c.setReadOnly(!admin);
            c.addValueChangeListener(e -> rules.put(r, new NotificationSettings.Rule(e.getValue(),
                    rules.get(r).throttleMs())));
            return c;
        }).setHeader("Mail").setAutoWidth(true);
        Button save = new Button("Save rules", e -> {
            if (Ui.attempt(() -> service.saveRules(Map.copyOf(rules), user))) {
                Ui.ok("Rules saved");
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        save.setEnabled(admin);
        card.add(ruleGrid, Ui.div("pg-row", save));
        return card;
    }

    private static String label(long ms) {
        for (Map.Entry<String, Long> e : THROTTLES.entrySet()) {
            if (e.getValue() == ms) {
                return e.getKey();
            }
        }
        return ms / 60_000 + " min";
    }

    private Div previewCard() {
        Div card = Ui.card("Mail preview · automatic failover");
        preview.addClassName("pg-log");
        preview.getStyle().set("background", "#F7F9F4").set("color", "var(--pg-body)")
                .set("border", "1px solid var(--pg-border)");
        card.add(preview);
        return card;
    }

    private void load() {
        NotificationService.SettingsView v = service.view();
        host.setValue(v.smtp().host() == null ? "" : v.smtp().host());
        port.setValue(v.smtp().port());
        security.setValue(v.smtp().security());
        smtpUser.setValue(v.smtp().user() == null ? "" : v.smtp().user());
        password.setPlaceholder(v.smtp().passwordSet() ? "Stored encrypted (leave empty to keep)" : "Not set");
        from.setValue(v.smtp().from() == null ? "" : v.smtp().from());
        recipients.clear();
        recipients.addAll(v.recipients());
        recipientGrid.setItems(recipients);
        lagKb.setValue(v.lagAlertBytes() / 1024.0);
        slotGb.setValue(Math.round(v.slotAlertBytes() * 10.0 / (1024 * 1024 * 1024)) / 10.0);
        rules.clear();
        rules.putAll(v.rules());
        ruleGrid.setItems(MailRule.values());
        MailMessage m = service.preview();
        preview.setText("From     " + (v.smtp().from() == null ? "(not set)" : v.smtp().from()) + "\nTo       "
                + (m.to().isEmpty() ? "(no recipients)" : String.join(", ", m.to())) + "\nSubject  " + m.subject()
                + "\n\n" + m.body());
    }
}
