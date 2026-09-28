package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.SManga;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Decides which manga are blocked and blacks them out.
 *
 * A manga is blocked when one of its tags matches block-tags.txt (baked into {@link Tags}).
 * Verdicts are remembered by manga url: blocked ones for good, clean ones until block-tags.txt changes.
 */
public final class Guard {
    public static final String BLOCKED_TITLE = "Blocked";
    public static final String BLOCKED_COVER = "https://raw.githubusercontent.com/foku1-create/clean-manga-repo/main/blocked.png";
    static final String BLOCKED_DESCRIPTION = "Hidden by your clean manga filter: this title is tagged hentai, ecchi or adult.";
    static final String UNCHECKED_TITLE = "Checking";
    static final String UNCHECKED_DESCRIPTION = "Hidden by your clean manga filter until its tags are checked. Open it, or reload the list in a moment.";
    static final long CHECK_TIMEOUT_MS = 25_000;
    /** How long a list waits for its tag checks before it goes to the app. */
    static final long LIST_BUDGET_MS = 12_000;
    /** Tag checks one list runs at the same time. */
    static final int LIST_PARALLEL = 6;
    /** Clean verdicts are only valid for the tag list and checking rules they were made with. */
    static final String CLEAN = "clean_manga_" + Integer.toHexString(Arrays.hashCode(Tags.LINES) * 31 + 4);
    /** Where the app is sent instead of an adult site or a blocked title. */
    static final String BLOCKED_PAGE = "https://github.com/foku1-create/clean-manga-repo";
    private static final Object FAILED = new Object();

    private static final List<String> WHOLE = new ArrayList<>();
    private static final List<String> PREFIX = new ArrayList<>();
    private static final List<String> ANYWHERE = new ArrayList<>();
    private static final Set<String> ALLOW = new HashSet<>();

    static {
        for (String line : Tags.LINES) {
            String word = normalize(line);
            if (word.isEmpty()) continue;
            if (word.startsWith("!")) {
                ALLOW.add(word.substring(1).trim());
            } else if (!isSpaced(word)) {
                ANYWHERE.add(word.endsWith("*") ? word.substring(0, word.length() - 1) : word);
            } else if (word.endsWith("*")) {
                PREFIX.add(word.substring(0, word.length() - 1));
            } else {
                WHOLE.add(word);
            }
        }
    }

    /** url -> true when blocked, false when checked and clean. */
    private static final Map<String, Boolean> VERDICTS = lru(20_000);
    /** real cover url -> the manga it belongs to, for covers that must wait for a check or stay black. */
    private static final Map<String, SManga> COVERS = lru(5_000);
    /** chapter urls of blocked manga. */
    private static final Map<String, Boolean> BLOCKED_CHAPTERS = lru(20_000);
    private static final ConcurrentHashMap<String, CountDownLatch> CHECKING = new ConcurrentHashMap<>();
    /** Runs tag checks for lists, away from the app's own threads. */
    static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "clean-manga-guard-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });
    private static volatile boolean attached;
    private static volatile Store store;

    private Guard() {
    }

    /** Loads what earlier runs of the app decided. Called on every way in; cheap after the first. */
    static void attach(GuardHost h) {
        if (attached) return;
        synchronized (Guard.class) {
            if (attached) return;
            attached = true;
            Store s;
            try {
                s = Store.open("cleanmanga_guard_" + h.guard$pkg());
            } catch (Throwable e) {
                s = null;
            }
            if (s == null) return;
            for (String url : s.read(CLEAN)) VERDICTS.put(url, Boolean.FALSE);
            for (String url : s.read(Store.MANGA)) VERDICTS.put(url, Boolean.TRUE);
            for (String url : s.read(Store.CHAPTERS)) BLOCKED_CHAPTERS.put(url, Boolean.TRUE);
            store = s;
        }
    }

    private static void remember(String key, Set<String> urls) {
        Store s = store;
        if (s != null && !urls.isEmpty()) s.add(key, urls);
    }

    // ---- tags ----

    public static boolean tagsBlocked(String genre) {
        if (genre == null) return false;
        String all = normalize(genre);
        for (String tag : all.split("[,;|\\n]")) {
            tag = tag.trim();
            if (tag.isEmpty() || ALLOW.contains(tag)) continue;
            for (String w : ANYWHERE) if (tag.contains(w)) return true;
            for (String w : WHOLE) if (wordAt(tag, w, false)) return true;
            for (String w : PREFIX) if (wordAt(tag, w, true)) return true;
        }
        return false;
    }

    private static boolean wordAt(String tag, String word, boolean prefix) {
        int i = tag.indexOf(word);
        while (i >= 0) {
            int end = i + word.length();
            boolean startOk = i == 0 || !Character.isLetterOrDigit(tag.charAt(i - 1));
            boolean endOk = prefix || end == tag.length() || !Character.isLetterOrDigit(tag.charAt(end));
            if (startOk && endOk) return true;
            i = tag.indexOf(word, i + 1);
        }
        return false;
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    /** Latin and Cyrillic words are matched as whole words; other scripts anywhere in a tag. */
    private static boolean isSpaced(String word) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (!Character.isLetter(c)) continue;
            Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
            if (b != Character.UnicodeBlock.BASIC_LATIN && b != Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                    && b != Character.UnicodeBlock.LATIN_EXTENDED_A && b != Character.UnicodeBlock.LATIN_EXTENDED_B
                    && b != Character.UnicodeBlock.CYRILLIC) {
                return false;
            }
        }
        return true;
    }

    // ---- verdicts ----

    static Boolean verdict(String url) {
        return url == null ? null : VERDICTS.get(url);
    }

    static boolean isBlockedChapter(String chapterUrl) {
        return chapterUrl != null && BLOCKED_CHAPTERS.containsKey(chapterUrl);
    }

    static void blockChapters(Collection<?> chapters) {
        if (chapters == null) return;
        Set<String> urls = new HashSet<>();
        for (Object c : chapters) {
            String url = Safe.chapterUrl(c);
            if (url != null) {
                BLOCKED_CHAPTERS.put(url, Boolean.TRUE);
                urls.add(url);
            }
        }
        remember(Store.CHAPTERS, urls);
    }

    /**
     * Judges a manga whose details (and so all tags) are known. Returns true when blocked, false
     * when clean, null when an adult-labelled site's title could not be checked with AniList
     * (it is blacked out this time, but nothing is remembered).
     */
    static Boolean judgeDetails(GuardHost host, String url, SManga details) {
        return judge(host, url, details, true);
    }

    /** saveClean: false when the caller saves clean verdicts itself, in one go. */
    private static Boolean judge(GuardHost host, String url, SManga details, boolean saveClean) {
        boolean blocked = details != null && (tagsBlocked(Safe.genre(details)) || tagsBlocked(Safe.title(details)));
        if (!blocked && host.guard$strict()) {
            Boolean ok = details == null ? Boolean.FALSE : Verify.clean(host, Safe.title(details));
            if (ok == null) {
                gateCover(snapshot(details));
                blackout(details);
                return null;
            }
            blocked = !ok;
        }
        if (url == null && details != null) url = Safe.url(details);
        if (url != null) {
            Boolean before = VERDICTS.put(url, blocked);
            Store s = store;
            if (blocked && !Boolean.TRUE.equals(before)) {
                remember(Store.MANGA, Collections.singleton(url));
                if (s != null) s.remove(CLEAN, url);
            }
            if (!blocked && Boolean.TRUE.equals(before) && s != null) s.remove(Store.MANGA, url);
            if (!blocked && saveClean && !Boolean.FALSE.equals(before)) remember(CLEAN, Collections.singleton(url));
        }
        if (blocked) {
            gateCover(details);
            blackout(details);
        }
        return blocked;
    }

    static void blackout(SManga m) {
        mask(m, BLOCKED_TITLE, BLOCKED_DESCRIPTION);
    }

    /** A listed manga whose tags could not be checked in time: looks blocked, but is not remembered as blocked. */
    static void veil(SManga m) {
        mask(m, UNCHECKED_TITLE, UNCHECKED_DESCRIPTION);
    }

    private static void mask(SManga m, String title, String description) {
        if (m == null) return;
        try {
            m.setTitle(title);
            m.setThumbnail_url(BLOCKED_COVER);
            m.setDescription(description);
            m.setGenre(title);
            m.setAuthor(null);
            m.setArtist(null);
        } catch (Throwable ignored) {
            // some fields may be read-only in a custom SManga; the title is what matters
        }
    }

    // ---- web links ("open in WebView", "share") ----

    static final String NOWHERE = "about:blank";

    public static String getMangaUrl(GuardHost h, SManga manga) {
        attach(h);
        if (h.guard$strict()) return BLOCKED_PAGE;
        if (Boolean.TRUE.equals(verdict(Safe.url(manga)))) return NOWHERE;
        return h.getMangaUrl$gorig(manga);
    }

    public static String getChapterUrl(GuardHost h, eu.kanade.tachiyomi.source.model.SChapter chapter) {
        attach(h);
        if (h.guard$strict()) return BLOCKED_PAGE;
        if (isBlockedChapter(Safe.chapterUrl(chapter))) return NOWHERE;
        return h.getChapterUrl$gorig(chapter);
    }

    /**
     * The site's address. The extension and the extension library get the real one, so the site
     * keeps working. For an adult-labelled site, anyone else (the app's "open in browser") gets
     * {@link #BLOCKED_PAGE}, so the site itself never opens.
     */
    public static String getBaseUrl(GuardHost h) {
        String real = h.getBaseUrl$gorig();
        if (!h.guard$strict() || calledFromInside()) return real;
        return BLOCKED_PAGE;
    }

    /** Frames that only pass a call along; the frame above them decides. */
    private static final String[] PASSING = {"java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "rx.", "okhttp3.", "okio.", "dalvik."};
    /** The extension library the app provides, and code shipped in extensions. */
    private static final String[] INSIDE = {"eu.kanade.tachiyomi.source.", "eu.kanade.tachiyomi.network.",
            "eu.kanade.tachiyomi.extension.", "eu.kanade.tachiyomi.multisrc.", "keiyoushi.", "cleanmanga."};
    private static final Map<String, Boolean> OWN_CLASSES = new ConcurrentHashMap<>();

    private static boolean calledFromInside() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        // [0] here, [1] Guard.getBaseUrl, [2] the extension's getBaseUrl, then whoever called it
        if (st.length <= 3) return true; // no stack to judge by: keep the site working
        for (int i = 3; i < st.length; i++) {
            String c = st[i].getClassName();
            if (startsWith(c, PASSING)) continue;
            if (startsWith(c, INSIDE)) return true;
            return isOwnClass(c);
        }
        return true;
    }

    private static boolean startsWith(String s, String[] prefixes) {
        for (String p : prefixes) if (s.startsWith(p)) return true;
        return false;
    }

    /** True for classes that come from this extension's own file (its obfuscated helpers). */
    private static boolean isOwnClass(String name) {
        Boolean known = OWN_CLASSES.get(name);
        if (known != null) return known;
        boolean own;
        try {
            ClassLoader mine = Guard.class.getClassLoader();
            own = Class.forName(name, false, mine).getClassLoader() == mine;
        } catch (Throwable e) {
            own = false;
        }
        OWN_CLASSES.put(name, own);
        return own;
    }

    // ---- lists ----

    public static MangasPage page(GuardHost host, MangasPage page) {
        if (page != null) list(host, page.getMangas());
        return page;
    }

    /**
     * Screens a list before the app sees it. Every manga whose tags are not known yet gets its
     * details checked, a few at a time, for at most {@link #LIST_BUDGET_MS}. Blocked ones are
     * blacked out and ones still unchecked are hidden, so neither the title nor the cover of a
     * blocked manga reaches the app, however the app loads and caches covers. Checks that run
     * past the budget carry on, so the next load of the list has their answer.
     */
    public static List<?> list(final GuardHost host, List<?> mangas) {
        if (mangas == null) return null;
        final List<SManga> todo = new ArrayList<>();
        for (Object o : mangas) {
            if (o instanceof SManga && listed((SManga) o) == null) todo.add((SManga) o);
        }
        if (todo.isEmpty()) return mangas;

        // The checks work on copies: the originals get masked when the budget runs out, while
        // checks may still be waiting to start.
        final List<SManga> copies = new ArrayList<>();
        for (SManga m : todo) copies.add(snapshot(m));
        final int n = todo.size();
        final AtomicReferenceArray<Object> outcome = new AtomicReferenceArray<>(n);
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger left = new AtomicInteger(n);
        final CountDownLatch finished = new CountDownLatch(n);
        final Set<String> clean = Collections.synchronizedSet(new HashSet<String>());
        if (host.guard$strict()) {
            // ask AniList about the whole list in a few requests while the details load
            List<String> titles = new ArrayList<>();
            for (SManga m : copies) titles.add(Safe.title(m));
            Verify.prefetch(host, titles);
        }
        Runnable worker = new Runnable() {
            @Override
            public void run() {
                for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) {
                    Object r = FAILED;
                    try {
                        Boolean b = check(host, copies.get(i), clean);
                        if (b != null) r = b;
                    } catch (Throwable ignored) {
                        // counts as failed
                    }
                    outcome.set(i, r);
                    finished.countDown();
                    // whoever finishes the last check saves the clean verdicts in one go
                    if (left.decrementAndGet() == 0) remember(CLEAN, clean);
                }
            }
        };
        for (int w = Math.min(LIST_PARALLEL, n); w > 0; w--) POOL.execute(worker);
        try {
            finished.await(LIST_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // A check that failed counts like one still running, on every site: Keiyoushi's "safe"
        // label is not always right (MangaKa is "safe" and has hentai).
        for (int i = 0; i < n; i++) {
            Object r = outcome.get(i);
            SManga m = todo.get(i);
            if (Boolean.FALSE.equals(r)) continue;
            gateCover(copies.get(i));
            if (Boolean.TRUE.equals(r)) blackout(m);
            else veil(m);
        }
        return mangas;
    }

    /** A detached copy of a manga, for checks that must not see (or cause) masking of the one the app holds. */
    static SManga snapshot(SManga m) {
        SManga c;
        try {
            c = SManga.Companion.create();
        } catch (Throwable e) {
            return m;
        }
        try { c.setUrl(m.getUrl()); } catch (Throwable ignored) { }
        try { c.setTitle(m.getTitle()); } catch (Throwable ignored) { }
        try { c.setThumbnail_url(m.getThumbnail_url()); } catch (Throwable ignored) { }
        try { c.setGenre(m.getGenre()); } catch (Throwable ignored) { }
        try { c.setDescription(m.getDescription()); } catch (Throwable ignored) { }
        try { c.setAuthor(m.getAuthor()); } catch (Throwable ignored) { }
        try { c.setArtist(m.getArtist()); } catch (Throwable ignored) { }
        try { c.setStatus(m.getStatus()); } catch (Throwable ignored) { }
        try { c.setInitialized(m.getInitialized()); } catch (Throwable ignored) { }
        return c;
    }

    /** Judges a listed manga from the tags the list itself carries. Returns the verdict, null when unknown. */
    private static Boolean listed(SManga m) {
        String url = Safe.url(m);
        if (url == null) return Boolean.FALSE; // nothing to check against; opening it is still guarded
        Boolean v = verdict(url);
        // the tags a list carries, or a blocked word in the title itself ("... Hentai ...")
        if (!Boolean.TRUE.equals(v) && (tagsBlocked(Safe.genre(m)) || tagsBlocked(Safe.title(m)))) {
            v = Boolean.TRUE;
            VERDICTS.put(url, Boolean.TRUE);
            remember(Store.MANGA, Collections.singleton(url));
        }
        if (Boolean.TRUE.equals(v)) {
            gateCover(m);
            blackout(m);
        }
        return v;
    }

    // ---- covers ----

    /** The app may still ask for the real cover of a masked manga (it saw it earlier): route that through the gate. */
    private static void gateCover(SManga m) {
        String cover = Safe.thumbnail(m);
        if (cover != null && !cover.isEmpty() && !cover.equals(BLOCKED_COVER)) COVERS.put(GuardNet.key(cover), m);
    }

    /** The manga a real cover belongs to, when that cover must wait for a check or stay black. */
    static SManga coverOwner(String coverUrl) {
        return COVERS.get(coverUrl);
    }

    /**
     * Checks a listed manga by loading its details. Waits at most {@link #CHECK_TIMEOUT_MS}.
     * Returns null when the check failed.
     */
    static Boolean check(GuardHost host, SManga m) {
        return check(host, m, null);
    }

    /** clean: when given, collects the urls found clean for the caller to save, instead of saving each. */
    private static Boolean check(GuardHost host, SManga m, Set<String> clean) {
        String url = Safe.url(m);
        if (url == null) return null;
        Boolean known = VERDICTS.get(url);
        if (known != null) return known;

        CountDownLatch mine = new CountDownLatch(1);
        CountDownLatch running = CHECKING.putIfAbsent(url, mine);
        if (running != null) {
            try {
                running.await(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return VERDICTS.get(url);
        }
        try {
            if (host.guard$strict()) {
                // adult-labelled site: AniList first, the site's own page only for titles it confirms
                Boolean ok = Verify.clean(host, Safe.title(m));
                if (ok == null) return null;
                if (!ok) {
                    VERDICTS.put(url, Boolean.TRUE);
                    remember(Store.MANGA, Collections.singleton(url));
                    return Boolean.TRUE;
                }
            }
            SManga details = host instanceof GuardHost16
                    ? Guard16.details((GuardHost16) host, m)
                    : Guard14.details((GuardHost14) host, m);
            Boolean blocked = judge(host, url, details, clean == null);
            if (Boolean.FALSE.equals(blocked) && clean != null) clean.add(url);
            return blocked;
        } catch (Throwable e) {
            return null;
        } finally {
            CHECKING.remove(url);
            mine.countDown();
        }
    }

    private static <K, V> Map<K, V> lru(final int max) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        });
    }
}
