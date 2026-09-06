package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.EndpointRequest;
import com.webhook.platform.common.enums.SignatureScheme;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * One rule for what an omitted field means on update, applied to every field.
 *
 * <p>{@code updateEndpoint} used to hold two rules at once, three lines apart: {@code secret},
 * {@code enabled}, {@code allowedSourceIps} and {@code signatureScheme} were left alone when the
 * request omitted them, under a comment explaining that null must mean "not specified" — while
 * {@code description} and {@code rateLimitPerSecond} were assigned straight from the request, so
 * an update that did not mention them wiped them. A partial PUT silently removed an endpoint's
 * throttle.
 *
 * <p>The rule now: an absent field is unchanged, and an explicitly empty value clears — blank for
 * a string, {@code 0} for the rate limit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EndpointService.updateEndpoint — absent means unchanged, empty means clear")
class EndpointUpdateSemanticsTest {

    private static final String MASTER_KEY = "master_key_32_chars_long_padding";
    private static final String SALT = "test_salt_value";

    @Mock private EndpointRepository endpointRepository;
    @Mock private ProjectRepository projectRepository;

    private EndpointService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID endpointId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        service = new EndpointService(
                endpointRepository, projectRepository, WebClient.builder(), buildRegistry(),
                true, Collections.emptyList(), false);

        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(Project.builder().id(projectId).name("p").build()));
        when(endpointRepository.saveAndFlush(any(Endpoint.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static EncryptionKeyRegistry buildRegistry() throws Exception {
        EncryptionKeyRegistry reg = new EncryptionKeyRegistry();
        setField(reg, "singleKey", MASTER_KEY);
        setField(reg, "multiKeys", "");
        setField(reg, "configuredActiveVersion", 1);
        setField(reg, "salt", SALT);
        var init = reg.getClass().getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(reg);
        return reg;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private Endpoint existing() {
        Endpoint endpoint = Endpoint.builder()
                .id(endpointId)
                .projectId(projectId)
                .url("https://example.com/hook")
                .description("the one the team relies on")
                .rateLimitPerSecond(25)
                .allowedSourceIps("203.0.113.4")
                .signatureScheme(SignatureScheme.LEGACY)
                .build();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        return endpoint;
    }

    private EndpointRequest urlOnly() {
        EndpointRequest request = new EndpointRequest();
        request.setUrl("https://example.com/hook");
        return request;
    }

    @Test
    void anUpdateThatMentionsNeitherLeavesTheDescriptionAndTheRateLimitAlone() {
        Endpoint endpoint = existing();

        service.updateEndpoint(endpointId, urlOnly());

        assertThat(endpoint.getDescription()).isEqualTo("the one the team relies on");
        assertThat(endpoint.getRateLimitPerSecond()).isEqualTo(25);
        assertThat(endpoint.getAllowedSourceIps()).isEqualTo("203.0.113.4");
        assertThat(endpoint.getSignatureScheme()).isEqualTo(SignatureScheme.LEGACY);
    }

    @Test
    void aBlankDescriptionClearsIt() {
        Endpoint endpoint = existing();
        EndpointRequest request = urlOnly();
        request.setDescription("");

        service.updateEndpoint(endpointId, request);

        assertThat(endpoint.getDescription()).isNull();
    }

    @Test
    void aZeroRateLimitRemovesTheThrottle() {
        Endpoint endpoint = existing();
        EndpointRequest request = urlOnly();
        request.setRateLimitPerSecond(0);

        service.updateEndpoint(endpointId, request);

        assertThat(endpoint.getRateLimitPerSecond()).isNull();
    }

    @Test
    void aBlankAllowedSourceIpsClearsTheAllowList() {
        Endpoint endpoint = existing();
        EndpointRequest request = urlOnly();
        request.setAllowedSourceIps("  ");

        service.updateEndpoint(endpointId, request);

        assertThat(endpoint.getAllowedSourceIps()).isNull();
    }

    @Test
    void aValueStillReplacesTheOldOne() {
        Endpoint endpoint = existing();
        EndpointRequest request = urlOnly();
        request.setDescription("renamed");
        request.setRateLimitPerSecond(90);

        service.updateEndpoint(endpointId, request);

        assertThat(endpoint.getDescription()).isEqualTo("renamed");
        assertThat(endpoint.getRateLimitPerSecond()).isEqualTo(90);
    }

    @Test
    void creatingWithAZeroRateLimitMeansNoLimitRatherThanALimitOfZero() {
        EndpointRequest request = urlOnly();
        request.setRateLimitPerSecond(0);
        request.setDescription("  ");

        var response = service.createEndpoint(projectId, request);

        assertThat(response.getRateLimitPerSecond()).isNull();
        assertThat(response.getDescription()).isNull();
    }
}
