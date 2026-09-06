package com.webhook.platform.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Utility for JSON Schema operations: inference from payloads, fingerprinting,
 * diff computation, breaking change detection, and payload validation.
 */
public final class JsonSchemaUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSchemaUtils() {
    }

    // ── Schema Inference ──

    /**
     * Infers a JSON Schema (draft-07 style) from a sample payload.
     * Returns a schema object with type, properties, required fields.
     */
    public static ObjectNode inferSchema(String payloadJson) throws JsonProcessingException {
        JsonNode payload = MAPPER.readTree(payloadJson);
        return inferNodeSchema(payload);
    }

    private static ObjectNode inferNodeSchema(JsonNode node) {
        ObjectNode schema = MAPPER.createObjectNode();

        if (node.isObject()) {
            schema.put("type", "object");
            ObjectNode properties = MAPPER.createObjectNode();
            ArrayNode required = MAPPER.createArrayNode();

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                properties.set(field.getKey(), inferNodeSchema(field.getValue()));
                required.add(field.getKey());
            }

            schema.set("properties", properties);
            if (required.size() > 0) {
                schema.set("required", required);
            }
            schema.put("additionalProperties", true);
        } else if (node.isArray()) {
            schema.put("type", "array");
            if (node.size() > 0) {
                schema.set("items", inferNodeSchema(node.get(0)));
            }
        } else if (node.isTextual()) {
            schema.put("type", "string");
        } else if (node.isInt() || node.isLong()) {
            schema.put("type", "integer");
        } else if (node.isFloat() || node.isDouble() || node.isBigDecimal()) {
            schema.put("type", "number");
        } else if (node.isBoolean()) {
            schema.put("type", "boolean");
        } else if (node.isNull()) {
            schema.put("type", "null");
        }

        return schema;
    }

    // ── Fingerprinting ──

    /**
     * Computes a SHA-256 fingerprint of a normalized JSON Schema.
     * Normalization: remove description/title fields, sort keys deterministically.
     */
    public static String fingerprint(String schemaJson) throws JsonProcessingException {
        JsonNode schema = MAPPER.readTree(schemaJson);
        JsonNode normalized = normalizeForFingerprint(schema);
        String canonical = MAPPER.writeValueAsString(normalized);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static JsonNode normalizeForFingerprint(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                // strip cosmetic fields
                if ("description".equals(key) || "title".equals(key) || "$comment".equals(key)) {
                    continue;
                }
                sorted.put(key, normalizeForFingerprint(field.getValue()));
            }
            ObjectNode result = MAPPER.createObjectNode();
            sorted.forEach(result::set);
            return result;
        } else if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                result.add(normalizeForFingerprint(element));
            }
            return result;
        }
        return node;
    }

    // ── Schema Diff ──

    /**
     * Computes the diff between two JSON Schemas.
     *
     * <p>Five buckets, because a compatibility rule has to tell them apart: properties the new
     * schema added, properties it dropped, properties whose type it changed, properties it made
     * required that were optional before ({@code tightened}), and properties it made optional
     * that were required before ({@code relaxed}). Whether a property is required is read from
     * the {@code required} array of the object that owns it, not the root's — a nested object
     * has its own, and reading the root's marked every nested field optional.
     *
     * <p>{@code breaking} is the union of the four that cost somebody something: it answers
     * "would any consumer of either schema notice?", which is what the schema-changes board
     * shows. The narrower question — "is this allowed under the compatibility mode this version
     * declares?" — is answered by {@code CompatibilityMode}, which reads the buckets separately,
     * because the two directions disagree about which of them matter.
     */
    public static SchemaDiff diff(String oldSchemaJson, String newSchemaJson) throws JsonProcessingException {
        JsonNode oldSchema = MAPPER.readTree(oldSchemaJson);
        JsonNode newSchema = MAPPER.readTree(newSchemaJson);

        List<FieldChange> added = new ArrayList<>();
        List<FieldChange> removed = new ArrayList<>();
        List<FieldChange> changed = new ArrayList<>();
        List<FieldChange> tightened = new ArrayList<>();
        List<FieldChange> relaxed = new ArrayList<>();

        Map<String, Property> oldProps = flattenProperties(oldSchema, "$");
        Map<String, Property> newProps = flattenProperties(newSchema, "$");

        for (Map.Entry<String, Property> entry : newProps.entrySet()) {
            Property newProp = entry.getValue();
            Property oldProp = oldProps.get(entry.getKey());

            if (oldProp == null) {
                added.add(new FieldChange(entry.getKey(), newProp.type(), null, newProp.required()));
                continue;
            }
            if (!oldProp.type().equals(newProp.type())) {
                changed.add(new FieldChange(entry.getKey(), newProp.type(), oldProp.type(), newProp.required()));
            }
            if (!oldProp.required() && newProp.required()) {
                tightened.add(new FieldChange(entry.getKey(), newProp.type(), oldProp.type(), true));
            } else if (oldProp.required() && !newProp.required()) {
                relaxed.add(new FieldChange(entry.getKey(), newProp.type(), oldProp.type(), false));
            }
        }

        for (Map.Entry<String, Property> entry : oldProps.entrySet()) {
            if (!newProps.containsKey(entry.getKey())) {
                Property oldProp = entry.getValue();
                removed.add(new FieldChange(entry.getKey(), null, oldProp.type(), oldProp.required()));
            }
        }

        boolean breaking = !removed.isEmpty()
                || !changed.isEmpty()
                || !tightened.isEmpty()
                || added.stream().anyMatch(FieldChange::required);

        return new SchemaDiff(added, removed, changed, tightened, relaxed, breaking);
    }

    /**
     * Serializes a SchemaDiff to JSON string for storage.
     */
    public static String diffToJson(SchemaDiff diff) {
        try {
            ObjectNode json = MAPPER.createObjectNode();
            json.set("added", MAPPER.valueToTree(diff.added));
            json.set("removed", MAPPER.valueToTree(diff.removed));
            json.set("changed", MAPPER.valueToTree(diff.changed));
            json.set("tightened", MAPPER.valueToTree(diff.tightened));
            json.set("relaxed", MAPPER.valueToTree(diff.relaxed));
            json.put("breaking", diff.breaking);
            return MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ── Payload Validation ──

    /**
     * Validates a payload against a JSON Schema.
     * Returns a list of validation errors. Empty list = valid.
     * <p>
     * This is a lightweight validator that checks:
     * - required fields present
     * - type matches (string, number, integer, boolean, array, object, null)
     * - nested object validation
     */
    public static List<String> validate(String payloadJson, String schemaJson) {
        try {
            JsonNode payload = MAPPER.readTree(payloadJson);
            JsonNode schema = MAPPER.readTree(schemaJson);
            List<String> errors = new ArrayList<>();
            validateNode(payload, schema, "$", errors);
            return errors;
        } catch (JsonProcessingException e) {
            return List.of("Invalid JSON: " + e.getMessage());
        }
    }

    private static void validateNode(JsonNode payload, JsonNode schema, String path, List<String> errors) {
        String expectedType = schema.has("type") ? schema.get("type").asText() : null;

        if (expectedType != null && !typeMatches(payload, expectedType)) {
            errors.add(path + ": expected " + expectedType + " but got " + inferType(payload));
            return;
        }

        if ("object".equals(expectedType) && payload.isObject()) {
            // Check required fields
            if (schema.has("required") && schema.get("required").isArray()) {
                for (JsonNode req : schema.get("required")) {
                    String fieldName = req.asText();
                    if (!payload.has(fieldName)) {
                        errors.add(path + "." + fieldName + ": required field missing");
                    }
                }
            }

            // Validate each property
            if (schema.has("properties") && schema.get("properties").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> props = schema.get("properties").fields();
                while (props.hasNext()) {
                    Map.Entry<String, JsonNode> prop = props.next();
                    if (payload.has(prop.getKey())) {
                        validateNode(payload.get(prop.getKey()), prop.getValue(),
                                path + "." + prop.getKey(), errors);
                    }
                }
            }
        }

        if ("array".equals(expectedType) && payload.isArray() && schema.has("items")) {
            JsonNode itemSchema = schema.get("items");
            for (int i = 0; i < payload.size(); i++) {
                validateNode(payload.get(i), itemSchema, path + "[" + i + "]", errors);
            }
        }
    }

    private static boolean typeMatches(JsonNode node, String type) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" -> node.isInt() || node.isLong();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    private static String inferType(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isInt() || node.isLong()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "unknown";
    }

    // ── Helpers ──

    /**
     * Every property in the schema, keyed by JSONPath, each carrying its declared type and
     * whether the object that owns it lists it as required.
     */
    private static Map<String, Property> flattenProperties(JsonNode schema, String prefix) {
        Map<String, Property> result = new LinkedHashMap<>();
        if (schema.has("properties") && schema.get("properties").isObject()) {
            Set<String> required = extractRequired(schema);
            Iterator<Map.Entry<String, JsonNode>> fields = schema.get("properties").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String path = prefix + "." + field.getKey();
                result.put(path, new Property(getType(field.getValue()), required.contains(field.getKey())));
                // Recurse into nested objects
                if (field.getValue().has("type") && "object".equals(field.getValue().get("type").asText())) {
                    result.putAll(flattenProperties(field.getValue(), path));
                }
            }
        }
        return result;
    }

    private static Set<String> extractRequired(JsonNode schema) {
        Set<String> required = new HashSet<>();
        if (schema.has("required") && schema.get("required").isArray()) {
            for (JsonNode r : schema.get("required")) {
                required.add(r.asText());
            }
        }
        return required;
    }

    private static String getType(JsonNode propSchema) {
        if (propSchema.has("type")) {
            return propSchema.get("type").asText();
        }
        return "unknown";
    }

    private record Property(String type, boolean required) {}

    // ── Data classes ──

    public record FieldChange(String path, String type, String oldType, boolean required) {}

    /**
     * @param added     properties the new schema has and the old one did not
     * @param removed   properties the old schema had and the new one does not
     * @param changed   properties whose declared type differs between the two
     * @param tightened properties the new schema requires that the old one left optional
     * @param relaxed   properties the new schema leaves optional that the old one required
     * @param breaking  true when a consumer of either schema could notice the difference
     */
    public record SchemaDiff(
            List<FieldChange> added,
            List<FieldChange> removed,
            List<FieldChange> changed,
            List<FieldChange> tightened,
            List<FieldChange> relaxed,
            boolean breaking
    ) {}
}
