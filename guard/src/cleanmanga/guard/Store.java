package cleanmanga.guard;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Remembers blocked manga and chapters across app restarts, in the same settings storage the
 * extension itself uses. Everything here is reached by reflection and any failure just leaves
 * the guard working from memory.
 */
final class Store {
    static final String MANGA = "blocked_manga";
    static final String CHAPTERS = "blocked_chapters";
    private static final int MAX = 5_000;

    private final Object prefs;
    private final Method getStringSet;
    private final Method edit;
    private final Method putStringSet;
    private final Method apply;

    private Store(Object prefs) throws Exception {
        this.prefs = prefs;
        ClassLoader cl = Store.class.getClassLoader();
        Class<?> p = Class.forName("android.content.SharedPreferences", false, cl);
        Class<?> e = Class.forName("android.content.SharedPreferences$Editor", false, cl);
        getStringSet = p.getMethod("getStringSet", String.class, Set.class);
        edit = p.getMethod("edit");
        putStringSet = e.getMethod("putStringSet", String.class, Set.class);
        apply = e.getMethod("apply");
    }

    static Store open(String name) {
        try {
            ClassLoader cl = Store.class.getClassLoader();
            Object scope = Class.forName("uy.kohesive.injekt.InjektKt", true, cl).getMethod("getInjekt").invoke(null);
            Class<?> appClass = Class.forName("android.app.Application", false, cl);
            Method getInstance = Class.forName("uy.kohesive.injekt.api.InjektFactory", false, cl)
                    .getMethod("getInstance", Type.class);
            Object app = getInstance.invoke(scope, appClass);
            Object prefs = appClass.getMethod("getSharedPreferences", String.class, int.class).invoke(app, name, 0);
            return prefs == null ? null : new Store(prefs);
        } catch (Throwable e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    synchronized Set<String> read(String key) {
        try {
            Set<String> s = (Set<String>) getStringSet.invoke(prefs, key, Collections.<String>emptySet());
            return s == null ? new HashSet<String>() : new HashSet<String>(s);
        } catch (Throwable e) {
            return new HashSet<String>();
        }
    }

    synchronized void add(String key, Set<String> values) {
        Set<String> s = read(key);
        if (!s.addAll(values)) return;
        while (s.size() > MAX) s.remove(s.iterator().next());
        write(key, s);
    }

    synchronized void remove(String key, String value) {
        Set<String> s = read(key);
        if (s.remove(value)) write(key, s);
    }

    private void write(String key, Set<String> s) {
        try {
            Object editor = edit.invoke(prefs);
            putStringSet.invoke(editor, key, s);
            apply.invoke(editor);
        } catch (Throwable ignored) {
            // memory only
        }
    }
}
