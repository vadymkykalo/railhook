package com.webhook.platform.api.service;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// A string a browser chose ends up on a log line, so log injection is the risk here.
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

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\u0000", "\u001B"})
    @DisplayName("a control character in the message cannot forge or repaint a log line")
    void controlCharactersAreStripped(String control) {
        service.record(report("boom" + control + "ERROR c.w.p.Fake - the database is gone"), UUID.randomUUID());

        assertThat(appender.list).hasSize(1);
        assertThat(loggedText()).doesNotContain(control).contains("boom");
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

            assertThat(loggedText().length()).isLessThan(10_000);
        }

        @Test
        @DisplayName("the query string is dropped from the url")
        void urlQueryIsDropped() {
            ClientErrorReportRequest r = report("boom");
            r.setUrl("https://hooks.example.com/admin/deliveries?token=secret-value&page=2");

            service.record(r, UUID.randomUUID());

            assertThat(loggedText()).doesNotContain("secret-value").contains("/admin/deliveries");
        }

        @Test
        @DisplayName("the throttle's bookkeeping does not outlive the throttle")
        void windowsDoNotAccumulateForEveryUserEver() {
            // The per-user window map used to grow for the life of the process.
            ClientErrorReportService bounded = new ClientErrorReportService(true, 20);

            for (int i = 0; i < 200_000; i++) {
                bounded.record(report("something broke"), UUID.randomUUID());
            }

            assertThat(bounded.trackedWindows()).isLessThanOrEqualTo(50_000L);
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

            assertThat(appender.list).hasSize(5);
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

            assertThat(appender.list).hasSize(1);
        }
    }

    @Test
    @DisplayName("an operator can turn reporting off, and then nothing is written")
    void disabledDropsSilently() {
        new ClientErrorReportService(false, 5).record(report("boom"), UUID.randomUUID());

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("the organization is taken from the tenant scope, not from the caller")
    void organizationComesFromTheScope() {
        UUID org = UUID.randomUUID();

        TenantContext.runAs(org, () -> service.record(report("boom"), UUID.randomUUID()));

        assertThat(loggedText()).contains(org.toString());
    }
}
