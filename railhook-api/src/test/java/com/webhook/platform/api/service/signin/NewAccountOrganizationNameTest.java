package com.webhook.platform.api.service.signin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// A Workspace account names its company; a personal one does not.
class NewAccountOrganizationNameTest {

    private static VerifiedIdentity identity(String email, String givenName, String hostedDomain) {
        return new VerifiedIdentity("google", "sub-1", email, "Full Name", givenName, hostedDomain);
    }

    @Test
    void namesTheCompanyAfterTheWorkspaceDomain() {
        assertThat(NewAccountOrganizationName.of(identity("ada@acme.com", "Ada", "acme.com")))
                .isEqualTo("Acme");
        assertThat(NewAccountOrganizationName.of(identity("ada@northwind-traders.co.uk", "Ada", "northwind-traders.co.uk")))
                .isEqualTo("Northwind-traders");
    }

    @Test
    void namesAPersonalAccountAfterThePerson() {
        assertThat(NewAccountOrganizationName.of(identity("ada@gmail.com", "Ada", null)))
                .isEqualTo("Ada's workspace");
    }

    @Test
    void fallsBackToTheAddressWhenGoogleGivesNoName() {
        assertThat(NewAccountOrganizationName.of(identity("ada.lovelace@gmail.com", null, "")))
                .isEqualTo("ada.lovelace's workspace");
    }

    @Test
    void neverProducesANameLongerThanTheColumnAllows() {
        String longName = "x".repeat(300);
        assertThat(NewAccountOrganizationName.of(identity("a@gmail.com", longName, null)))
                .hasSizeLessThanOrEqualTo(NewAccountOrganizationName.MAX_LENGTH);
    }
}
