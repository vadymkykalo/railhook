package com.webhook.platform.api.service;

import com.webhook.platform.api.tenancy.SystemTenant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the day-2 onboarding nudge hourly on one replica.
 *
 * <p>Kept apart from {@link OnboardingMailService#sendDueNudges} so the lock guards the schedule
 * and not the work: the work is safe to run twice by itself, since each account is claimed with a
 * conditional update before its mail goes.
 */
@Component
public class OnboardingNudgeScheduler {

    private final OnboardingMailService onboardingMailService;

    public OnboardingNudgeScheduler(OnboardingMailService onboardingMailService) {
        this.onboardingMailService = onboardingMailService;
    }

    @SystemTenant("new accounts across every organization, found by what their organizations have not done yet")
    @Scheduled(fixedRateString = "${app.onboarding-emails.nudge-interval-ms:3600000}", initialDelay = 300_000)
    @SchedulerLock(name = "onboardingNudge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        onboardingMailService.sendDueNudges();
    }
}
