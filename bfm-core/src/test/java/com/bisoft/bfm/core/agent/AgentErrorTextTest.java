package com.bisoft.bfm.core.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Agent failures reach the console and the mails; they must read as what happened, not as Java class names. */
class AgentErrorTextTest {

    private static final String URL = "https://10.0.0.1:8008";
    private static final Duration T = Duration.ofSeconds(3);

    private static String text(IOException e) {
        return HttpAgentClient.describe(e, URL, T);
    }

    @Test
    void namesTheCauseInWords() {
        assertEquals("connection refused by 10.0.0.1:8008 (agent not running, or wrong port)",
                text(new ConnectException()));
        assertEquals("connection refused by 10.0.0.1:8008 (agent not running, or wrong port)",
                text(new IOException("wrapped", new ConnectException("Connection refused"))));
        assertEquals("no answer from 10.0.0.1:8008 within 3.0 s (host down or firewalled)",
                text(new HttpConnectTimeoutException("HTTP connect timed out")));
        assertEquals("the agent at 10.0.0.1:8008 did not reply within 3.0 s",
                text(new HttpTimeoutException("request timed out")));
        assertEquals("unknown host in 10.0.0.1:8008", text(new IOException(new UnknownHostException("x"))));
        assertEquals("connection to 10.0.0.1:8008 failed: broken pipe", text(new IOException("broken pipe")));
        assertEquals("connection to 10.0.0.1:8008 failed", text(new IOException()));
        assertFalse(text(new ConnectException()).contains("Exception"));
    }
}
