package com.webhook.platform.api;

import com.webhook.platform.api.domain.entity.AlertRule;
import com.webhook.platform.api.domain.enums.AlertChannel;
import com.webhook.platform.api.domain.enums.AlertSeverity;
import com.webhook.platform.api.domain.enums.AlertType;
import com.webhook.platform.api.domain.repository.AlertRuleRepository;
import com.webhook.platform.api.service.AlertNotificationService;
import com.webhook.platform.api.service.AlertService;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * An alert's notification leaves only once the alert is stored.
 *
 * <p>{@code fireAlert} used to call the notifier in the middle of its transaction, before the
 * incident rows were written. When anything after that call failed, the alert event rolled back
 * and the Slack message, email or webhook had already gone; the rule was still un-alerted, so the
 * next evaluation a minute later sent it again.
 */
class AlertNotificationAfterCommitIntegrationTest extends AbstractIntegrationTest {

    @MockitoBean
    private AlertNotificationService notificationService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private UUID organizationId;
    private AlertRule rule;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name, plan_id) "
                + "VALUES (?, 'alert-after-commit', (SELECT id FROM plans WHERE name = 'free'))", organizationId);
        jdbcTemplate.update("INSERT INTO projects (id, organization_id, name) VALUES (?, ?, 'p')",
                projectId, organizationId);

        rule = TenantContext.callAs(organizationId, () -> transactionTemplate.execute(tx ->
                ruleRepository.save(AlertRule.builder()
                        .projectId(projectId)
                        .name("Failure rate")
                        .alertType(AlertType.FAILURE_RATE)
                        .severity(AlertSeverity.CRITICAL)
                        .channel(AlertChannel.SLACK)
                        .webhookUrl("https://hooks.slack.com/services/T000/B000/XXXX")
                        .thresholdValue(10.0)
                        .build())));
    }

    @Test
    void anAlertThatRollsBack_notifiesNobody() {
        TenantContext.runAs(organizationId, () -> transactionTemplate.executeWithoutResult(tx -> {
            alertService.fireAlert(rule, 80.0, "80% of deliveries failed");
            tx.setRollbackOnly();
        }));

        verify(notificationService, never()).dispatch(any(), any());
        assertThat(storedAlerts()).isZero();
    }

    @Test
    void anAlertThatCommits_notifiesOnce_andTheNotifierCanAlreadySeeIt() {
        AtomicLong visibleWhenDispatched = new AtomicLong(-1);
        doAnswer(inv -> {
            // A connection of its own, outside the transaction that wrote the alert: it sees
            // only what has been committed.
            visibleWhenDispatched.set(storedAlerts());
            return null;
        }).when(notificationService).dispatch(any(), any());

        TenantContext.runAs(organizationId, () -> alertService.fireAlert(rule, 80.0, "80% of deliveries failed"));

        verify(notificationService, times(1)).dispatch(any(), any());
        assertThat(visibleWhenDispatched.get()).isEqualTo(1L);
    }

    private long storedAlerts() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM alert_events WHERE alert_rule_id = ?")) {
            query.setObject(1, rule.getId());
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
