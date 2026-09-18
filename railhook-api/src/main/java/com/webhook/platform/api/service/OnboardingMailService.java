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
 * The two mails a new account gets on a deployment that turned them on: a welcome when its
 * address is proven, and one nudge two days later if its organization has still sent and received
 * nothing.
 *
 * <p>Off by default ({@code ONBOARDING_EMAILS_ENABLED}). They are written in the first person by
 * Railhook's author, which is right for Railhook Cloud and wrong for a self-hosted install mailing
 * its own users.
 *
 * <p>Each is sent at most once, and the timestamp on the user is what says so. It is written
 * before the mail goes, so a crash between the two loses a mail rather than sending it twice —
 * the right way round for mail nobody asked for.
 */
@Service
@Slf4j
public class OnboardingMailService {

    /** How long after the welcome an account that has done nothing is asked whether it is stuck. */
    static final Duration NUDGE_AFTER = Duration.ofHours(48);

    /** Accounts taken per query, so one run never holds an unbounded list. */
    static final int BATCH_SIZE = 100;

    /** Batches per run; whatever is left waits for the next run. */
    static final int MAX_BATCHES = 20;

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

    /**
     * Welcomes an account whose address has just been proven, unless it was welcomed already or
     * this deployment does not send onboarding mail.
     *
     * <p>Called inside the transaction that verifies the address: the timestamp is written with
     * it, and the mail goes only once that transaction commits, so a verification that rolls back
     * sends nothing.
     */
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

    /**
     * Nudges every account that is due: verified, welcomed at least two days ago, not nudged yet,
     * owning an organization that is not suspended, and a member of none that has sent or received
     * an event. Returns how many were nudged.
     */
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
                // Claimed one at a time: a row another replica already claimed updates nothing.
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
