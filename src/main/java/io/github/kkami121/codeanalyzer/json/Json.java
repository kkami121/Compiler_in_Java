package io.github.kkami121.codeanalyzer.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 의존성 없는 최소 JSON 도구.
 * 쓰기: Map, Iterable, String, Number, Boolean, Enum, null.
 * 읽기: 객체는 {@link LinkedHashMap}, 배열은 {@link ArrayList}, 수는 {@link Long} 또는 {@link Double}.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, -1, 0);
        return out.toString();
    }

    /** 들여쓰기 2칸으로 보기 좋게 쓴다. */
    public static String writePretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 2, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int indent, int level) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) d).append(".0"); // 실수임이 드러나도록 1.0 형태로
            } else {
                out.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
            }
        } else if (value instanceof Number) {
            out.append(value);
        } else if (value instanceof Enum) {
            writeString(out, ((Enum<?>) value).name());
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, indent, level + 1);
                writeString(out, String.valueOf(entry.getKey()));
                out.append(indent >= 0 ? ": " : ":");
                write(out, entry.getValue(), indent, level + 1);
            }
            if (!map.isEmpty()) {
                newline(out, indent, level);
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                newline(out, indent, level + 1);
                write(out, item, indent, level + 1);
            }
            if (!first) {
                newline(out, indent, level);
            }
            out.append(']');
        } else {
            writeString(out, value.toString());
        }
    }

    private static void newline(StringBuilder out, int indent, int level) {
        if (indent < 0) {
            return;
        }
        out.append('\n');
        for (int i = 0; i < indent * level; i++) {
            out.append(' ');
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /** @throws JsonException 형식이 틀렸을 때 */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("Unexpected trailing characters");
        }
        return value;
    }

    /** JSON 형식 오류. */
    public static final class JsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Nesting too deep");
            }
            if (pos >= text.length()) {
                throw error("Unexpected end of input");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("Unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected string key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value(depth + 1));
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else if (peek() == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                if (peek() == ',') {
                    pos++;
                } else if (peek() == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        private String string() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    break;
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw error("Bad unicode escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("Bad unicode escape");
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("Bad escape '\\" + e + "'");
                }
            }
            throw error("Unterminated string");
        }

        private Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isDouble = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, pos);
            try {
                return isDouble ? (Object) Double.parseDouble(number) : (Object) Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw error("Bad number '" + number + "'");
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("Unexpected token");
            }
            pos += word.length();
            return value;
        }

        private void expect(char c) {
            if (peek() != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        JsonException error(String message) {
            return new JsonException(message + " at position " + pos);
        }
    }
}
