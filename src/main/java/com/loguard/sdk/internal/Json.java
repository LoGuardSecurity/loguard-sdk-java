package com.loguard.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * The core SDK only ever needs to (de)serialize a small, known set of
 * shapes (event batches, ingest responses, alert rules) — pulling in
 * Jackson or Gson for that would add a nontrivial dependency (plus its
 * own transitive dependency tree and CVE surface to track) for a job
 * a couple hundred lines can do safely. If your application already
 * depends on Jackson, this codec does not conflict with or shade over
 * it — it's purely internal to this package.
 *
 * Hardened against malicious/malformed server responses:
 *  - {@link #MAX_DEPTH} bounds recursion (no stack-overflow DoS from a
 *    deeply nested `[[[[[...`).
 *  - {@link #MAX_INPUT_LENGTH} bounds total input size (paired with
 *    Transport's response-size cap, this is defense in depth).
 *  - Parsing never throws anything other than {@link JsonException},
 *    and callers in this SDK always catch it and fail soft (treat as
 *    an empty/absent value) rather than letting a malformed response
 *    crash application code.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_INPUT_LENGTH = 8 * 1024 * 1024; // 8 MiB

    private Json() {
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("max serialization depth exceeded");
        }
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Number) {
            writeNumber(sb, (Number) value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item, depth + 1);
            }
            sb.append(']');
        } else {
            // Fallback: never let an unexpected type crash serialization
            // of an otherwise-valid event payload.
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else {
            sb.append(n);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * Parses JSON text into java.util.Map / java.util.List / String /
     * Double / Boolean / null. Returns null (never throws) for
     * null/empty input; throws {@link JsonException} for genuinely
     * malformed input — callers in this SDK always catch that and
     * degrade gracefully.
     */
    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            throw new JsonException("input exceeds max length");
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        if (p.atEnd()) {
            return null;
        }
        Object result = p.parseValue(0);
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new JsonException("trailing content after JSON value");
        }
        return result;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("max parse depth exceeded");
            }
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject(depth);
                case '[':
                    return parseArray(depth);
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        Map<String, Object> parseObject(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!atEnd() && peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonException("expected string key");
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new JsonException("expected ':'");
                }
                pos++;
                Object value = parseValue(depth + 1);
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    break;
                } else {
                    throw new JsonException("expected ',' or '}'");
                }
            }
            return map;
        }

        List<Object> parseArray(int depth) {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!atEnd() && peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = parseValue(depth + 1);
                list.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    break;
                } else {
                    throw new JsonException("expected ',' or ']'");
                }
            }
            return list;
        }

        String parseString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new JsonException("unterminated escape");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
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
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new JsonException("invalid unicode escape");
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new JsonException("invalid unicode escape");
                            }
                            break;
                        default:
                            throw new JsonException("invalid escape sequence");
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Double parseNumber() {
            int start = pos;
            if (!atEnd() && (peek() == '-' || peek() == '+')) {
                pos++;
            }
            while (!atEnd() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e' || peek() == 'E' || peek() == '-' || peek() == '+')) {
                pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw new JsonException("expected a value");
            }
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw new JsonException("invalid number literal: " + num);
            }
        }

        void expectLiteral(String literal) {
            if (pos + literal.length() > s.length() || !s.regionMatches(pos, literal, 0, literal.length())) {
                throw new JsonException("expected literal '" + literal + "'");
            }
            pos += literal.length();
        }
    }
}
