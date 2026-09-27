package cleanmanga.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader and string quoter, so the guard needs no JSON library from the app. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Objects become Maps, arrays Lists, numbers Doubles. Throws on broken input. */
    static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.space();
        if (j.i != j.s.length()) throw new IllegalArgumentException("trailing data at " + j.i);
        return v;
    }

    static String quote(String v) {
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value() {
        space();
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            m.put(key, value());
            space();
            if (s.charAt(i) == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        space();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(value());
            space();
            if (s.charAt(i) == ',') { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("bad value at " + i);
        return Double.valueOf(s.substring(start, i));
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
        i++;
    }
}
