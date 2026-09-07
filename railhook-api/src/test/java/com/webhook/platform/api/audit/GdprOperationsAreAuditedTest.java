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

/**
 * Ratchet over the operations a data-protection request actually asks for.
 *
 * <p>Every one of these has to be answerable months later, by someone who was not there: who
 * asked, when, and did it succeed. That is what the audit log is for, and it is exactly what
 * these two had no entry in — {@code deleteOrganization} carries a javadoc citing Article 17
 * and destroys every row a customer has, and left nothing behind but a log line that the
 * retention policy will eventually roll away.
 *
 * <p>Kept as an explicit list rather than a rule inferred from names, because "this is a data
 * subject's right, not just another write" is a judgement about the law and not a property the
 * signature carries. Adding an operation here is how it becomes enforced.
 */
@Tag("ratchet")
class GdprOperationsAreAuditedTest {

    /**
     * The operations that answer a data-protection request, and must therefore leave a record
     * of who asked. {@code {class, method}}.
     */
    private static final Object[][] GDPR_OPERATIONS = {
            // Article 17, the right to erasure. Destroys the organization and, by cascade, every
            // project, endpoint, delivery and event under it. Irreversible.
            {OrganizationService.class, "deleteOrganization"},
            // Article 20, the right to data portability. Not destructive, but it puts every
            // member, project, endpoint and key into one downloadable file, so who asked for it
            // is worth as much as who deleted something.
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
