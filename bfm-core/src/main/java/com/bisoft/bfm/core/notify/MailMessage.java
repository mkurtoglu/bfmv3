package com.bisoft.bfm.core.notify;

import java.util.List;

public record MailMessage(List<String> to, String subject, String body) {
}
