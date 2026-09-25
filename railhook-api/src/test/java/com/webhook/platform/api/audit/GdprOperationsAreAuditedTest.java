package com.webhook.platform.api.audit;

import com.webhook.platform.api.service.GdprExportService;
import com.webhook.platform.api.service.OrganizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// An explicit list: which operations answer a data-protection request is a legal judgement.
@Tag("ratchet")
class GdprOperationsAreAuditedTest {

    private static final Object[][] GDPR_OPERATIONS = {
            // Article 17, the right to erasure.
            {OrganizationService.class, "deleteOrganization"},
            // Article 20, the right to data portability.
            {GdprExportService.class, "exportOrganizationData"},
    };

    @Test
    @DisplayName("every data-protection operation records who asked for it")
    void gdprOperationsCarryAuditable() {
        List<String> unaudited = new ArrayList<>();

        for (Object[] operation : GDPR_OPERATIONS) {
            Class<?> type = (Class<?>) operation[0];
            String methodName = (String) operation[1];

            Method method = find(type, methodName);
            if (!method.isAnnotationPresent(Auditable.class)) {
                unaudited.add(type.getSimpleName() + "." + methodName);
            }
        }

        assertTrue(unaudited.isEmpty(),
                "These operations answer a data-protection request and leave no audit entry: "
                        + unaudited + ". A regulator's question is 'who asked, and when'; a log line "
                        + "that retention will roll away is not an answer. Annotate with @Auditable.");
    }

    private static Method find(Class<?> type, String methodName) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError(type.getSimpleName() + "." + methodName + " no longer exists. If the "
                + "operation moved, move it in this test too — do not delete the entry, or the "
                + "requirement quietly disappears with it.");
    }
}
