package org.openhab.binding.rainbird.internal.net;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Minimal JSON parser/serializer used for communicating with the Rain Bird controller.
 */
@NonNullByDefault
final class RainbirdJson {

    private RainbirdJson() {
        // Utility class
    }

    public static String stringify(Map<String, Object> value) throws IOException {
        StringBuilder builder = new StringBuilder();
        new Serializer(builder).writeObject(value);
        return builder.toString();
    }

    public static Map<String, Object> parseObject(String json) throws IOException {
        Object value = new Parser(json).parseValue();
        if (!(value instanceof Map)) {
            throw new IOException("JSON document is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private static final class Serializer {

        private final StringBuilder builder;

        Serializer(StringBuilder builder) {
            this.builder = builder;
        }

        void writeObject(Map<String, Object> value) throws IOException {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(entry.getKey());
                builder.append(':');
                writeValue(entry.getValue());
            }
            builder.append('}');
        }

        void writeArray(List<?> values) throws IOException {
            builder.append('[');
            boolean first = true;
            for (Object element : values) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(element);
            }
            builder.append(']');
        }

        void writeString(String value) {
            builder.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\\':
                    case '"':
                        builder.append('\\').append(c);
                        break;
                    case '\b':
                        builder.append("\\b");
                        break;
                    case '\f':
                        builder.append("\\f");
                        break;
                    case '\n':
                        builder.append("\\n");
                        break;
                    case '\r':
                        builder.append("\\r");
                        break;
                    case '\t':
                        builder.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            builder.append(String.format("\\u%04x", (int) c));
                        } else {
                            builder.append(c);
                        }
                        break;
                }
            }
            builder.append('"');
        }

        void writeValue(Object value) throws IOException {
            if (value == null) {
                builder.append("null");
            } else if (value instanceof String) {
                writeString((String) value);
            } else if (value instanceof Number || value instanceof Boolean) {
                builder.append(Objects.toString(value));
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) value;
                writeObject(map);
            } else if (value instanceof List) {
                writeArray((List<?>) value);
            } else {
                throw new IOException("Unsupported JSON value type: " + value.getClass());
            }
        }
    }

    private static final class Parser {

        private final String json;
        private int index = 0;

        Parser(String json) {
            this.json = json;
        }

        Object parseValue() throws IOException {
            skipWhitespace();
            if (index >= json.length()) {
                throw new IOException("Unexpected end of JSON input");
            }
            char c = json.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                case 'f':
                    return parseBoolean();
                case 'n':
                    parseNull();
                    return null;
                default:
                    if (c == '-' || Character.isDigit(c)) {
                        return parseNumber();
                    }
                    throw new IOException("Unexpected character '" + c + "' at position " + index);
            }
        }

        private Map<String, Object> parseObject() throws IOException {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    break;
                }
                expect(',');
            }
            return result;
        }

        private List<Object> parseArray() throws IOException {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    break;
                }
                expect(',');
            }
            return result;
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    if (index >= json.length()) {
                        throw new IOException("Unexpected end of JSON input");
                    }
                    char escape = json.charAt(index++);
                    switch (escape) {
                        case '"':
                        case '\\':
                        case '/':
                            builder.append(escape);
                            break;
                        case 'b':
                            builder.append('\b');
                            break;
                        case 'f':
                            builder.append('\f');
                            break;
                        case 'n':
                            builder.append('\n');
                            break;
                        case 'r':
                            builder.append('\r');
                            break;
                        case 't':
                            builder.append('\t');
                            break;
                        case 'u':
                            if (index + 4 > json.length()) {
                                throw new IOException("Invalid unicode escape in JSON string");
                            }
                            String hex = json.substring(index, index + 4);
                            index += 4;
                            try {
                                builder.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new IOException("Invalid unicode escape in JSON string", e);
                            }
                            break;
                        default:
                            throw new IOException("Invalid escape sequence \\" + escape + " in JSON string");
                    }
                } else {
                    builder.append(c);
                }
            }
            throw new IOException("Unterminated JSON string");
        }

        private Boolean parseBoolean() throws IOException {
            if (json.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (json.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new IOException("Invalid boolean value in JSON");
        }

        private void parseNull() throws IOException {
            if (!json.startsWith("null", index)) {
                throw new IOException("Invalid null value in JSON");
            }
            index += 4;
        }

        private Number parseNumber() throws IOException {
            int start = index;
            if (json.charAt(index) == '-') {
                index++;
            }
            while (index < json.length() && Character.isDigit(json.charAt(index))) {
                index++;
            }
            boolean isFloat = false;
            if (index < json.length() && json.charAt(index) == '.') {
                isFloat = true;
                index++;
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            if (index < json.length()) {
                char exp = json.charAt(index);
                if (exp == 'e' || exp == 'E') {
                    isFloat = true;
                    index++;
                    if (index < json.length() && (json.charAt(index) == '+' || json.charAt(index) == '-')) {
                        index++;
                    }
                    while (index < json.length() && Character.isDigit(json.charAt(index))) {
                        index++;
                    }
                }
            }
            String number = json.substring(start, index);
            try {
                if (isFloat) {
                    return Double.valueOf(number);
                }
                long value = Long.parseLong(number);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return Integer.valueOf((int) value);
                }
                return Long.valueOf(value);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid number in JSON", e);
            }
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) throws IOException {
            if (index >= json.length() || json.charAt(index) != expected) {
                throw new IOException("Expected '" + expected + "' in JSON");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }
    }
}
