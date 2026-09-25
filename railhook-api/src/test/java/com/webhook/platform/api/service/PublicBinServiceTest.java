package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.repository.PublicBinRepository;
import com.webhook.platform.api.domain.repository.PublicBinRequestRepository;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.TrustedProxyResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

// A self-hosted install that never enabled the public tester must not open an anonymous store.
class PublicBinServiceTest {

    private final PublicBinRepository bins = mock(PublicBinRepository.class);
    private final PublicBinRequestRepository requests = mock(PublicBinRequestRepository.class);

    private PublicBinService disabled() {
        return new PublicBinService(bins, requests, mock(TrustedProxyResolver.class), new ObjectMapper(),
                Clock.systemUTC(), "https://example.test", false, 3, 5000);
    }

    @Test
    void offByDefaultItMakesNothingAndShowsNothing() {
        PublicBinService service = disabled();
        assertThatThrownBy(() -> service.create("192.0.2.1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.read("abc")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(bins, requests);
    }
}
