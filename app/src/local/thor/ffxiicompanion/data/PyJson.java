package local.thor.ffxiicompanion.data;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes exactly what Python's json.dumps(doc, separators=(',', ':')) writes (ensure_ascii=True, the default):
 * insertion order (LinkedHashMap), non-ASCII as lowercase \\uXXXX (surrogate pairs as two escapes), Python float repr
 * (shortest round-trip, '.0' for whole numbers, scientific outside 1e-4..1e16), None -> null.
 * The atlas's index.json and regions.json are written this way (unlike reference/*.json, which use ensure_ascii=False).
 */
final class PyJson {
    private PyJson() {}

    static String dumps(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String) str(sb, (String) o);
        else if (o instanceof Boolean) sb.append(((Boolean) o) ? "true" : "false");
        else if (o instanceof Integer || o instanceof Long) sb.append(o.toString());
        else if (o instanceof Double) sb.append(floatRepr((Double) o));
        else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("unsupported JSON value " + o.getClass());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Python repr(float). */
    static String floatRepr(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("non-finite float");
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        BigDecimal exact = new BigDecimal(d), shortest = null;
        for (int p = 1; p <= 17; p++) {
            BigDecimal c = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            if (c.doubleValue() == d) { shortest = c; break; }
        }
        if (shortest == null) throw new IllegalStateException("no round-trip repr");
        shortest = shortest.stripTrailingZeros();
        String digits = shortest.unscaledValue().abs().toString();
        int exp10 = digits.length() - 1 - shortest.scale();          // position of the first digit
        String sign = d < 0 ? "-" : "";
        if (exp10 < -4 || exp10 >= 16) {
            String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mant + "e" + (exp10 < 0 ? "-" : "+") + String.format(Locale.ROOT, "%02d", Math.abs(exp10));
        }
        String plain = shortest.abs().toPlainString();
        if (!plain.contains(".")) plain += ".0";
        return sign + plain;
    }

    /** Python round(x, 4) for a float: correctly rounded half-even on the exact binary value; keeps -0.0. */
    static double round4(double x) {
        double r = new BigDecimal(x).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
        return (r == 0 && Math.copySign(1.0, x) < 0) ? -0.0 : r;
    }
}
