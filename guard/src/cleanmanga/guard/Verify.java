package cleanmanga.guard;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Second and third opinions for sites Keiyoushi labels adult. There most titles are adult and are
 * often not tagged as such, so the site's tags are not enough. A title on such a site only counts
 * as clean when both manga databases know a manga with exactly that title and call it clean:
 * <ul>
 * <li>AniList: not marked adult, not hentai or ecchi, no adult tag, no tag from block-tags.txt;</li>
 * <li>MangaUpdates: no genre from block-tags.txt (Adult, Mature, Smut, Hentai, Ecchi, Yaoi, ...).
 * It catches the uncensored 19+ editions of webtoons that AniList rates as ordinary romance.</li>
 * </ul>
 * A title either database does not know stays blocked. AniList is asked first, in batches;
 * MangaUpdates only for the titles AniList passes.
 */
final class Verify {
    static final String ANILIST = "https://graphql.anilist.co";
    static final String MANGAUPDATES = "https://api.mangaupdates.com/v1/series/search";
    /** Titles asked in one AniList request. */
    static final int BATCH = 8;
    /** How long a list's background questions may wait for their turn. */
    static final long PREFETCH_WAIT_MS = 10 * 60_000;

    /** AniList allows 30 requests a minute; a little is left for the other device on the same line. */
    private static final Pace ANILIST_PACE = new Pace(25);
    private static final Pace MANGAUPDATES_PACE = new Pace(60);

    /** normalised title -> true when that database confirms it clean, false when adult or unknown. */
    private static final Map<String, Boolean> ANILIST_ANSWERS = answers();
    private static final Map<String, Boolean> MANGAUPDATES_ANSWERS = answers();
    private static final ConcurrentHashMap<String, CountDownLatch> ANILIST_ASKING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CountDownLatch> MANGAUPDATES_ASKING = new ConcurrentHashMap<>();

    private Verify() {
    }

    /** True when both databases confirm the title clean, false when either calls it adult or does not know it, null when one could not be asked. */
    static Boolean clean(GuardHost h, String title) {
        String key = key(title);
        if (key.isEmpty()) return Boolean.FALSE;
        Boolean a = anilist(h, title, key);
        if (!Boolean.TRUE.equals(a)) return a;
        return mangaUpdates(h, title, key);
    }

    /** Starts asking AniList about a whole list at once, in few requests. Later {@link #clean} calls wait for it. */
    static void prefetch(final GuardHost h, List<String> titles) {
        final List<String> mine = claim(titles);
        if (mine.isEmpty()) return;
        Guard.POOL.execute(new Runnable() {
            @Override
            public void run() {
                askAnilist(h, mine, PREFETCH_WAIT_MS);
            }
        });
    }

    // ---- AniList ----

    private static Boolean anilist(GuardHost h, String title, String key) {
        Boolean known = ANILIST_ANSWERS.get(key);
        if (known != null) return known;
        CountDownLatch running = ANILIST_ASKING.get(key);
        if (running != null) {
            await(running);
            return ANILIST_ANSWERS.get(key);
        }
        List<String> one = claim(Collections.singletonList(title));
        if (!one.isEmpty()) askAnilist(h, one, Guard.CHECK_TIMEOUT_MS);
        return ANILIST_ANSWERS.get(key);
    }

    /** Marks titles as being asked about; returns the ones this caller must ask. */
    private static List<String> claim(List<String> titles) {
        List<String> mine = new ArrayList<>();
        for (String t : titles) {
            String key = key(t);
            if (key.isEmpty() || ANILIST_ANSWERS.containsKey(key)) continue;
            if (ANILIST_ASKING.putIfAbsent(key, new CountDownLatch(1)) == null) mine.add(t);
        }
        return mine;
    }

    private static void askAnilist(GuardHost h, List<String> titles, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        for (int from = 0; from < titles.size(); from += BATCH) {
            List<String> chunk = titles.subList(from, Math.min(from + BATCH, titles.size()));
            try {
                if (ANILIST_PACE.slot(deadline - System.currentTimeMillis())) requestAnilist(h, chunk);
            } catch (Throwable ignored) {
                // no answer: those titles stay unchecked and are asked again next time
            } finally {
                for (String t : chunk) {
                    CountDownLatch l = ANILIST_ASKING.remove(key(t));
                    if (l != null) l.countDown();
                }
            }
        }
    }

    private static void requestAnilist(GuardHost h, List<String> titles) throws IOException {
        StringBuilder q = new StringBuilder("query(");
        for (int i = 0; i < titles.size(); i++) q.append(i > 0 ? ", " : "").append("$s").append(i).append(": String");
        q.append(") {");
        for (int i = 0; i < titles.size(); i++) {
            q.append(" t").append(i).append(": Page(perPage: 5) { media(search: $s").append(i)
                    .append(", type: MANGA) { isAdult genres synonyms title { romaji english native } tags { name isAdult } } }");
        }
        q.append(" }");
        StringBuilder body = new StringBuilder("{\"query\":").append(Json.quote(q.toString())).append(",\"variables\":{");
        for (int i = 0; i < titles.size(); i++) {
            body.append(i > 0 ? "," : "").append("\"s").append(i).append("\":").append(Json.quote(titles.get(i)));
        }
        body.append("}}");

        Object data = post(h, ANILIST, body.toString(), ANILIST_PACE);
        if (data instanceof Map) data = ((Map<?, ?>) data).get("data");
        if (!(data instanceof Map)) return;
        for (int i = 0; i < titles.size(); i++) {
            Object page = ((Map<?, ?>) data).get("t" + i);
            if (!(page instanceof Map)) continue;
            Object media = ((Map<?, ?>) page).get("media");
            if (!(media instanceof List)) continue;
            String key = key(titles.get(i));
            ANILIST_ANSWERS.put(key, judgeAnilist(key, (List<?>) media));
        }
    }

    /** Clean only when some AniList manga has exactly this title and every one that does is clean. */
    static boolean judgeAnilist(String key, List<?> media) {
        boolean matched = false;
        for (Object o : media) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            if (!anilistMatches(key, m)) continue;
            matched = true;
            if (Boolean.TRUE.equals(m.get("isAdult"))) return false;
            StringBuilder tags = new StringBuilder();
            for (Object g : list(m.get("genres"))) {
                String genre = String.valueOf(g);
                if (genre.equalsIgnoreCase("hentai") || genre.equalsIgnoreCase("ecchi")) return false;
                tags.append(genre).append(',');
            }
            for (Object t : list(m.get("tags"))) {
                if (!(t instanceof Map)) continue;
                if (Boolean.TRUE.equals(((Map<?, ?>) t).get("isAdult"))) return false;
                tags.append(((Map<?, ?>) t).get("name")).append(',');
            }
            if (Guard.tagsBlocked(tags.toString())) return false;
        }
        return matched;
    }

    private static boolean anilistMatches(String key, Map<?, ?> m) {
        Object title = m.get("title");
        if (title instanceof Map) {
            for (Object t : ((Map<?, ?>) title).values()) {
                if (t != null && key.equals(key(t.toString()))) return true;
            }
        }
        for (Object s : list(m.get("synonyms"))) {
            if (s != null && key.equals(key(s.toString()))) return true;
        }
        return false;
    }

    // ---- MangaUpdates ----

    private static Boolean mangaUpdates(GuardHost h, String title, String key) {
        Boolean known = MANGAUPDATES_ANSWERS.get(key);
        if (known != null) return known;
        CountDownLatch mine = new CountDownLatch(1);
        CountDownLatch running = MANGAUPDATES_ASKING.putIfAbsent(key, mine);
        if (running != null) {
            await(running);
            return MANGAUPDATES_ANSWERS.get(key);
        }
        try {
            if (MANGAUPDATES_PACE.slot(Guard.CHECK_TIMEOUT_MS)) {
                String body = "{\"search\":" + Json.quote(title) + ",\"perpage\":10}";
                Object root = post(h, MANGAUPDATES, body, MANGAUPDATES_PACE);
                Object results = root instanceof Map ? ((Map<?, ?>) root).get("results") : null;
                if (results instanceof List) MANGAUPDATES_ANSWERS.put(key, judgeMangaUpdates(key, (List<?>) results));
            }
        } catch (Throwable ignored) {
            // no answer: asked again next time
        } finally {
            MANGAUPDATES_ASKING.remove(key);
            mine.countDown();
        }
        return MANGAUPDATES_ANSWERS.get(key);
    }

    /** Clean only when some MangaUpdates series has exactly this title (or it is one of its other names) and every one that does is clean. */
    static boolean judgeMangaUpdates(String key, List<?> results) {
        boolean matched = false;
        for (Object o : results) {
            if (!(o instanceof Map)) continue;
            Object record = ((Map<?, ?>) o).get("record");
            if (!(record instanceof Map)) continue;
            Object hit = ((Map<?, ?>) o).get("hit_title");
            Object name = ((Map<?, ?>) record).get("title");
            boolean same = (hit != null && key.equals(key(hit.toString()))) || (name != null && key.equals(key(name.toString())));
            if (!same) continue;
            matched = true;
            StringBuilder genres = new StringBuilder();
            for (Object g : list(((Map<?, ?>) record).get("genres"))) {
                if (g instanceof Map) genres.append(((Map<?, ?>) g).get("genre")).append(',');
            }
            if (Guard.tagsBlocked(genres.toString())) return false;
        }
        return matched;
    }

    // ---- shared ----

    /** POSTs JSON and returns the parsed answer, or null. A "too many requests" answer pauses that database. */
    private static Object post(GuardHost h, String url, String json, Pace pace) throws IOException {
        OkHttpClient client = h.getClient$gorig();
        Request req = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(new JsonBody(json.getBytes(StandardCharsets.UTF_8)))
                .build();
        Response r = client.newCall(req).execute();
        try {
            if (r.code() == 429) {
                long wait = 60;
                try {
                    wait = Long.parseLong(r.header("Retry-After", "60").trim());
                } catch (NumberFormatException ignored) {
                    // keep 60
                }
                pace.quietUntil = System.currentTimeMillis() + Math.max(5, wait) * 1000;
                return null;
            }
            if (r.code() != 200) return null;
            return Json.parse(r.body().string());
        } finally {
            r.close();
        }
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(Guard.CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<?> list(Object o) {
        return o instanceof List ? (List<?>) o : Collections.emptyList();
    }

    private static Map<String, Boolean> answers() {
        return Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > 10_000;
            }
        });
    }

    /** Lower case, no accents, no apostrophes, punctuation as single spaces. */
    static String key(String title) {
        if (title == null) return "";
        String n = Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("['’`]", "");
        StringBuilder b = new StringBuilder(n.length());
        boolean space = false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (space && b.length() > 0) b.append(' ');
                b.append(c);
                space = false;
            } else {
                space = true;
            }
        }
        return b.toString();
    }

    /** Keeps a database under its requests-per-minute limit. */
    private static final class Pace {
        private final int perMinute;
        private final ArrayDeque<Long> sent = new ArrayDeque<>();
        /** After "too many requests", nothing is asked until then. */
        volatile long quietUntil;

        Pace(int perMinute) {
            this.perMinute = perMinute;
        }

        /** Waits for a turn. False when none comes within maxWaitMs. */
        boolean slot(long maxWaitMs) {
            long deadline = System.currentTimeMillis() + Math.max(0, maxWaitMs);
            synchronized (sent) {
                while (true) {
                    long now = System.currentTimeMillis();
                    while (!sent.isEmpty() && now - sent.peekFirst() >= 60_000) sent.pollFirst();
                    long wait = quietUntil - now;
                    if (sent.size() >= perMinute) wait = Math.max(wait, sent.peekFirst() + 60_000 - now);
                    if (wait <= 0) {
                        sent.addLast(now);
                        return true;
                    }
                    if (now + wait > deadline) return false;
                    try {
                        sent.wait(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
    }

    /** A POST body. The Content-Type is set as a header, so no MediaType is needed (it differs between OkHttp versions). */
    private static final class JsonBody extends RequestBody {
        private final byte[] bytes;

        JsonBody(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public MediaType contentType() {
            return null;
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            sink.write(bytes);
        }
    }
}
