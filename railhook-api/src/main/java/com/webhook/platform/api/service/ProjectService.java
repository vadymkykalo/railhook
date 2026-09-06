package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.IdempotencyPolicy;
import com.webhook.platform.api.domain.enums.SchemaValidationPolicy;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.ProjectRequest;
import com.webhook.platform.api.dto.ProjectResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.NotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "Project")
    @Transactional
    public ProjectResponse createProject(ProjectRequest request) {
        UUID organizationId = TenantContext.require();
        Project project = Project.builder()
                .name(request.getName())
                .organizationId(organizationId)
                .description(request.getDescription())
                .build();

        project = projectRepository.saveAndFlush(project);
        return ProjectResponse.of(project);
    }

    public ProjectResponse getProject(UUID id) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        return ProjectResponse.of(project);
    }

    public List<ProjectResponse> listProjects() {
        UUID organizationId = TenantContext.require();
        return projectRepository.findByOrganizationIdAndDeletedAtIsNull(organizationId).stream()
                .map(ProjectResponse::of)
                .collect(Collectors.toList());
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Project")
    @Transactional
    public ProjectResponse updateProject(UUID id, ProjectRequest request) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        project.setName(request.getName());
        project.setDescription(request.getDescription());
        if (request.getSchemaValidationEnabled() != null) {
            project.setSchemaValidationEnabled(request.getSchemaValidationEnabled());
        }
        if (request.getSchemaValidationPolicy() != null) {
            try {
                project.setSchemaValidationPolicy(
                        SchemaValidationPolicy.valueOf(request.getSchemaValidationPolicy().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (request.getIdempotencyPolicy() != null) {
            try {
                project.setIdempotencyPolicy(
                        IdempotencyPolicy.valueOf(request.getIdempotencyPolicy().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        project = projectRepository.save(project);

        return ProjectResponse.of(project);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "Project")
    @Transactional
    public void deleteProject(UUID id) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        project.setDeletedAt(Instant.now());
        projectRepository.save(project);
    }

}
