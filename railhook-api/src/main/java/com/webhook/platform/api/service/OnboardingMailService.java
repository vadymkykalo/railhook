package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.tenancy.SystemTenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Off by default: the mails speak as Railhook's author, which suits Cloud, not self-hosting.
 * The sent timestamp is written first, so a crash loses a mail rather than sending it twice.
 */
@Service
@Slf4j
public class OnboardingMailService {

    static final Duration NUDGE_AFTER = Duration.ofHours(48);

    // Nudges share the daily mail quota with verification and reset mails: at most ten an hour.
    static final int BATCH_SIZE = 10;
    static final int MAX_BATCHES = 1;

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final Clock clock;
    private boolean enabled;

    public OnboardingMailService(UserRepository userRepository,
                                 EmailService emailService,
                                 Clock clock,
                                 @Value("${app.onboarding-emails.enabled:false}") boolean enabled) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.clock = clock;
        this.enabled = enabled;
    }

    // The mail goes only after the verifying transaction commits, so a rollback sends nothing.
    public void welcome(User user) {
        if (!enabled
                || user.getOnboardingWelcomeSentAt() != null
                || !Boolean.TRUE.equals(user.getEmailVerified())) {
            return;
        }
        user.setOnboardingWelcomeSentAt(Instant.now(clock));
        userRepository.save(user);
        String to = user.getEmail();
        afterCommit(() -> emailService.sendWelcomeEmail(to));
    }

    @SystemTenant("new accounts across every organization, found by what their organizations have not done yet")
    public int sendDueNudges() {
        if (!enabled) {
            return 0;
        }
        int nudged = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            Instant now = Instant.now(clock);
            List<UUID> due = userRepository.findDueOnboardingNudges(now.minus(NUDGE_AFTER), BATCH_SIZE);
            for (UUID userId : due) {
                // A row another replica already claimed updates nothing.
                if (userRepository.markOnboardingNudgeSent(userId, now) == 1) {
                    userRepository.findById(userId)
                            .ifPresent(user -> emailService.sendOnboardingNudgeEmail(user.getEmail()));
                    nudged++;
                }
            }
            if (due.size() < BATCH_SIZE) {
                break;
            }
        }
        if (nudged > 0) {
            log.info("Sent the onboarding nudge to {} account(s)", nudged);
        }
        return nudged;
    }

    private static void afterCommit(Runnable send) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send.run();
            }
        });
    }
}
