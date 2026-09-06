package com.webhook.platform.api.billing;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.repository.PlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan ladder as the migrations leave it, asserted against the numbers the
 * public pricing page prints.
 *
 * `railhook-ui/src/pages/landing/plans.ts` is a hand-written mirror of
 * these rows — the landing page cannot query the database, and quoting a limit
 * it does not enforce is the kind of mistake nobody notices until a customer
 * does. Nothing but a test spans the two, so this is it: change a seeded limit
 * and this fails, naming the file to update.
 *
 * Needs Docker: Flyway runs against the Testcontainers database, which is the
 * whole point — these are the values a real deployment ends up with, not a
 * fixture.
 */
class SeededPlanIntegrationTest extends AbstractIntegrationTest {

    private static final int UNLIMITED = -1;

    @Autowired
    private PlanRepository planRepository;

    private Plan plan(String name) {
        return planRepository.findByName(name).orElseThrow(() -> new AssertionError("no seeded plan: " + name));
    }

    @Test
    @DisplayName("free grants one tunnel, with the feature flag that gates it")
    void freePlanGrantsOneTunnel() {
        Plan free = plan("free");

        /* Both halves matter. EntitlementService.checkTunnelLimit() rejects on
           the feature flag before it ever reads the count, so a plan with a
           limit of 1 and the flag off still grants nothing. */
        assertThat(free.getMaxActiveTunnels()).isEqualTo(1);
        assertThat(free.hasFeature("tunnels")).isTrue();
    }

    @Test
    @DisplayName("the seeded limits match what the pricing page prints")
    void seededLimitsMatchThePricingPage() {
        Plan free = plan("free");
        assertThat(free.getMaxEventsPerMonth()).isEqualTo(10_000);
        assertThat(free.getMaxProjects()).isEqualTo(3);
        assertThat(free.getMaxEndpointsPerProject()).isEqualTo(5);
        assertThat(free.getMaxMembers()).isEqualTo(5);
        assertThat(free.getRateLimitPerSecond()).isEqualTo(10);
        assertThat(free.getMaxRetentionDays()).isEqualTo(7);
        assertThat(free.getPriceMonthlyCents()).isZero();

        Plan starter = plan("starter");
        assertThat(starter.getMaxEventsPerMonth()).isEqualTo(100_000);
        assertThat(starter.getMaxProjects()).isEqualTo(10);
        assertThat(starter.getMaxEndpointsPerProject()).isEqualTo(20);
        assertThat(starter.getMaxMembers()).isEqualTo(10);
        assertThat(starter.getRateLimitPerSecond()).isEqualTo(50);
        assertThat(starter.getMaxRetentionDays()).isEqualTo(30);
        assertThat(starter.getMaxActiveTunnels()).isEqualTo(3);
        assertThat(starter.getPriceMonthlyCents()).isEqualTo(2_900);
        assertThat(starter.getPriceYearlyCents()).isEqualTo(29_000);

        Plan pro = plan("pro");
        assertThat(pro.getMaxEventsPerMonth()).isEqualTo(1_000_000);
        assertThat(pro.getMaxProjects()).isEqualTo(50);
        assertThat(pro.getMaxEndpointsPerProject()).isEqualTo(100);
        assertThat(pro.getMaxMembers()).isEqualTo(50);
        assertThat(pro.getRateLimitPerSecond()).isEqualTo(200);
        assertThat(pro.getMaxRetentionDays()).isEqualTo(90);
        assertThat(pro.getMaxActiveTunnels()).isEqualTo(10);
        assertThat(pro.getPriceMonthlyCents()).isEqualTo(9_900);
        assertThat(pro.getPriceYearlyCents()).isEqualTo(99_000);

        Plan enterprise = plan("enterprise");
        assertThat(enterprise.getMaxEventsPerMonth()).isEqualTo(UNLIMITED);
        assertThat(enterprise.getRateLimitPerSecond()).isEqualTo(1_000);
        assertThat(enterprise.getMaxRetentionDays()).isEqualTo(365);
        assertThat(enterprise.getMaxActiveTunnels()).isEqualTo(UNLIMITED);
    }

    @Test
    @DisplayName("the feature ladder is the three rows the pricing table shows, and no SSO")
    void featureLadderMatchesThePricingTable() {
        assertThat(plan("free").hasFeature("workflows")).isFalse();
        assertThat(plan("starter").hasFeature("workflows")).isTrue();

        /* The seed and every @RequireFeature spell it "mTLS", not "mtls". A
           lookup with the wrong casing silently returns false, which would gate
           a Pro customer out of a feature they are paying for. */
        assertThat(plan("starter").hasFeature("mTLS")).isFalse();
        assertThat(plan("pro").hasFeature("mTLS")).isTrue();

        /* V036 seeded "sso": true on enterprise and self_hosted with no SSO
           anywhere in the tree, and the Billing page renders whatever `features`
           holds — so a paying customer was shown SSO as included. V059 drops the
           key; this keeps it dropped until an implementation seeds it back. */
        for (String name : new String[] { "free", "starter", "pro", "enterprise", "self_hosted" }) {
            assertThat(plan(name).getFeatures().has("sso"))
                    .as("%s must not advertise SSO: no implementation exists", name)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("self-hosted is unlimited on everything the page claims")
    void selfHostedIsUnlimited() {
        Plan selfHosted = plan("self_hosted");
        assertThat(selfHosted.getMaxEventsPerMonth()).isEqualTo(UNLIMITED);
        assertThat(selfHosted.getMaxProjects()).isEqualTo(UNLIMITED);
        assertThat(selfHosted.getMaxEndpointsPerProject()).isEqualTo(UNLIMITED);
        assertThat(selfHosted.getMaxMembers()).isEqualTo(UNLIMITED);
        assertThat(selfHosted.getMaxRetentionDays()).isEqualTo(UNLIMITED);
        assertThat(selfHosted.getRateLimitPerSecond()).isEqualTo(10_000);
    }
}
