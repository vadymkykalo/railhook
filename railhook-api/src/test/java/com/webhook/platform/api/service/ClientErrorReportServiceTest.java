package com.webhook.platform.api.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.webhook.platform.api.dto.ClientErrorReportRequest;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard's render errors only ever reached the browser console, so nobody here saw them.
 * They now come back to this service and go into the same logs as everything else — which means
 * a string a browser chose ends up on a log line, and that is why this class has tests before it
 * has features. CodeQL has already caught one log-injection in this repository.
 */
class ClientErrorReportServiceTest {

    private ClientErrorReportService service;
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new ClientErrorReportService(true, 5);
        serviceLogger = (Logger) LoggerFactory.getLogger(ClientErrorReportService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    private static ClientErrorReportRequest report(String message) {
        ClientErrorReportRequest r = new ClientErrorReportRequest();
        r.setMessage(message);
        r.setUrl("https://hooks.example.com/admin/deliveries");
        return r;
    }

    private String loggedText() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    @Nested
    @DisplayName("a browser cannot write its own log lines")
    class LogInjection {

        @Test
        @DisplayName("a newline in the message cannot forge a second log entry")
        void newlinesAreStripped() {
            service.record(report("boom\nERROR c.w.p.Fake - the database is gone"), UUID.randomUUID());

            String logged = loggedText();
            assertFalse(logged.contains("\nERROR"),
                    "a report that can start a line can impersonate any log this platform writes: " + logged);
            assertTrue(logged.contains("boom"), logged);
        }

        @Test
        @DisplayName("carriage returns go too")
        void carriageReturnsAreStripped() {
            service.record(report("boom\r\nsomething else"), UUID.randomUUID());

            assertFalse(loggedText().contains("\r"), loggedText());
        }

        @Test
        @DisplayName("other control characters do not survive either")
        void controlCharactersAreStripped() {
            // A NUL truncates the line for some readers; an escape sequence repaints the
            // terminal of whoever is tailing the log.
            service.record(report("boom\u0000cut\u001B[31mred"), UUID.randomUUID());

            String logged = loggedText();
            assertFalse(logged.contains("\u0000"), logged);
            assertFalse(logged.contains("\u001B"), logged);
            assertTrue(logged.contains("boom"), logged);
        }
    }

    @Nested
    @DisplayName("what is kept is bounded")
    class Bounds {

        @Test
        @DisplayName("a huge stack is truncated rather than written whole")
        void oversizedStackIsTruncated() {
            ClientErrorReportRequest r = report("boom");
            r.setStack("x".repeat(50_000));

            service.record(r, UUID.randomUUID());

            assertTrue(loggedText().length() < 10_000,
                    "one report must not be able to fill a log volume");
        }

        @Test
        @DisplayName("the query string is dropped from the url")
        void urlQueryIsDropped() {
            ClientErrorReportRequest r = report("boom");
            r.setUrl("https://hooks.example.com/admin/deliveries?token=secret-value&page=2");

            service.record(r, UUID.randomUUID());

            String logged = loggedText();
            assertFalse(logged.contains("secret-value"),
                    "a query string is where a token ends up; the path is what identifies the screen");
            assertTrue(logged.contains("/admin/deliveries"), logged);
        }
    }

    @Nested
    @DisplayName("a loop in the browser cannot flood the log")
    class Throttling {

        @Test
        @DisplayName("one user is capped per window")
        void perUserCap() {
            UUID user = UUID.randomUUID();
            for (int i = 0; i < 20; i++) {
                service.record(report("boom " + i), user);
            }

            List<ILoggingEvent> events = appender.list;
            assertEquals(5, events.size(),
                    "a component that throws on every render would otherwise write a log line per frame");
        }

        @Test
        @DisplayName("one user's flood does not silence another")
        void capIsPerUser() {
            UUID noisy = UUID.randomUUID();
            for (int i = 0; i < 20; i++) {
                service.record(report("boom"), noisy);
            }
            appender.list.clear();

            service.record(report("a different user's problem"), UUID.randomUUID());

            assertEquals(1, appender.list.size());
        }
    }

    @Test
    @DisplayName("an operator can turn reporting off, and then nothing is written")
    void disabledDropsSilently() {
        ClientErrorReportService disabled = new ClientErrorReportService(false, 5);

        disabled.record(report("boom"), UUID.randomUUID());

        assertTrue(appender.list.isEmpty(), "reporting is off; nothing about the report belongs in the log");
    }

    @Test
    @DisplayName("the report is logged at WARN — visible, but not an incident of its own")
    void logsAtWarn() {
        service.record(report("boom"), UUID.randomUUID());

        assertEquals(Level.WARN, appender.list.get(0).getLevel());
    }

    @Test
    @DisplayName("a report with no message at all is ignored rather than logged empty")
    void blankMessageIsIgnored() {
        service.record(report("   "), UUID.randomUUID());

        assertTrue(appender.list.isEmpty());
    }

    @Test
    @DisplayName("the organization is taken from the tenant scope, not from the caller")
    void organizationComesFromTheScope() {
        UUID org = UUID.randomUUID();

        TenantContext.runAs(org, () -> service.record(report("boom"), UUID.randomUUID()));

        assertTrue(loggedText().contains(org.toString()),
                "whose organization this is is a property of the request, so it must come off the "
                        + "scope rather than off an argument a handler could pass wrongly: " + loggedText());
    }
}
