package com.mimecast.robin.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates every JSON5 test case in the repository against the case schema.
 *
 * <p>Robin does not validate cases at runtime, and unknown keys are silently ignored.
 * This keeps the schema, the documentation and the shipped cases from drifting apart.
 * The validator supports the draft-07 subset the case schema uses.
 */
class CaseFilesSchemaTest {

    private static final Path SCHEMA = Path.of("src/main/resources/schema/case.schema.json");
    private static final Path CASES = Path.of("src/test/resources/cases/config");

    @TestFactory
    Stream<DynamicTest> caseFilesMatchSchema() throws IOException {
        Map<String, Object> schema = load(SCHEMA);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CASES)) {
            files = walk.filter(p -> p.toString().endsWith(".json5")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "No case files found under " + CASES);

        return files.stream().map(file -> DynamicTest.dynamicTest(CASES.relativize(file).toString(), () -> {
            List<String> errors = new SchemaValidator(schema).validate(load(file));
            assertTrue(errors.isEmpty(), file + " does not match the case schema:\n" + String.join("\n", errors));
        }));
    }

    @Test
    void validatorRejectsUnknownKeysAndMissingSource() {
        SchemaValidator validator = new SchemaValidator(load(SCHEMA));

        List<String> errors = validator.validate(parse("{envelopes: [{mail: 'a@b.c', rcpt: ['d@e.f'], sever: 'x'}]}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("sever")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("oneOf")), errors.toString());

        errors = validator.validate(parse("{envelopes: [{file: 'a.eml', assertions: {protocol: [[]]}}]}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("minItems")), errors.toString());

        errors = validator.validate(parse("{envelopes: [{file: 'a.eml', assertions: {external: [{type: 'imap', server: 'x'}]}}]}"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("server")), errors.toString());

        assertEquals(List.of(), validator.validate(parse("{envelopes: [{subject: 's', message: 'm'}]}")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return new Gson().fromJson(json, Map.class);
    }

    private static Map<String, Object> load(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + path, e);
        }
    }

    /**
     * Minimal JSON schema validator for the keywords used by case.schema.json.
     */
    @SuppressWarnings("unchecked")
    static final class SchemaValidator {
        private final Map<String, Object> root;

        SchemaValidator(Map<String, Object> root) {
            this.root = root;
        }

        List<String> validate(Object value) {
            List<String> errors = new ArrayList<>();
            validate(value, root, "$", errors);
            return errors;
        }

        private void validate(Object value, Map<String, Object> schema, String path, List<String> errors) {
            if (schema.containsKey("$ref")) {
                String ref = (String) schema.get("$ref");
                Map<String, Object> definitions = (Map<String, Object>) root.get("definitions");
                validate(value, (Map<String, Object>) definitions.get(ref.substring("#/definitions/".length())), path, errors);
                return;
            }

            if (schema.containsKey("type") && !hasType(value, schema.get("type"))) {
                errors.add(path + ": expected type " + schema.get("type") + " but was " + value);
                return;
            }

            if (schema.containsKey("enum") && !((List<Object>) schema.get("enum")).contains(value)) {
                errors.add(path + ": " + value + " is not one of " + schema.get("enum"));
            }

            if (value instanceof String string && schema.containsKey("minLength")
                    && string.length() < ((Number) schema.get("minLength")).intValue()) {
                errors.add(path + ": shorter than minLength " + schema.get("minLength"));
            }

            if (value instanceof List<?> list) {
                if (schema.containsKey("minItems") && list.size() < ((Number) schema.get("minItems")).intValue()) {
                    errors.add(path + ": fewer than minItems " + schema.get("minItems"));
                }
                if (schema.containsKey("maxItems") && list.size() > ((Number) schema.get("maxItems")).intValue()) {
                    errors.add(path + ": more than maxItems " + schema.get("maxItems"));
                }
                if (schema.get("items") instanceof Map) {
                    for (int i = 0; i < list.size(); i++) {
                        validate(list.get(i), (Map<String, Object>) schema.get("items"), path + "[" + i + "]", errors);
                    }
                }
            }

            if (value instanceof Map<?, ?> map) {
                validateObject((Map<String, Object>) map, schema, path, errors);
            }

            if (schema.containsKey("oneOf")) {
                long valid = ((List<Map<String, Object>>) schema.get("oneOf")).stream()
                        .filter(option -> {
                            List<String> optionErrors = new ArrayList<>();
                            validate(value, option, path, optionErrors);
                            return optionErrors.isEmpty();
                        })
                        .count();
                if (valid != 1) {
                    errors.add(path + ": matches " + valid + " oneOf options, expected exactly 1");
                }
            }
        }

        private void validateObject(Map<String, Object> map, Map<String, Object> schema, String path, List<String> errors) {
            if (schema.containsKey("required")) {
                for (String key : (List<String>) schema.get("required")) {
                    if (!map.containsKey(key)) {
                        errors.add(path + ": missing required " + key);
                    }
                }
            }

            Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            Object additional = schema.get("additionalProperties");
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String childPath = path + "." + entry.getKey();
                if (properties.containsKey(entry.getKey())) {
                    validate(entry.getValue(), (Map<String, Object>) properties.get(entry.getKey()), childPath, errors);
                } else if (Boolean.FALSE.equals(additional)) {
                    errors.add(childPath + ": unknown key");
                } else if (additional instanceof Map) {
                    validate(entry.getValue(), (Map<String, Object>) additional, childPath, errors);
                }
            }
        }

        private static boolean hasType(Object value, Object type) {
            if (type instanceof List<?> types) {
                return types.stream().anyMatch(t -> hasType(value, t));
            }
            return switch ((String) type) {
                case "object" -> value instanceof Map;
                case "array" -> value instanceof List;
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "number" -> value instanceof Number;
                case "integer" -> value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue());
                default -> false;
            };
        }
    }
}
