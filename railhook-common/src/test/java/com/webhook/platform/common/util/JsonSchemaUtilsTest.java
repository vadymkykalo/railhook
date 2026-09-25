package com.webhook.platform.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaUtilsTest {

    @Test
    void inferSchema_typesEachPrimitiveAndRequiresEveryField() throws Exception {
        JsonNode schema = JsonSchemaUtils.inferSchema(
                "{\"name\": \"Alice\", \"age\": 30, \"active\": true, \"price\": 19.99, \"data\": null}");

        assertEquals("object", schema.get("type").asText());
        JsonNode properties = schema.get("properties");
        assertEquals("string", properties.get("name").get("type").asText());
        assertEquals("integer", properties.get("age").get("type").asText());
        assertEquals("boolean", properties.get("active").get("type").asText());
        assertEquals("number", properties.get("price").get("type").asText());
        assertEquals("null", properties.get("data").get("type").asText());
        assertEquals(5, schema.get("required").size());
    }

    @Test
    void inferSchema_descendsIntoObjectsAndArrays() throws Exception {
        JsonNode schema = JsonSchemaUtils.inferSchema(
                "{\"user\": {\"name\": \"Bob\"}, \"items\": [{\"id\": 1, \"name\": \"item1\"}]}");

        JsonNode userProp = schema.get("properties").get("user");
        assertEquals("object", userProp.get("type").asText());
        assertEquals("string", userProp.get("properties").get("name").get("type").asText());
        JsonNode itemsProp = schema.get("properties").get("items");
        assertEquals("array", itemsProp.get("type").asText());
        assertEquals("object", itemsProp.get("items").get("type").asText());
    }

    @Test
    void fingerprint_changesWithTheShapeButNotWithTheDescription() throws Exception {
        String s1 = "{\"type\": \"object\", \"description\": \"v1\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        String s2 = "{\"type\": \"object\", \"description\": \"v2\", \"properties\": {\"name\": {\"type\": \"string\"}}}";
        String s3 = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"integer\"}}}";

        assertEquals(JsonSchemaUtils.fingerprint(s1), JsonSchemaUtils.fingerprint(s1));
        assertEquals(JsonSchemaUtils.fingerprint(s1), JsonSchemaUtils.fingerprint(s2));
        assertNotEquals(JsonSchemaUtils.fingerprint(s1), JsonSchemaUtils.fingerprint(s3));
    }

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
        assertFalse(diff.removed().get(0).required());
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
    void diff_addedRequiredField_isMarkedRequiredAndBreaking() throws Exception {
        String old = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        String nw = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\",\"email\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.added().size());
        assertTrue(diff.added().get(0).required());
        assertTrue(diff.breaking());
    }

    // Removed fields used to carry required=false unconditionally.
    @Test
    void diff_removedRequiredField_isMarkedRequired() throws Exception {
        String old = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"email\":{\"type\":\"string\"}},\"required\":[\"name\",\"email\"]}";
        String nw = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";
        JsonSchemaUtils.SchemaDiff diff = JsonSchemaUtils.diff(old, nw);

        assertEquals(1, diff.removed().size());
        assertTrue(diff.removed().get(0).required());
    }

    // Reading the root's required array put required=false on a field the nested object requires.
    @Test
    void diff_requiredIsReadFromTheOwningObject_notTheRoot() throws Exception {
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

    @Test
    void validate_validPayload_noErrors() {
        String schema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"age\": {\"type\": \"integer\"}}, \"required\": [\"name\"]}";
        assertTrue(JsonSchemaUtils.validate("{\"name\": \"Alice\", \"age\": 30}", schema).isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}, \"age\": {\"type\": \"integer\"}}, \"required\": [\"name\", \"age\"]} | {\"name\": \"Alice\"} | age",
            "{\"type\": \"object\", \"properties\": {\"age\": {\"type\": \"integer\"}}, \"required\": [\"age\"]} | {\"age\": \"not a number\"} | expected integer",
            "{\"type\": \"object\", \"properties\": {\"user\": {\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}}, \"required\": [\"user\"]} | {\"user\": {}} | $.user.name",
            "{\"type\": \"object\"} | \"just a string\" | expected object",
            "{\"type\": \"object\"} | not json | Invalid JSON",
            "{\"type\": \"object\", \"properties\": {\"items\": {\"type\": \"array\", \"items\": {\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}, \"required\": [\"id\"]}}}, \"required\": [\"items\"]} | {\"items\": [{\"id\": 1}, {\"id\": \"bad\"}]} | [1]",
    })
    void validate_reportsOneErrorNamingWhereItIs(String schema, String payload, String expectedFragment) {
        List<String> errors = JsonSchemaUtils.validate(payload, schema);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains(expectedFragment), errors.get(0));
    }
}
