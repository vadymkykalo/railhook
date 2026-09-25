package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.IdempotencyPolicy;
import com.webhook.platform.api.domain.enums.SchemaValidationPolicy;
import com.webhook.platform.api.domain.repository.ApiKeyRepository;
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
    private final PiiMaskingService piiMaskingService;
    private final ApiKeyRepository apiKeyRepository;

    public ProjectService(ProjectRepository projectRepository, PiiMaskingService piiMaskingService,
            ApiKeyRepository apiKeyRepository) {
        this.projectRepository = projectRepository;
        this.piiMaskingService = piiMaskingService;
        this.apiKeyRepository = apiKeyRepository;
    }

    public static final String FIRST_PROJECT_NAME = "My first project";

    @Auditable(action = AuditAction.CREATE, resourceType = "Project")
    @Transactional
    public ProjectResponse createProject(ProjectRequest request) {
        return ProjectResponse.of(save(TenantContext.require(), request.getName(), request.getDescription()));
    }

    // Registration runs before any tenant scope exists, so the organization is passed in.
    @Transactional
    public ProjectResponse createFirstProject(Organization organization) {
        return ProjectResponse.of(save(organization.getId(), FIRST_PROJECT_NAME, null));
    }

    private Project save(UUID owningOrganization, String name, String description) {
        Project project = projectRepository.saveAndFlush(Project.builder()
                .name(name)
                .organizationId(owningOrganization)
                .description(description)
                .build());
        // A project with no masking rules showed customer addresses in full.
        piiMaskingService.seedDefaultRules(project.getId());
        return project;
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

        Instant now = Instant.now();
        project.setDeletedAt(now);
        projectRepository.save(project);

        // For anything that reads a key without going through the deleted project.
        apiKeyRepository.findByProjectIdAndRevokedAtIsNull(id).forEach(key -> key.setRevokedAt(now));
    }

}
