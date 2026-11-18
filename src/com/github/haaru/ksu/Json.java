package com.github.haaru.ksu;

import java.util.*;
import java.math.BigDecimal;

public final class Json {
    private Json() {}
    /** Strict, bounded JSON parser for module preferences and package arrays. */
    public static Object decode(String source) {
        if (source == null || source.length() > 65536) throw new IllegalArgumentException("JSON is too large");
        Parser parser = new Parser(source); Object value = parser.value(0); parser.space();
        if (parser.position != source.length()) throw new IllegalArgumentException("Trailing JSON content");
        return value;
    }
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Object item : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) item;
                if (out.length() > 1) out.append(',');
                out.append(encode(String.valueOf(e.getKey()))).append(':').append(encode(e.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) {
                if (out.length() > 1) out.append(',');
                out.append(encode(item));
            }
            return out.append(']').toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static final class Parser {
        final String source; int position;
        Parser(String source) { this.source = source; }
        void space() {
            while (position < source.length() && " \n\r\t".indexOf(source.charAt(position)) >= 0) position++;
        }
        IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid JSON at offset " + position); }
        char take() { if (position >= source.length()) throw invalid(); return source.charAt(position++); }
        boolean consume(char expected) {
            space(); if (position < source.length() && source.charAt(position) == expected) { position++; return true; } return false;
        }
        Object value(int depth) {
            if (depth > 16) throw new IllegalArgumentException("JSON nesting is too deep");
            space(); if (position >= source.length()) throw invalid();
            char current = source.charAt(position);
            if (current == '"') return string();
            if (current == '{') {
                position++; Map<String, Object> out = new LinkedHashMap<String, Object>();
                if (consume('}')) return out;
                do {
                    space(); if (position >= source.length() || source.charAt(position) != '"') throw invalid();
                    String key = string(); if (out.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key");
                    if (!consume(':')) throw invalid(); out.put(key, value(depth + 1));
                    if (consume('}')) return out;
                } while (consume(',')); throw invalid();
            }
            if (current == '[') {
                position++; List<Object> out = new ArrayList<Object>(); if (consume(']')) return out;
                do { out.add(value(depth + 1)); if (consume(']')) return out; } while (consume(',')); throw invalid();
            }
            for (String literal : Arrays.asList("true", "false", "null")) {
                if (source.startsWith(literal, position)) {
                    position += literal.length(); return "null".equals(literal) ? null : Boolean.valueOf(literal);
                }
            }
            int start = position;
            if (current == '-') position++;
            if (position >= source.length()) throw invalid();
            if (source.charAt(position) == '0') position++;
            else {
                if (source.charAt(position) < '1' || source.charAt(position) > '9') throw invalid();
                while (position < source.length() && digit(source.charAt(position))) position++;
            }
            if (position < source.length() && source.charAt(position) == '.') {
                position++; int digits = position;
                while (position < source.length() && digit(source.charAt(position))) position++;
                if (digits == position) throw invalid();
            }
            if (position < source.length() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                position++;
                if (position < source.length() && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
                int digits = position;
                while (position < source.length() && digit(source.charAt(position))) position++;
                if (digits == position) throw invalid();
            }
            try { return new BigDecimal(source.substring(start, position)); }
            catch (NumberFormatException e) { throw invalid(); }
        }
        boolean digit(char value) { return value >= '0' && value <= '9'; }
        String string() {
            if (take() != '"') throw invalid(); StringBuilder out = new StringBuilder();
            while (position < source.length()) {
                char c = take();
                if (c == '"') {
                    String value = out.toString();
                    for (int i = 0; i < value.length(); i++) {
                        char unit = value.charAt(i);
                        if (Character.isHighSurrogate(unit)) {
                            if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
                        } else if (Character.isLowSurrogate(unit)) throw invalid();
                    } return value;
                }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    c = take();
                    switch (c) {
                        case '"': case '\\': case '/': out.append(c); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            int unit = 0;
                            for (int i = 0; i < 4; i++) {
                                char hex = take(); int digit = hex < 128 ? Character.digit(hex, 16) : -1;
                                if (digit < 0) throw invalid(); unit = unit * 16 + digit;
                            }
                            out.append((char) unit); break;
                        default: throw invalid();
                    }
                } else out.append(c);
            } throw invalid();
        }
    }
}

