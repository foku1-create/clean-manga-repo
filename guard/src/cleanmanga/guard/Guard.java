package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.SManga;

import java.text.Normalizer;
import java.util.ArrayList;
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
import java.util.concurrent.TimeUnit;

/**
 * Decides which manga are blocked and blacks them out.
 *
 * A manga is blocked when one of its tags matches block-tags.txt (baked into {@link Tags}).
 * Verdicts are remembered by manga url for as long as the app runs.
 */
public final class Guard {
    public static final String BLOCKED_TITLE = "Blocked";
    public static final String BLOCKED_COVER = "https://raw.githubusercontent.com/foku1-create/clean-manga-repo/main/blocked.png";
    static final String BLOCKED_DESCRIPTION = "Hidden by your clean manga filter: this title is tagged hentai, ecchi or adult.";
    static final long CHECK_TIMEOUT_MS = 25_000;

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
    /** cover url -> the listed manga it belongs to, waiting for a check. */
    private static final Map<String, SManga> COVERS = lru(5_000);
    /** chapter urls of blocked manga. */
    private static final Map<String, Boolean> BLOCKED_CHAPTERS = lru(20_000);
    private static final ConcurrentHashMap<String, CountDownLatch> CHECKING = new ConcurrentHashMap<>();
    private static volatile boolean attached;
    private static volatile Store store;

    private Guard() {
    }

    /** Loads what earlier runs of the app blocked. Called on every way in; cheap after the first. */
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

    /** Judges a manga whose details (and so all tags) are known. Returns true when blocked. */
    static boolean judgeDetails(String url, SManga details) {
        boolean blocked = details != null && tagsBlocked(Safe.genre(details));
        if (url == null && details != null) url = Safe.url(details);
        if (url != null) {
            Boolean before = VERDICTS.put(url, blocked);
            if (blocked && !Boolean.TRUE.equals(before)) remember(Store.MANGA, Collections.singleton(url));
            Store s = store;
            if (!blocked && Boolean.TRUE.equals(before) && s != null) s.remove(Store.MANGA, url);
        }
        if (blocked) blackout(details);
        return blocked;
    }

    static void blackout(SManga m) {
        if (m == null) return;
        try {
            m.setTitle(BLOCKED_TITLE);
            m.setThumbnail_url(BLOCKED_COVER);
            m.setDescription(BLOCKED_DESCRIPTION);
            m.setGenre(BLOCKED_TITLE);
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
        if (Boolean.TRUE.equals(verdict(Safe.url(manga)))) return NOWHERE;
        return h.getMangaUrl$gorig(manga);
    }

    public static String getChapterUrl(GuardHost h, eu.kanade.tachiyomi.source.model.SChapter chapter) {
        attach(h);
        if (isBlockedChapter(Safe.chapterUrl(chapter))) return NOWHERE;
        return h.getChapterUrl$gorig(chapter);
    }

    // ---- lists ----

    public static MangasPage page(MangasPage page) {
        if (page != null) list(page.getMangas());
        return page;
    }

    public static List<?> list(List<?> mangas) {
        if (mangas == null) return null;
        for (Object o : mangas) {
            if (o instanceof SManga) listed((SManga) o);
        }
        return mangas;
    }

    private static void listed(SManga m) {
        String url = Safe.url(m);
        Boolean v = verdict(url);
        if (v == null && tagsBlocked(Safe.genre(m))) {
            v = Boolean.TRUE;
            if (url != null) {
                VERDICTS.put(url, Boolean.TRUE);
                remember(Store.MANGA, Collections.singleton(url));
            }
        }
        if (Boolean.TRUE.equals(v)) {
            blackout(m);
        } else if (v == null && url != null) {
            String cover = Safe.thumbnail(m);
            if (cover != null && !cover.isEmpty()) COVERS.put(GuardNet.key(cover), m);
        }
    }

    // ---- covers ----

    /** The listed manga a cover belongs to, if its tags are not checked yet. */
    static SManga coverOwner(String coverUrl) {
        return COVERS.get(coverUrl);
    }

    /**
     * Checks a listed manga by loading its details. Waits at most {@link #CHECK_TIMEOUT_MS}.
     * Returns null when the check failed.
     */
    static Boolean check(GuardHost host, SManga m) {
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
            SManga details = host instanceof GuardHost16
                    ? Guard16.details((GuardHost16) host, m)
                    : Guard14.details((GuardHost14) host, m);
            return judgeDetails(url, details);
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
