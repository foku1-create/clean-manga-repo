package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import kotlin.coroutines.Continuation;
import rx.Observable;
import rx.functions.Func1;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Entry points for extension API 1.4. The patcher makes each guarded method call one of these. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class Guard14 {
    private static final Func1 PAGE = new Func1() {
        @Override
        public Object call(Object page) {
            return Guard.page((MangasPage) page);
        }
    };

    private Guard14() {
    }

    public static Observable fetchPopularManga(GuardHost14 h, int page) {
        Guard.attach(h);
        return ((Observable) h.fetchPopularManga$gorig(page)).map(PAGE);
    }

    public static Observable fetchLatestUpdates(GuardHost14 h, int page) {
        Guard.attach(h);
        return ((Observable) h.fetchLatestUpdates$gorig(page)).map(PAGE);
    }

    public static Observable fetchSearchManga(GuardHost14 h, int page, String query, FilterList filters) {
        Guard.attach(h);
        return ((Observable) h.fetchSearchManga$gorig(page, query, filters)).map(PAGE);
    }

    public static Observable fetchMangaDetails(GuardHost14 h, SManga manga) {
        Guard.attach(h);
        final String url = Safe.url(manga);
        return ((Observable) h.fetchMangaDetails$gorig(manga)).map(new Func1() {
            @Override
            public Object call(Object details) {
                Guard.judgeDetails(url, (SManga) details);
                return details;
            }
        });
    }

    /** Hands out chapters only once the manga's tags are known to be clean. */
    public static Observable fetchChapterList(final GuardHost14 h, final SManga manga) {
        Guard.attach(h);
        final String url = Safe.url(manga);
        Boolean known = Guard.verdict(url);
        if (Boolean.TRUE.equals(known)) return Observable.just(Collections.emptyList());
        if (Boolean.FALSE.equals(known)) return (Observable) h.fetchChapterList$gorig(manga);

        Observable details = ((Observable) h.fetchMangaDetails$gorig(manga))
                .take(1)
                .onErrorReturn(new Func1() {
                    @Override
                    public Object call(Object error) {
                        return null;
                    }
                });
        return details.flatMap(new Func1() {
            @Override
            public Object call(Object d) {
                boolean blocked = d == null ? h.guard$mixed() : Guard.judgeDetails(url, (SManga) d);
                if (blocked) return Observable.just(Collections.emptyList());
                return h.fetchChapterList$gorig(manga);
            }
        });
    }

    public static Observable fetchPageList(GuardHost14 h, SChapter chapter) {
        Guard.attach(h);
        if (Guard.isBlockedChapter(Safe.chapterUrl(chapter))) return Observable.error(new BlockedException());
        return (Observable) h.fetchPageList$gorig(chapter);
    }

    public static Object fetchRelatedMangaList(GuardHost14 h, SManga manga, Continuation<Object> c) {
        Guard.attach(h);
        GuardCont gc = new GuardCont(c, GuardCont.RELATED, null);
        return gc.done(h.fetchRelatedMangaList$gorig(manga, gc));
    }

    /** Loads a manga's details, waiting for the answer. Used to check a cover before it shows. */
    static SManga details(GuardHost14 h, SManga m) {
        Observable o = (Observable) h.fetchMangaDetails$gorig(m);
        return (SManga) o.timeout(Guard.CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS).toBlocking().first();
    }
}
