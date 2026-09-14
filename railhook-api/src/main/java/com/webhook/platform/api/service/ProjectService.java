package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Organization;
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
    private final PiiMaskingService piiMaskingService;

    public ProjectService(ProjectRepository projectRepository, PiiMaskingService piiMaskingService) {
        this.projectRepository = projectRepository;
        this.piiMaskingService = piiMaskingService;
    }

    /** What a brand-new organization's first project is called until its owner renames it. */
    public static final String FIRST_PROJECT_NAME = "My first project";

    @Auditable(action = AuditAction.CREATE, resourceType = "Project")
    @Transactional
    public ProjectResponse createProject(ProjectRequest request) {
        return ProjectResponse.of(save(TenantContext.require(), request.getName(), request.getDescription()));
    }

    /**
     * The project a new organization starts with, created while its account is registered.
     *
     * <p>Every section of the dashboard is scoped to a project, so an organization with none opens
     * onto a sidebar where nothing leads anywhere until its owner has worked out that a project is
     * the thing to make first. Registration runs before any tenant scope exists, so the
     * organization is the row just created rather than the caller's; the project is built by the
     * same {@link #save} the Projects page uses, and counts toward the plan's project quota like any
     * other.
     */
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
        // Every project starts masking email, phone and card numbers in the dashboard, as the docs
        // promise; a project with no rules showed customer addresses in full.
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

        project.setDeletedAt(Instant.now());
        projectRepository.save(project);
    }

}
