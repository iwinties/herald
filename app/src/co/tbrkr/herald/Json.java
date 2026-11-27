package co.tbrkr.herald;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Reads and writes JSON with up to 16 levels of containers. Malformed values throw
 * IllegalArgumentException. Pure Java: no Android classes. PLAN-20261125-herald-v1.
 */
final class Json {
    private static final String HEX = "0123456789abcdef";

    private Json() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text) {
        Object value = parseValue(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("expected object");
        return (Map<String, Object>) value;
    }

    static Object parseValue(String text) {
        if (text == null) throw new IllegalArgumentException("no input");
        Reader r = new Reader(text);
        r.space();
        Object value = r.value(0);
        r.space();
        if (r.peek() >= 0) throw r.fail("trailing text");
        return value;
    }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value instanceof Map || value instanceof List) {
            if (depth == 16) throw new IllegalArgumentException("value too deep");
            boolean object = value instanceof Map;
            out.append(object ? '{' : '[');
            Iterable<?> items = object ? ((Map<?, ?>) value).entrySet() : (List<?>) value;
            boolean first = true;
            for (Object item : items) {
                if (!first) out.append(',');
                first = false;
                if (object) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
                    out.append(quote((String) entry.getKey())).append(':');
                    item = entry.getValue();
                }
                write(out, item, depth + 1);
            }
            out.append(object ? '}' : ']');
        } else if (value instanceof String) {
            out.append(quote((String) value));
        } else if (value == null || value instanceof Boolean
                || value instanceof Number && Double.isFinite(((Number) value).doubleValue())) {
            out.append(value);
        } else {
            throw new IllegalArgumentException("unsupported JSON value");
        }
    }

    /** A JSON string literal; everything outside printable ASCII is escaped. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        b.append("\\u").append(HEX.charAt((c >> 12) & 15)).append(HEX.charAt((c >> 8) & 15))
                                .append(HEX.charAt((c >> 4) & 15)).append(HEX.charAt(c & 15));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        int peek() {
            return i < s.length() ? s.charAt(i) : -1;
        }

        IllegalArgumentException fail(String why) {
            return new IllegalArgumentException(why + " at offset " + i);
        }

        void space() {
            while (true) {
                int c = peek();
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
                i++;
            }
        }

        void expect(char c) {
            if (peek() != c) throw fail("expected '" + c + "'");
            i++;
        }

        Map<String, Object> object(int depth) {
            if (depth > 16) throw fail("object too deep");
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            space();
            expect('{');
            space();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                space();
                if (peek() != '"') throw fail("expected a key");
                String key = string();
                space();
                expect(':');
                space();
                m.put(key, value(depth));
                space();
                int c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw fail("expected ',' or '}'");
            }
        }

        Object value(int depth) {
            int c = peek();
            switch (c) {
                case '"': return string();
                case 't': word("true"); return Boolean.TRUE;
                case 'f': word("false"); return Boolean.FALSE;
                case 'n': word("null"); return null;
                case '{': return object(depth + 1);
                case '[': return array(depth + 1);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw fail("unexpected character");
            }
        }

        List<Object> array(int depth) {
            if (depth > 16) throw fail("array too deep");
            expect('[');
            space();
            List<Object> items = new ArrayList<>();
            if (peek() == ']') {
                i++;
                return items;
            }
            while (true) {
                space();
                items.add(value(depth));
                space();
                if (peek() == ']') {
                    i++;
                    return items;
                }
                expect(',');
            }
        }

        void word(String w) {
            if (!s.startsWith(w, i)) throw fail("expected " + w);
            i += w.length();
        }

        Double number() {
            int start = i;
            if (peek() == '-') i++;
            if (peek() == '0') {
                i++;
            } else if (digit()) {
                while (digit()) i++;
            } else {
                throw fail("bad number");
            }
            if (peek() == '.') {
                i++;
                if (!digit()) throw fail("bad number");
                while (digit()) i++;
            }
            if (peek() == 'e' || peek() == 'E') {
                i++;
                if (peek() == '+' || peek() == '-') i++;
                if (!digit()) throw fail("bad number");
                while (digit()) i++;
            }
            double n = Double.parseDouble(s.substring(start, i));
            if (!Double.isFinite(n)) throw fail("number out of range");
            return n;
        }

        boolean digit() {
            int c = peek();
            return c >= '0' && c <= '9';
        }

        /** One pass into one StringBuilder: linear in the length (a voice note is about 3 MB). */
        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw fail("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw fail("control character in string");
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) throw fail("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u': b.append(unicode()); break;
                    default: throw fail("bad escape");
                }
            }
        }

        char unicode() {
            if (i + 4 > s.length()) throw fail("short \\u escape");
            int v = 0;
            for (int k = 0; k < 4; k++) {
                int d = HEX.indexOf(Character.toLowerCase(s.charAt(i++)));
                if (d < 0) throw fail("bad \\u escape");
                v = v * 16 + d;
            }
            return (char) v;
        }
    }
}
