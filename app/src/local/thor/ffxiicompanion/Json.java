package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, dependency-free JSON reader, so the snapshot model runs unchanged on the JVM test harness
 * (android.jar's org.json is stub-only off-device). Objects become LinkedHashMap, arrays ArrayList,
 * integers Long, other numbers Double. Throws IllegalArgumentException on any syntax error.
 */
final class Json {
    static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing data");
        return v;
    }

    // ---- typed accessors (null when absent or of another type) ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof List ? (List<Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    static Long lng(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) {   // 0.11.0: only exact whole numbers within long range (no 1e999 -> Long.MAX_VALUE)
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 9.0e15) return (long) d;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String str(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof String ? (String) v : null;
    }

    @SuppressWarnings("unchecked")
    static Boolean bool(Object o, String key) {
        Object v = o instanceof Map ? ((Map<String, Object>) o).get(key) : null;
        return v instanceof Boolean ? (Boolean) v : null;
    }

    // ---- parser ----

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("too deep");
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected '" + c + "'");
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            if (!peek('"')) throw err("expected key");
            String k = string();
            ws();
            expect(':');
            ws();
            if (m.containsKey(k)) throw err("duplicate key");   // 0.11.0: shared-storage input is read strictly
            m.put(k, value(depth + 1));
            ws();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) { i++; return a; }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            if (peek(',')) { i++; continue; }
            expect(']');
            return a;
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw err("control character in string");
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("bad escape");
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
                case 'u':
                    if (i + 4 > s.length()) throw err("bad \\u escape");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                    catch (NumberFormatException x) { throw err("bad \\u escape"); }
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek('-')) i++;
        if (peek('0')) i++;
        else if (i < s.length() && s.charAt(i) >= '1' && s.charAt(i) <= '9') digits();
        else throw err("bad number");
        boolean real = false;
        if (peek('.')) { real = true; i++; if (!digits()) throw err("bad fraction"); }
        if (peek('e') || peek('E')) {
            real = true; i++;
            if (peek('+') || peek('-')) i++;
            if (!digits()) throw err("bad exponent");
        }
        String t = s.substring(start, i);
        if (!real) {
            try { return Long.parseLong(t); } catch (NumberFormatException x) { /* too large: fall through */ }
        }
        double d = Double.parseDouble(t);
        if (Double.isInfinite(d) || Double.isNaN(d)) throw err("number out of range");
        return d;
    }

    private boolean digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        return i > start;
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("bad literal");
        i += w.length();
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private void expect(char c) {
        if (!peek(c)) throw err("expected '" + c + "'");
        i++;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            i++;
        }
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException(m + " at " + i);
    }
}
