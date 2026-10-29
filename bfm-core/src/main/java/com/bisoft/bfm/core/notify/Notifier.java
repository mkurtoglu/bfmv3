package com.bisoft.bfm.core.notify;

/** Sends one mail through the given SMTP server. Implemented by the watcher application. */
@FunctionalInterface
public interface Notifier {

    void send(NotificationSettings.Smtp smtp, MailMessage message) throws Exception;
}
