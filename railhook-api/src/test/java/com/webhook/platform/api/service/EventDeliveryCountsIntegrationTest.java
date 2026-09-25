package com.webhook.platform.api.service;

import com.webhook.platform.api.AbstractIntegrationTest;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Organization;
import com.webhook.platform.api.domain.entity.Plan;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.OrganizationRepository;
import com.webhook.platform.api.domain.repository.PlanRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.DeliveryStatusCounts;
import com.webhook.platform.api.dto.EventResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The dashboard once guessed these from the project's newest 200 Deliveries.
class EventDeliveryCountsIntegrationTest extends AbstractIntegrationTest {

    private static final int EVENTS = 30;
    private static final int PAGE_SIZE = 10;
    private static final int ENDPOINTS = 15;

    @Autowired private EventService eventService;
    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private PlanRepository planRepository;

    private UUID orgId;

    @BeforeEach
    void seedOrganization() {
        Plan plan = planRepository.findByName("self_hosted")
                .orElseGet(() -> planRepository.findAll().stream().findFirst().orElseThrow());
        orgId = organizationRepository.save(
                Organization.builder().name("Acme " + UUID.randomUUID()).plan(plan).build()).getId();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void everyEventOnEveryPageCarriesTheExactCountsOfItsOwnDeliveries() {
        UUID projectId = project("Payments");
        UUID neighbourId = project("Shipping");
        List<UUID> endpoints = endpoints(projectId);
        List<UUID> neighbourEndpoints = endpoints(neighbourId);

        Map<UUID, DeliveryStatusCounts> expected = new HashMap<>();
        int deliveriesInProject = 0;
        for (int i = 0; i < EVENTS; i++) {
            DeliveryStatusCounts counts = DeliveryStatusCounts.builder()
                    .pending(i % 3)
                    .processing(i % 2)
                    .success(5 + i % 4)
                    .failed(i % 2 == 0 ? 1 : 0)
                    .dlq(i % 5 == 0 ? 2 : 0)
                    .build();
            UUID eventId = eventWithDeliveries(projectId, endpoints, counts);
            expected.put(eventId, counts);
            deliveriesInProject += counts.getPending() + counts.getProcessing() + counts.getSuccess()
                    + counts.getFailed() + counts.getDlq();

            // Created in between, so a window over creation time would take these in too.
            eventWithDeliveries(neighbourId, neighbourEndpoints, DeliveryStatusCounts.builder()
                    .pending(0).processing(0).success(ENDPOINTS).failed(0).dlq(0).build());
        }
        assertThat(deliveriesInProject).isGreaterThan(200);

        TenantContext.set(orgId);
        Map<UUID, EventResponse> seen = new HashMap<>();
        for (int page = 0; page < EVENTS / PAGE_SIZE; page++) {
            Page<EventResponse> events = eventService.listEvents(projectId, null,
                    PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
            events.getContent().forEach(e -> seen.put(e.getId(), e));
        }

        assertThat(seen.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((eventId, counts) -> {
            EventResponse event = seen.get(eventId);
            assertThat(event.getDeliveryCounts()).as("counts of event %s", eventId)
                    .usingRecursiveComparison().isEqualTo(counts);
            assertThat(event.getDeliveriesCreated()).isEqualTo(counts.getPending() + counts.getProcessing()
                    + counts.getSuccess() + counts.getFailed() + counts.getDlq());
        });
    }

    @Test
    void anEventNobodySubscribedToCountsZeroOfEveryStatus() {
        UUID projectId = project("Payments");
        UUID eventId = eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(projectId)
                .eventType("order.created").payload("{}").build()).getId();

        TenantContext.set(orgId);
        EventResponse event = eventService.listEvents(projectId, null, PageRequest.of(0, PAGE_SIZE))
                .getContent().get(0);

        assertThat(event.getId()).isEqualTo(eventId);
        assertThat(event.getDeliveriesCreated()).isZero();
        assertThat(event.getDeliveryCounts()).usingRecursiveComparison().isEqualTo(DeliveryStatusCounts.builder()
                .pending(0).processing(0).success(0).failed(0).dlq(0).build());
    }

    private UUID project(String name) {
        return projectRepository.save(Project.builder().organizationId(orgId).name(name).build()).getId();
    }

    private List<UUID> endpoints(UUID projectId) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < ENDPOINTS; i++) {
            ids.add(endpointRepository.save(Endpoint.builder()
                    .organizationId(orgId).projectId(projectId)
                    .url("https://example.test/hook/" + i)
                    .secretEncrypted("encrypted").secretIv("iv").build()).getId());
        }
        return ids;
    }

    private UUID eventWithDeliveries(UUID projectId, List<UUID> endpoints, DeliveryStatusCounts counts) {
        Event event = eventRepository.save(Event.builder()
                .organizationId(orgId).projectId(projectId)
                .eventType("payment.succeeded").payload("{}").build());
        List<DeliveryStatus> statuses = new ArrayList<>();
        addTimes(statuses, DeliveryStatus.PENDING, counts.getPending());
        addTimes(statuses, DeliveryStatus.PROCESSING, counts.getProcessing());
        addTimes(statuses, DeliveryStatus.SUCCESS, counts.getSuccess());
        addTimes(statuses, DeliveryStatus.FAILED, counts.getFailed());
        addTimes(statuses, DeliveryStatus.DLQ, counts.getDlq());
        List<Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            deliveries.add(Delivery.builder()
                    .organizationId(orgId)
                    .eventId(event.getId())
                    .endpointId(endpoints.get(i))
                    .status(statuses.get(i))
                    .build());
        }
        deliveryRepository.saveAllAndFlush(deliveries);
        return event.getId();
    }

    private static void addTimes(List<DeliveryStatus> statuses, DeliveryStatus status, int times) {
        for (int i = 0; i < times; i++) {
            statuses.add(status);
        }
    }
}
