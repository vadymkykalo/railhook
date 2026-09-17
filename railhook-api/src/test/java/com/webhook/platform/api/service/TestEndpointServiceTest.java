package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.repository.CapturedRequestRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.TestEndpointRepository;
import com.webhook.platform.api.dto.TestEndpointRequest;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.security.SuspensionCheck;
import com.webhook.platform.api.security.TrustedProxyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestEndpointServiceTest {

    @Test
    @DisplayName("hitting the per-project limit is a conflict the caller can read, not a server error")
    void limitReachedIsConflict() {
        UUID projectId = UUID.randomUUID();
        TestEndpointRepository endpoints = mock(TestEndpointRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        when(projects.findById(projectId)).thenReturn(Optional.of(new Project()));
        when(endpoints.countByProjectId(projectId)).thenReturn(10L);

        TestEndpointService service = new TestEndpointService(
                endpoints, mock(CapturedRequestRepository.class), projects,
                mock(TrustedProxyResolver.class), mock(PlatformTransactionManager.class),
                mock(SuspensionCheck.class));
        ReflectionTestUtils.setField(service, "maxPerProject", 10);

        assertThatThrownBy(() -> service.create(projectId, new TestEndpointRequest()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Maximum test endpoints limit reached (10)");
    }
}
