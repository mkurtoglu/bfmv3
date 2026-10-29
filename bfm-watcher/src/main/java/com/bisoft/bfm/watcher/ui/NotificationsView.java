package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeLeaveEvent;
import com.vaadin.flow.router.BeforeLeaveObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.common.Lsn;
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
public class NotificationsView extends Div implements BeforeLeaveObserver {

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
    private final TextField lagAlert = new TextField("Replica lag alert");
    private final TextField slotAlert = new TextField("Inactive slot alert");
    private final Span smtpState = Ui.text("pg-dirty", "");
    private final Span thresholdsState = Ui.text("pg-dirty", "");
    private final Span rulesState = Ui.text("pg-dirty", "");
    private final Button saveSmtp = new Button("Save");
    private final Button testMail = new Button("Send test mail");
    private final Button saveThresholds = new Button("Save thresholds");
    private final Button saveRules = new Button("Save rules");
    private boolean smtpDirty;
    private boolean thresholdsDirty;
    private boolean rulesDirty;
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
        saveSmtp.addClickListener(e -> {
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
        saveSmtp.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        testMail.addClickListener(e -> {
            try {
                List<String> to = service.sendTest(user);
                Ui.ok("Test mail accepted by " + host.getValue() + " for " + String.join(", ", to));
            } catch (Exception ex) {
                Ui.error("Test mail failed: " + ex.getMessage());
            }
        });
        host.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        smtpUser.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        from.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        port.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        security.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        password.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        card.add(form, Ui.div("pg-row", testMail, saveSmtp, smtpState),
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

        lagAlert.setHelperText("Replay lag that raises a warning, e.g. 512 KB or 16 MB");
        slotAlert.setHelperText("WAL an unused slot may hold, e.g. 1 GB");
        lagAlert.addValueChangeListener(e -> thresholdsChanged(e.isFromClient()));
        slotAlert.addValueChangeListener(e -> thresholdsChanged(e.isFromClient()));
        saveThresholds.addClickListener(e -> {
            long lag;
            long slot;
            try {
                lag = SizeText.parse(lagAlert.getValue());
                lagAlert.setInvalid(false);
            } catch (IllegalArgumentException ex) {
                lagAlert.setErrorMessage(ex.getMessage());
                lagAlert.setInvalid(true);
                return;
            }
            try {
                slot = SizeText.parse(slotAlert.getValue());
                slotAlert.setInvalid(false);
            } catch (IllegalArgumentException ex) {
                slotAlert.setErrorMessage(ex.getMessage());
                slotAlert.setInvalid(true);
                return;
            }
            if (Ui.attempt(() -> service.saveThresholds(lag, slot, user))) {
                Ui.ok("Thresholds saved");
                load();
            }
        });
        FormLayout thresholds = new FormLayout(lagAlert, slotAlert);
        thresholds.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("420px", 2));
        recipientGrid.setEmptyStateText("No recipients yet. Add a group below.");
        card.add(recipientGrid, form, Ui.div("pg-row", add), new H2("Alert thresholds"), thresholds,
                Ui.div("pg-row", saveThresholds, thresholdsState));
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
            s.addValueChangeListener(e -> {
                rules.put(r, new NotificationSettings.Rule(rules.get(r).enabled(), THROTTLES.get(e.getValue())));
                rulesChanged(e.isFromClient());
            });
            return s;
        }).setHeader("Throttle").setAutoWidth(true);
        ruleGrid.addComponentColumn(r -> {
            Checkbox c = new Checkbox(rules.get(r).enabled());
            c.setAriaLabel("Mail for " + r.label());
            c.setReadOnly(!admin);
            c.addValueChangeListener(e -> {
                rules.put(r, new NotificationSettings.Rule(e.getValue(), rules.get(r).throttleMs()));
                rulesChanged(e.isFromClient());
            });
            return c;
        }).setHeader("Mail").setAutoWidth(true);
        saveRules.addClickListener(e -> {
            if (Ui.attempt(() -> service.saveRules(Map.copyOf(rules), user))) {
                Ui.ok("Rules saved");
                load();
            }
        });
        saveRules.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        card.add(ruleGrid, Ui.div("pg-row", saveRules, rulesState));
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
        Div card = Ui.card("Mail preview · sample automatic failover");
        preview.addClassNames("pg-log", "pg-preview");
        card.add(Ui.text("pg-note", "Sample data, not a real failover: this is how the mail looks with the settings"
                + " above."), preview);
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
        lagAlert.setValue(Lsn.bytes(v.lagAlertBytes()));
        slotAlert.setValue(Lsn.bytes(v.slotAlertBytes()));
        rules.clear();
        rules.putAll(v.rules());
        ruleGrid.setItems(MailRule.values());
        MailMessage m = service.preview();
        preview.setText("From     " + (v.smtp().from() == null ? "(not set)" : v.smtp().from()) + "\nTo       "
                + (m.to().isEmpty() ? "(no recipients)" : String.join(", ", m.to())) + "\nSubject  " + m.subject()
                + "\n\n" + m.body());
        smtpDirty = false;
        thresholdsDirty = false;
        rulesDirty = false;
        showDirty();
    }

    // ---------------------------------------------------------------- unsaved changes

    private void smtpChanged(boolean fromClient) {
        if (fromClient) {
            smtpDirty = true;
            showDirty();
        }
    }

    private void thresholdsChanged(boolean fromClient) {
        if (fromClient) {
            thresholdsDirty = true;
            showDirty();
        }
    }

    private void rulesChanged(boolean fromClient) {
        if (fromClient) {
            rulesDirty = true;
            showDirty();
        }
    }

    /** Each card says when it has changes that are not saved yet, and its Save button only works then. */
    private void showDirty() {
        smtpState.setText(smtpDirty ? "Unsaved changes" : "");
        thresholdsState.setText(thresholdsDirty ? "Unsaved changes" : "");
        rulesState.setText(rulesDirty ? "Unsaved changes" : "");
        saveSmtp.setEnabled(admin && smtpDirty);
        saveThresholds.setEnabled(admin && thresholdsDirty);
        saveRules.setEnabled(admin && rulesDirty);
        testMail.setEnabled(admin && !smtpDirty);
        testMail.setTooltipText(smtpDirty ? "Save the SMTP settings first" : null);
    }

    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
        if (!(smtpDirty || thresholdsDirty || rulesDirty)) {
            return;
        }
        BeforeLeaveEvent.ContinueNavigationAction go = event.postpone();
        ConfirmDialog d = new ConfirmDialog("Leave without saving?", "The changes on this page are not saved yet.",
                "Leave", e -> go.proceed());
        d.setCancelable(true);
        d.setCancelText("Stay");
        d.setConfirmButtonTheme("error primary");
        d.open();
    }
}
