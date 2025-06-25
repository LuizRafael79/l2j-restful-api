package custom.apiserver.util;

import java.util.List;
import java.util.Map;

/**
 * JsonBuilder is a utility class for building JSON strings manually in an
 * efficient, simple, and fluent way without relying on external libraries.
 * 
 * <p>Supports creating JSON objects with fields of types String, Number,
 * Boolean, null, nested objects, JSON arrays, as well as Map and List.</p>
 * 
 * <p>Includes the inner {@link JsonArrayBuilder} class for building JSON arrays.</p>
 * 
 * <h3>Usage example:</h3>
 * <pre>{@code
 * JsonBuilder obj = JsonBuilder.object()
 *     .appendField("name", "Someone")
 *     .appendField("age", 47)
 *     .appendArrayField("scores", new JsonBuilder.JsonArrayBuilder()
 *         .add(100)
 *         .add(95)
 *         .add(88));
 * String json = obj.build();
 * }</pre>
 * 
 * @author Luiz
 * @since 2025
 */
public class JsonBuilder {
    private final StringBuilder sb;
    private boolean hasFields;
    private boolean isBuilt;

    private JsonBuilder() {
        this.sb = new StringBuilder();
        this.hasFields = false;
        this.isBuilt = false;
        sb.append("{");
    }

    public static JsonBuilder object() {
        return new JsonBuilder();
    }

    public JsonBuilder appendField(String key, String value) {
        checkNotBuilt();
        appendCommaIfNeeded();
        sb.append("\"").append(escape(key)).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(escapeUnicode(value)).append("\"");
        }
        return this;
    }

    public JsonBuilder appendField(String key, Number value) {
        checkNotBuilt();
        appendCommaIfNeeded();
        sb.append("\"").append(escape(key)).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        return this;
    }

    public JsonBuilder appendField(String key, Boolean value) {
        checkNotBuilt();
        appendCommaIfNeeded();
        sb.append("\"").append(escape(key)).append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
        return this;
    }

    public JsonBuilder appendField(String key, Map<String, Object> map) {
        checkNotBuilt();
        appendCommaIfNeeded();
        sb.append("\"").append(escape(key)).append("\":");
        sb.append(buildFromMap(map));
        return this;
    }

    public JsonBuilder appendField(String key, List<Object> list) {
        checkNotBuilt();
        appendCommaIfNeeded();
        sb.append("\"").append(escape(key)).append("\":");
        sb.append(buildFromList(list));
        return this;
    }

    public JsonBuilder appendNullField(String key) {
        checkNotBuilt();
        appendCommaIfNeeded();
        sb.append("\"").append(escape(key)).append("\":null");
        return this;
    }

    public JsonBuilder appendRawField(String key, String rawJson) {
        checkNotBuilt();
        appendCommaIfNeeded();
        sb.append("\"").append(escape(key)).append("\":").append(rawJson);
        return this;
    }

    public JsonBuilder appendObjectField(String key, JsonBuilder nestedObject) {
        checkNotBuilt();
        return appendRawField(key, nestedObject.build());
    }

    public JsonBuilder appendArrayField(String key, JsonArrayBuilder array) {
        checkNotBuilt();
        return appendRawField(key, array.build());
    }

    private void appendCommaIfNeeded() {
        if (hasFields) {
            sb.append(",");
        } else {
            hasFields = true;
        }
    }

    // Basic escape for key names (usually simple ASCII)
    private static String escape(String s) {
        if (s == null) return null;
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // Escape Unicode characters to correct format
    private static String escapeUnicode(String s) {
        if (s == null) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private void checkNotBuilt() {
        if (isBuilt) {
            throw new IllegalStateException("JsonBuilder is already built, cannot append");
        }
    }

    /**
     * Finalizes the JSON object construction and returns the JSON string.
     * Multiple calls return the same string.
     * @return the JSON string representation
     */
    public String build() {
        if (!isBuilt) {
            sb.append("}");
            isBuilt = true;
        }
        return sb.toString();
    }

    // --- Helpers to build from Map and List ---

    private static String buildFromMap(Map<String, Object> map) {
        if (map == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            sb.append(toJsonValue(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String buildFromList(List<Object> list) {
        if (list == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(",");
            first = false;
            sb.append(toJsonValue(o));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String toJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            return "\"" + escapeUnicode((String) value) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map) {
            //noinspection unchecked
            return buildFromMap((Map<String, Object>) value);
        }
        if (value instanceof List) {
            //noinspection unchecked
            return buildFromList((List<Object>) value);
        }
        if (value instanceof JsonBuilder) {
            return ((JsonBuilder) value).build();
        }
        if (value instanceof JsonArrayBuilder) {
            return ((JsonArrayBuilder) value).build();
        }
        // Fallback: convert to string escaped
        return "\"" + escapeUnicode(value.toString()) + "\"";
    }

    /**
     * Parses a JSON object string into a Map<String, Object>.
     * Supports nested objects and arrays.
     * Throws RuntimeException on malformed JSON.
     */
    public static Map<String, Object> parseJsonObject(String json) {
        JsonParser parser = new JsonParser(json);
        Map<String, Object> result = parser.parseObject();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new RuntimeException("Unexpected trailing characters after JSON object");
        }
        return result;
    }

    private static class JsonParser {
        private final String json;
        private int pos;

        JsonParser(String json) {
            this.json = json.trim();
            this.pos = 0;
        }

        boolean isEnd() {
            return pos >= json.length();
        }

        Map<String, Object> parseObject() {
            skipWhitespace();
            if (pos >= json.length() || json.charAt(pos) != '{') throw error("Expected '{'");
            pos++;
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (pos >= json.length() || json.charAt(pos) != ':') throw error("Expected ':'");
                pos++;
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos >= json.length()) throw error("Expected ',' or '}'");
                char c = json.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                } else if (c == '}') {
                    pos++;
                    break;
                } else {
                    throw error("Expected ',' or '}'");
                }
            }
            return map;
        }

        List<Object> parseArray() {
            skipWhitespace();
            if (pos >= json.length() || json.charAt(pos) != '[') throw error("Expected '['");
            pos++;
            List<Object> list = new java.util.ArrayList<>();
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                Object val = parseValue();
                list.add(val);
                skipWhitespace();
                if (pos >= json.length()) throw error("Expected ',' or ']'");
                char c = json.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                } else if (c == ']') {
                    pos++;
                    break;
                } else {
                    throw error("Expected ',' or ']'");
                }
            }
            return list;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= json.length()) throw error("Unexpected end of input");
            char c = json.charAt(pos);
            if (c == '"') {
                return parseString();
            } else if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else if (c == 't') {
                expectLiteral("true");
                return Boolean.TRUE;
            } else if (c == 'f') {
                expectLiteral("false");
                return Boolean.FALSE;
            } else if (c == 'n') {
                expectLiteral("null");
                return null;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            } else {
                throw error("Unexpected character: " + c);
            }
        }

        String parseString() {
            if (pos >= json.length() || json.charAt(pos) != '"') throw error("Expected '\"' at start of string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (pos >= json.length()) throw error("Unexpected end after escape");
                    c = json.charAt(pos++);
                    switch (c) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > json.length()) throw error("Incomplete unicode escape");
                            String hex = json.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                int code = Integer.parseInt(hex, 16);
                                sb.append((char) code);
                            } catch (NumberFormatException e) {
                                throw error("Invalid unicode escape: \\u" + hex);
                            }
                            break;
                        default:
                            throw error("Invalid escape character: \\" + c);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Number parseNumber() {
            int start = pos;
            if (json.charAt(pos) == '-') pos++;
            if (pos >= json.length()) throw error("Invalid number format");
            if (json.charAt(pos) == '0') {
                pos++;
            } else if (json.charAt(pos) >= '1' && json.charAt(pos) <= '9') {
                pos++;
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++;
            } else {
                throw error("Invalid number format");
            }
            if (pos < json.length() && json.charAt(pos) == '.') {
                pos++;
                if (pos >= json.length() || !Character.isDigit(json.charAt(pos))) throw error("Invalid fraction in number");
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++;
            }
            if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
                pos++;
                if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) pos++;
                if (pos >= json.length() || !Character.isDigit(json.charAt(pos))) throw error("Invalid exponent in number");
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) pos++;
            }
            String numberStr = json.substring(start, pos);
            try {
                if (numberStr.contains(".") || numberStr.contains("e") || numberStr.contains("E")) {
                    return Double.parseDouble(numberStr);
                } else {
                    long longVal = Long.parseLong(numberStr);
                    if (longVal <= Integer.MAX_VALUE && longVal >= Integer.MIN_VALUE) {
                        return (int) longVal;
                    } else {
                        return longVal;
                    }
                }
            } catch (NumberFormatException e) {
                throw error("Invalid number: " + numberStr);
            }
        }

        void expectLiteral(String literal) {
            if (json.regionMatches(pos, literal, 0, literal.length())) {
                pos += literal.length();
            } else {
                throw error("Expected literal '" + literal + "'");
            }
        }

        void skipWhitespace() {
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        RuntimeException error(String message) {
            return new RuntimeException("JSON parse error at position " + pos + ": " + message);
        }
    }

    /**
     * JsonArrayBuilder is a helper class to build JSON arrays manually.
     */
    public static class JsonArrayBuilder {
        private final StringBuilder sb;
        private boolean hasElements;
        private boolean isBuilt;

        public JsonArrayBuilder() {
            sb = new StringBuilder();
            sb.append("[");
            hasElements = false;
            isBuilt = false;
        }

        private void checkNotBuilt() {
            if (isBuilt) {
                throw new IllegalStateException("JsonArrayBuilder is already built, cannot add more elements");
            }
        }

        private void appendCommaIfNeeded() {
            if (hasElements) {
                sb.append(",");
            } else {
                hasElements = true;
            }
        }

        public JsonArrayBuilder addObject(String value) {
            checkNotBuilt();
            appendCommaIfNeeded();
            if (value == null) {
                sb.append("null");
            } else {
                sb.append("\"").append(escapeUnicode(value)).append("\"");
            }
            return this;
        }

        public JsonArrayBuilder addObject(Number value) {
            checkNotBuilt();
            appendCommaIfNeeded();
            if (value == null) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            return this;
        }

        public JsonArrayBuilder addObject(Boolean value) {
            checkNotBuilt();
            appendCommaIfNeeded();
            if (value == null) {
                sb.append("null");
            } else {
                sb.append(value);
            }
            return this;
        }

        public JsonArrayBuilder addObject(Map<String, Object> map) {
            checkNotBuilt();
            appendCommaIfNeeded();
            sb.append(buildFromMap(map));
            return this;
        }

        public JsonArrayBuilder addArray(List<Object> list) {
            checkNotBuilt();
            appendCommaIfNeeded();
            sb.append(buildFromList(list));
            return this;
        }

        public JsonArrayBuilder addObject(JsonBuilder obj) {
            checkNotBuilt();
            appendCommaIfNeeded();
            sb.append(obj.build());
            return this;
        }

        public JsonArrayBuilder addArray(JsonArrayBuilder array) {
            checkNotBuilt();
            appendCommaIfNeeded();
            sb.append(array.build());
            return this;
        }

        public JsonArrayBuilder addNull() {
            checkNotBuilt();
            appendCommaIfNeeded();
            sb.append("null");
            return this;
        }

        public String build() {
            if (!isBuilt) {
                sb.append("]");
                isBuilt = true;
            }
            return sb.toString();
        }
    }
}

