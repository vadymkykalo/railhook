package com.webhook.platform.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── inferSchema ──

    @Test
    void inferSchema_objectWithPrimitives() throws Exception {
        String payload = "{\"name\": \"Alice\", \"age\": 30, \"active\": true}";
        JsonNode schema = JsonSchemaUtils.inferSchema(payload);

        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.has("properties"));
        assertEquals("string", schema.get("properties").get("name").get("type").asText());
        assertEquals("integer", schema.get("properties").get("age").get("type").asText());
        assertEquals("boolean", schema.get("properties").get("active").get("type").asText());
        assertNotNull(schema.get("required"));
        assertEquals(3, schema.get("required").size());
    }

    @Test
    void inferSchema_nestedObject() throws Exception {
        String payload = "{\"user\": {\"name\": \"Bob\", \"email\": \"bob@test.com\"}}";
        JsonNode schema = JsonSchemaUtils.inferSchema(payload);
        JsonNode userProp = schema.get("properties").get("user");

        assertEquals("object", userProp.get("type").asText());
        assertEquals("string", userProp.get("properties").get("name").get("type").asText());
        assertEquals("string", userProp.get("properties").get("email").get("type").asText());
    }

    @Test
    void inferSchema_array() throws Exception {
        String payload = "{\"items\": [{\"id\": 1, \"name\": \"item1\"}]}";
        JsonNode schema = JsonSchemaUtils.inferSchema(payload);
        JsonNode itemsProp = schema.get("properties").get("items");

        assertEquals("array", itemsProp.get("type").asText());
        assertNotNull(itemsProp.get("items"));
        assertEquals("object", itemsProp.get("items").get("type").asText());
    }

    @Test
    void inferSchema_nullValue() throws Exception {
        String payload = "{\"data\": null}";
        JsonNode schema = JsonSchemaUtils.inferSchema(payload);
        assertEquals("null", schema.get("properties").get("data").get("type").asText());
    }

    @Test
    void inferSchema_number() throws Exception {
        String payload = "{\"price\": 19.99}";
        JsonNode schema = JsonSchemaUtils.inferSchema(payload);
        assertEquals("number", schema.get("properties").get("price").get("type").asText());
    }

    // ── fingerprint ──

    @Test
    void fingerprint_sameSchema_sameResult() throws Exception {
        String schema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        assertEquals(JsonSchemaUtils.fingerprint(schema), JsonSchemaUtils.fingerprint(schema));
    }

    @Test
    void fingerprint_differentSchema_differentResult() throws Exception {
        String s1 = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        String s2 = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"integer\"}}}";
        assertNotEquals(JsonSchemaUtils.fingerprint(s1), JsonSchemaUtils.fingerprint(s2));
    }

    @Test
    void fingerprint_ignoresDescription() throws Exception {
        String s1 = "{\"type\": \"object\", \"description\": \"v1\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        String s2 = "{\"type\": \"object\", \"description\": \"v2\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        assertEquals(JsonSchemaUtils.fingerprint(s1), JsonSchemaUtils.fingerprint(s2));
    }

    @Test
    void fingerprint_isSha256Length() throws Exception {
        assertEquals(64, JsonSchemaUtils.fingerprint("{\"type\": \"string\"}").length());
    }

    // ── diff ──

    @Test
    void diff_addedOptionalField_notBreaking() throws Exception {
        String old = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        String nw = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"email\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.added().size());
        assertEquals("$.email", diff.added().get(0).path());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.changed().isEmpty());
        assertFalse(diff.breaking());
    }

    @Test
    void diff_removedField_breaking() throws Exception {
        String old = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"email\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        String nw = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.removed().size());
        assertEquals("$.email", diff.removed().get(0).path());
        assertTrue(diff.breaking());
    }

    @Test
    void diff_typeChanged_breaking() throws Exception {
        String old = "{\"type\": \"object\", \"properties\": {\"amount\": {\"type\": \"string\"}}, \"required\": [\"amount\"]}";
        String nw = "{\"type\": \"object\", \"properties\": {\"amount\": {\"type\": \"number\"}}, \"required\": [\"amount\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.changed().size());
        assertEquals("number", diff.changed().get(0).type());
        assertEquals("string", diff.changed().get(0).oldType());
        assertTrue(diff.breaking());
    }

    @Test
    void diff_noChanges() throws Exception {
        String schema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(schema, schema);

        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removed().isEmpty());
        assertTrue(diff.changed().isEmpty());
        assertFalse(diff.breaking());
    }

    @Test
    void diff_addedRequiredField_breaking() throws Exception {
        String old = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        String nw = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"age\": {\"type\": \"integer\"}}, \"required\": [\"name\", \"age\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.added().size());
        assertTrue(diff.added().get(0).required());
        assertTrue(diff.breaking());
    }

    // ── diffToJson ──

    @Test
    void diffToJson_validJson() throws Exception {
        String old = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        String nw = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"email\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        String json = JsonSchemaUtils.diffToJson(JsonSchemaUtils.diff(old, nw));

        JsonNode parsed = MAPPER.readTree(json);
        assertTrue(parsed.has("added"));
        assertTrue(parsed.has("removed"));
        assertTrue(parsed.has("changed"));
        assertTrue(parsed.has("breaking"));
    }

    // ── diff: which side of a change was required ──

    @Test
    void diff_addedRequiredField_isMarkedRequired() throws Exception {
        String old = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        String nw = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\",\"email\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.added().size());
        assertTrue(diff.added().get(0).required());
        assertTrue(diff.breaking());
    }

    @Test
    void diff_removedRequiredField_isMarkedRequired() throws Exception {
        String old = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\",\"email\"]}";
        String nw = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.removed().size());
        assertTrue(diff.removed().get(0).required(),
                "removed fields carried required=false unconditionally, so nothing could tell a "
                        + "dropped optional field from a dropped required one");
    }

    @Test
    void diff_removedOptionalField_isNotMarkedRequired() throws Exception {
        String old = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"nickname\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        String nw = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.removed().size());
        assertFalse(diff.removed().get(0).required());
    }

    @Test
    void diff_requiredIsReadFromTheOwningObject_notTheRoot() throws Exception {
        // "id" is required inside `user`, and the root requires nothing of that name. Reading
        // the root's required array put required=false on it.
        String old = "{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\","
                + "\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}}}";
        String nw = "{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\","
                + "\"properties\":{\"name\":{\"type\":\"string\"},\"id\":{\"type\":\"string\"}},"
                + "\"required\":[\"name\",\"id\"]}}}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.added().size());
        assertEquals("$.user.id", diff.added().get(0).path());
        assertTrue(diff.added().get(0).required());
    }

    @Test
    void diff_aFieldThatBecameRequiredCountsAsAddingARequirement() throws Exception {
        String old = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        String nw = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\",\"email\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.tightened().size());
        assertEquals("$.email", diff.tightened().get(0).path());
        assertTrue(diff.breaking());
    }

    @Test
    void diff_aFieldThatBecameOptionalIsNotBreakingOnItsOwn() throws Exception {
        String old = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\",\"email\"]}";
        String nw = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.relaxed().size());
        assertEquals("$.email", diff.relaxed().get(0).path());
    }

    // ── validate ──

    @Test
    void validate_validPayload_noErrors() {
        String schema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"age\": {\"type\": \"integer\"}}, \"required\": [\"name\"]}";
        String payload = "{\"name\": \"Alice\", \"age\": 30}";
        assertTrue(JsonSchemaUtils.validate(payload, schema).isEmpty());
    }

    @Test
    void validate_missingRequiredField() {
        String schema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"age\": {\"type\": \"integer\"}}, \"required\": [\"name\", \"age\"]}";
        String payload = "{\"name\": \"Alice\"}";
        List<String> errors = JsonSchemaUtils.validate(payload, schema);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("age"));
        assertTrue(errors.get(0).contains("required"));
    }

    @Test
    void validate_wrongType() {
        String schema = "{\"type\": \"object\", \"properties\": {\"age\": {\"type\": \"integer\"}}, \"required\": [\"age\"]}";
        String payload = "{\"age\": \"not a number\"}";
        List<String> errors = JsonSchemaUtils.validate(payload, schema);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("expected integer"));
        assertTrue(errors.get(0).contains("string"));
    }

    @Test
    void validate_nestedObject() {
        String schema = "{\"type\": \"object\", \"properties\": {\"user\": {\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}}, \"required\": [\"user\"]}";
        String payload = "{\"user\": {}}";
        List<String> errors = JsonSchemaUtils.validate(payload, schema);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("$.user.name"));
    }

    @Test
    void validate_wrongRootType() {
        String schema = "{\"type\": \"object\"}";
        String payload = "\"just a string\"";
        List<String> errors = JsonSchemaUtils.validate(payload, schema);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("expected object"));
    }

    @Test
    void validate_invalidJson() {
        List<String> errors = JsonSchemaUtils.validate("not json", "{\"type\": \"object\"}");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Invalid JSON"));
    }

    @Test
    void validate_arrayItems() {
        String schema = "{\"type\": \"object\", \"properties\": {\"items\": {\"type\": \"array\", \"items\": {\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}, \"required\": [\"id\"]}}}, \"required\": [\"items\"]}";
        String payload = "{\"items\": [{\"id\": 1}, {\"id\": \"bad\"}]}";
        List<String> errors = JsonSchemaUtils.validate(payload, schema);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("[1]"));
    }
}
