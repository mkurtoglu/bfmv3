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
import com.vaadin.flow.spring.security.AuthenticationContext;
import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.core.event.MailRule;
import com.bisoft.bfm.core.notify.MailMessage;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.watcher.service.NotificationService;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SMTP server, recipients, alert thresholds and per-event mail rules of one cluster. The SMTP server is the same for
 * every cluster of the watcher.
 */
class NotificationsPage extends Div implements BeforeLeaveObserver {

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
    private final boolean sharedSmtp;

    private final TextField host = new TextField(Ui.t("nt.host"));
    private final IntegerField port = new IntegerField(Ui.t("adm.port"));
    private final Select<NotificationSettings.Security> security = new Select<>();
    private final TextField smtpUser = new TextField(Ui.t("login.username"));
    private final PasswordField password = new PasswordField(Ui.t("login.password"));
    private final EmailField from = new EmailField(Ui.t("nt.from"));
    private final List<NotificationSettings.Recipient> recipients = new ArrayList<>();
    private final Grid<NotificationSettings.Recipient> recipientGrid = new Grid<>();
    private final TextField lagAlert = new TextField(Ui.t("nt.lagAlert"));
    private final TextField slotAlert = new TextField(Ui.t("nt.slotAlert"));
    private final Span smtpState = Ui.text("pg-dirty", "");
    private final Span thresholdsState = Ui.text("pg-dirty", "");
    private final Span rulesState = Ui.text("pg-dirty", "");
    private final Button saveSmtp = new Button(Ui.t("nt.save"));
    private final Button testMail = new Button(Ui.t("nt.test"));
    private final Button saveThresholds = new Button(Ui.t("nt.saveThresholds"));
    private final Button saveRules = new Button(Ui.t("nt.saveRules"));
    private boolean smtpDirty;
    private boolean thresholdsDirty;
    private boolean rulesDirty;
    private final Map<MailRule, NotificationSettings.Rule> rules = new EnumMap<>(MailRule.class);
    private final Grid<MailRule> ruleGrid = new Grid<>();
    private final Pre preview = new Pre();

    NotificationsPage(NotificationService service, AuthenticationContext auth, boolean sharedSmtp) {
        this.service = service;
        this.sharedSmtp = sharedSmtp;
        this.admin = auth.hasRole("ADMIN");
        this.user = auth.getPrincipalName().orElse("unknown");
        addClassName("pg-page");
        H1 title = new H1(Ui.t("title.mailNotifications"));
        title.addClassName("pg-title");
        add(new Div(Ui.text("pg-eyebrow", Ui.t("ca.eyebrow")), title));
        if (!admin) {
            add(Ui.banner(Ui.t("nt.viewerNote"), Ui.Tone.INFO));
        }
        add(Ui.div("pg-grid-auto", smtpCard(), recipientsCard()), rulesCard(), previewCard());
        load();
    }

    private Div smtpCard() {
        Div card = Ui.card(Ui.t("nt.smtp"));
        if (sharedSmtp) {
            card.add(Ui.text("pg-note", Ui.t("nt.shared")));
        }
        security.setLabel(Ui.t("nt.security"));
        security.setItems(NotificationSettings.Security.values());
        security.setItemLabelGenerator(s -> switch (s) {
            case STARTTLS -> "STARTTLS";
            case SMTPS -> "SMTPS (TLS)";
            case NONE -> Ui.t("nt.none");
        });
        host.setPlaceholder("smtp.example.com");
        port.setMin(1);
        port.setMax(65535);
        password.setPlaceholder(Ui.t("nt.storedEncrypted"));
        FormLayout form = new FormLayout(host, port, security, smtpUser, password, from);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("360px", 2));
        form.setColspan(host, 2);
        form.setColspan(from, 2);
        saveSmtp.addClickListener(e -> {
            if (host.isEmpty()) {
                host.setInvalid(true);
                host.setErrorMessage(Ui.t("nt.hostFirst"));
                return;
            }
            if (Ui.attempt(() -> service.saveSmtp(new NotificationService.SmtpChange(host.getValue(),
                    port.getValue() == null ? 587 : port.getValue(), security.getValue(), smtpUser.getValue(),
                    password.isEmpty() ? null : password.getValue(), from.getValue()), user))) {
                password.clear();
                Ui.ok(Ui.t("nt.smtpSaved"));
                load();
            }
        });
        saveSmtp.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        testMail.addClickListener(e -> {
            try {
                List<String> to = service.sendTest(user);
                Ui.ok(Ui.t("nt.testOk", host.getValue(), String.join(", ", to)));
            } catch (Exception ex) {
                Ui.error(Ui.t("nt.testFailed", Ui.tr(ex.getMessage())));
            }
        });
        host.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        smtpUser.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        from.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        port.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        security.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        password.addValueChangeListener(e -> smtpChanged(e.isFromClient()));
        card.add(form, Ui.div("pg-row", testMail, saveSmtp, smtpState),
                Ui.text("pg-note", Ui.t("nt.saveFirst")));
        return card;
    }

    private Div recipientsCard() {
        Div card = Ui.card(Ui.t("nt.recipients"));
        recipientGrid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_COMPACT);
        recipientGrid.setAllRowsVisible(true);
        recipientGrid.addComponentColumn(r -> {
            Div d = new Div(Ui.text(null, r.name()), new Div(Ui.text("pg-note", r.address())));
            d.getChildren().findFirst().ifPresent(c -> c.getElement().setAttribute("style",
                    "font-weight:700;color:var(--pg-ink)"));
            return d;
        }).setHeader(Ui.t("nt.group")).setFlexGrow(1);
        recipientGrid.addComponentColumn(r -> Ui.chip(switch (r.filter()) {
            case ALL -> Ui.t("nt.allEvents");
            case CRITICAL -> Ui.t("nt.criticalOnly");
            case ROLE_CHANGES -> Ui.t("nt.roleChanges");
        }, switch (r.filter()) {
            case ALL -> Ui.Tone.OK;
            case CRITICAL -> Ui.Tone.CRIT;
            case ROLE_CHANGES -> Ui.Tone.INFO;
        })).setHeader(Ui.t("nt.gets")).setAutoWidth(true);
        recipientGrid.addComponentColumn(r -> {
            Button rm = new Button(VaadinIcon.TRASH.create(), e -> {
                recipients.remove(r);
                saveRecipients();
            });
            rm.setAriaLabel(Ui.t("nt.removeGroup", r.name()));
            rm.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            rm.setEnabled(admin);
            return rm;
        }).setAutoWidth(true).setFlexGrow(0);

        TextField name = new TextField(Ui.t("nt.groupName"));
        TextField address = new TextField(Ui.t("nt.addresses"));
        address.setHelperText(Ui.t("nt.addressesHelp"));
        Select<NotificationSettings.Filter> filter = new Select<>();
        filter.setLabel(Ui.t("nt.gets"));
        filter.setItems(NotificationSettings.Filter.values());
        filter.setItemLabelGenerator(f -> switch (f) {
            case ALL -> Ui.t("nt.allEvents");
            case CRITICAL -> Ui.t("nt.criticalOnly");
            case ROLE_CHANGES -> Ui.t("nt.roleChanges");
        });
        filter.setValue(NotificationSettings.Filter.ALL);
        Button add = new Button(Ui.t("nt.addGroup"), e -> {
            if (address.isEmpty()) {
                address.setInvalid(true);
                address.setErrorMessage(Ui.t("nt.enterAddress"));
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

        lagAlert.setHelperText(Ui.t("nt.lagHelp"));
        slotAlert.setHelperText(Ui.t("nt.slotHelp"));
        lagAlert.addValueChangeListener(e -> thresholdsChanged(e.isFromClient()));
        slotAlert.addValueChangeListener(e -> thresholdsChanged(e.isFromClient()));
        saveThresholds.addClickListener(e -> {
            long lag;
            long slot;
            try {
                lag = SizeText.parse(lagAlert.getValue());
                lagAlert.setInvalid(false);
            } catch (IllegalArgumentException ex) {
                lagAlert.setErrorMessage(Ui.tr(ex.getMessage()));
                lagAlert.setInvalid(true);
                return;
            }
            try {
                slot = SizeText.parse(slotAlert.getValue());
                slotAlert.setInvalid(false);
            } catch (IllegalArgumentException ex) {
                slotAlert.setErrorMessage(Ui.tr(ex.getMessage()));
                slotAlert.setInvalid(true);
                return;
            }
            if (Ui.attempt(() -> service.saveThresholds(lag, slot, user))) {
                Ui.ok(Ui.t("nt.thresholdsSaved"));
                load();
            }
        });
        FormLayout thresholds = new FormLayout(lagAlert, slotAlert);
        thresholds.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1), new FormLayout.ResponsiveStep("420px", 2));
        recipientGrid.setEmptyStateText(Ui.t("nt.noRecipients"));
        card.add(recipientGrid, form, Ui.div("pg-row", add), new H2(Ui.t("nt.thresholds")), thresholds,
                Ui.div("pg-row", saveThresholds, thresholdsState));
        return card;
    }

    private boolean saveRecipients() {
        if (Ui.attempt(() -> service.saveRecipients(recipients, user))) {
            Ui.ok(Ui.t("nt.recipientsSaved"));
            load();
            return true;
        }
        load();
        return false;
    }

    private Div rulesCard() {
        Div card = Ui.card(null);
        card.add(Ui.div("pg-spread", new H2(Ui.t("nt.rules")),
                Ui.text("pg-note", Ui.t("nt.rulesNote"))));
        ruleGrid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_WRAP_CELL_CONTENT);
        ruleGrid.setAllRowsVisible(true);
        ruleGrid.addColumn(r -> Ui.tr(r.label())).setHeader(Ui.t("ev.col.event")).setFlexGrow(1);
        ruleGrid.addComponentColumn(r -> Ui.chip(switch (r.severity()) {
            case CRITICAL -> Ui.t("sev.critical");
            case WARNING -> Ui.t("sev.warning");
            case INFO -> Ui.t("sev.info");
        }, Ui.tone(r.severity()))).setHeader(Ui.t("ev.severity")).setAutoWidth(true);
        ruleGrid.addComponentColumn(r -> {
            Select<String> s = new Select<>();
            s.setItems(THROTTLES.keySet());
            s.setItemLabelGenerator(NotificationsPage::throttleLabel);
            s.setValue(label(rules.get(r).throttleMs()));
            s.setAriaLabel(Ui.t("nt.throttleFor", Ui.tr(r.label())));
            s.setReadOnly(!admin);
            s.addValueChangeListener(e -> {
                rules.put(r, new NotificationSettings.Rule(rules.get(r).enabled(), THROTTLES.get(e.getValue())));
                rulesChanged(e.isFromClient());
            });
            return s;
        }).setHeader(Ui.t("nt.throttle")).setAutoWidth(true);
        ruleGrid.addComponentColumn(r -> {
            Checkbox c = new Checkbox(rules.get(r).enabled());
            c.setAriaLabel(Ui.t("nt.mailFor", Ui.tr(r.label())));
            c.setReadOnly(!admin);
            c.addValueChangeListener(e -> {
                rules.put(r, new NotificationSettings.Rule(e.getValue(), rules.get(r).throttleMs()));
                rulesChanged(e.isFromClient());
            });
            return c;
        }).setHeader(Ui.t("nt.mail")).setAutoWidth(true);
        saveRules.addClickListener(e -> {
            if (Ui.attempt(() -> service.saveRules(Map.copyOf(rules), user))) {
                Ui.ok(Ui.t("nt.rulesSaved"));
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

    /** "None", "5 min", "1 h" in the page's language; the values stay as they are. */
    private static String throttleLabel(String v) {
        if ("None".equals(v)) {
            return Ui.t("nt.none");
        }
        return v.replace(" min", " " + Ui.t("unit.min")).replace(" h", " " + Ui.t("unit.h"));
    }

    private Div previewCard() {
        Div card = Ui.card(Ui.t("nt.preview"));
        preview.addClassNames("pg-log", "pg-preview");
        // the mails themselves are written in English, whatever the console's language
        preview.getElement().setAttribute("dir", "ltr");
        preview.getElement().setAttribute("lang", "en");
        card.add(Ui.text("pg-note", Ui.t("nt.previewNote")), preview);
        return card;
    }

    private void load() {
        NotificationService.SettingsView v = service.view();
        host.setValue(v.smtp().host() == null ? "" : v.smtp().host());
        port.setValue(v.smtp().port());
        security.setValue(v.smtp().security());
        smtpUser.setValue(v.smtp().user() == null ? "" : v.smtp().user());
        password.setPlaceholder(Ui.t(v.smtp().passwordSet() ? "nt.storedKeep" : "nt.notSet"));
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
        smtpState.setText(smtpDirty ? Ui.t("nt.unsaved") : "");
        thresholdsState.setText(thresholdsDirty ? Ui.t("nt.unsaved") : "");
        rulesState.setText(rulesDirty ? Ui.t("nt.unsaved") : "");
        saveSmtp.setEnabled(admin && smtpDirty);
        saveThresholds.setEnabled(admin && thresholdsDirty);
        saveRules.setEnabled(admin && rulesDirty);
        testMail.setEnabled(admin && !smtpDirty);
        testMail.setTooltipText(smtpDirty ? Ui.t("nt.saveSmtpFirst") : null);
    }

    @Override
    public void beforeLeave(BeforeLeaveEvent event) {
        if (!(smtpDirty || thresholdsDirty || rulesDirty)) {
            return;
        }
        BeforeLeaveEvent.ContinueNavigationAction go = event.postpone();
        ConfirmDialog d = new ConfirmDialog(Ui.t("nt.leaveTitle"), Ui.t("nt.leaveText"), Ui.t("nt.leave"),
                e -> go.proceed());
        d.setCancelable(true);
        d.setCancelText(Ui.t("nt.stay"));
        d.setConfirmButtonTheme("error primary");
        d.open();
    }
}
