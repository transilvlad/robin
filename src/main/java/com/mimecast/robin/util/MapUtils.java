package com.mimecast.robin.util;

import java.util.List;
import java.util.Map;

/**
 * Map utils.
 */
@SuppressWarnings("unchecked")
public class MapUtils {

    /**
     * Flatten a JSON document of any shape into "path: value" lines.
     *
     * <p>An object is flattened like {@link #flattenMap}, a top-level array uses each index as
     * the first path segment ("0: value", "1>name: value"), and a scalar becomes one line.
     * An empty object or array adds nothing.
     *
     * @param json      JSON text.
     * @param collector List of string to collect results.
     * @throws com.google.gson.JsonSyntaxException When the text is not valid JSON.
     */
    public static void flattenJson(String json, List<String> collector) {
        Object parsed = new com.google.gson.Gson().fromJson(json, Object.class);
        if (parsed instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flattenObject(list.get(i), i + ">", collector);
            }
        } else if (parsed instanceof Map) {
            flattenMap((Map<String, Object>) parsed, "", collector);
        } else if (parsed != null) {
            collector.add(String.valueOf(parsed));
        }
    }

    /**
     * Flatten map.
     *
     * @param map        Map of String, Object instance to flatten.
     * @param precedence Preceding precedence.
     * @param collector  List of string to collect results.
     */
    public static void flattenMap(Map<String, Object> map, String precedence, List<String> collector) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            flattenObject(entry.getValue(), precedence + entry.getKey() + ">", collector);
        }
    }

    /**
     * Flatten list.
     *
     * @param list       List of String instance to flatten.
     * @param precedence Preceding precedence.
     * @param collector  List of string to collect results.
     */
    public static void flattenList(List<String> list, String precedence, List<String> collector) {
        for (int i = 0; i < list.size(); i++) {
            flattenObject(list.get(i), precedence + String.valueOf(i) + ">", collector);
        }
    }

    /**
     * Flatten object.
     *
     * @param object     Object instance to flatten.
     * @param precedence Preceding precedence.
     * @param collector  List of string to collect results.
     */
    @SuppressWarnings("unchecked")
    protected static void flattenObject(Object object, String precedence, List<String> collector) {
        if (object instanceof Map) {
            flattenMap((Map<String, Object>) object, precedence, collector);

        } else {
            String endPrecedence = precedence.replaceAll(">$", ": ");

            if (object instanceof List) {
                if (!((List<?>) object).isEmpty()) {
                    flattenList((List) object, precedence, collector);

                } else {
                    collector.add(endPrecedence + String.valueOf(object));
                }

            } else {
                collector.add(endPrecedence + String.valueOf(object));
            }
        }
    }
}
