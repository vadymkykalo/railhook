package com.webhook.platform.api.billing;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.repository.PlanRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// railhook-ui's plans.ts hand-mirrors the free plan's limits; update it with a seeded limit.
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

        // checkTunnelLimit rejects on the feature flag before reading the count.
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
    @DisplayName("free carries every feature: there is no paid plan to upgrade to, so quotas are its only limit")
    void freePlanCarriesEveryFeature() {
        // Until paid plans are sold, free is bounded by quotas, not features.
        Plan free = plan("free");
        for (String feature : new String[] { "workflows", "rules", "replay", "mTLS", "tunnels" }) {
            assertThat(free.hasFeature(feature)).as(feature).isTrue();
        }
    }

    @Test
    @DisplayName("the paid feature ladder is the rows the pricing table shows, and no SSO")
    void featureLadderMatchesThePricingTable() {
        assertThat(plan("starter").hasFeature("workflows")).isTrue();

        // Spelled "mTLS"; a lookup with the wrong casing silently returns false.
        assertThat(plan("starter").hasFeature("mTLS")).isFalse();
        assertThat(plan("pro").hasFeature("mTLS")).isTrue();

        // V059 dropped the "sso" key seeded with no SSO implementation; keep it dropped.
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
