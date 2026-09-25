package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import eu.kanade.tachiyomi.source.model.SMangaUpdate;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

import java.util.Collections;
import java.util.List;

/** Entry points for extension API 1.6. The patcher makes each guarded method call one of these. */
public final class Guard16 {
    private Guard16() {
    }

    public static Object getPopularManga(GuardHost16 h, int page, Continuation<Object> c) {
        Guard.attach(h);
        GuardCont gc = new GuardCont(c, GuardCont.PAGE, null);
        return gc.done(h.getPopularManga$gorig(page, gc));
    }

    public static Object getLatestUpdates(GuardHost16 h, int page, Continuation<Object> c) {
        Guard.attach(h);
        GuardCont gc = new GuardCont(c, GuardCont.PAGE, null);
        return gc.done(h.getLatestUpdates$gorig(page, gc));
    }

    public static Object getSearchManga(GuardHost16 h, int page, String query, FilterList filters, Continuation<Object> c) {
        Guard.attach(h);
        GuardCont gc = new GuardCont(c, GuardCont.PAGE, null);
        return gc.done(h.getSearchManga$gorig(page, query, filters, gc));
    }

    /** Always loads the details too, so the tags are known before any chapter is handed out. */
    public static Object getMangaUpdate(GuardHost16 h, SManga manga, List<?> chapters, boolean fetchDetails, boolean fetchChapters, Continuation<Object> c) {
        Guard.attach(h);
        GuardCont gc = new GuardCont(c, GuardCont.UPDATE, Safe.url(manga));
        return gc.done(h.getMangaUpdate$gorig(manga, chapters, true, fetchChapters, gc));
    }

    public static Object getPageList(GuardHost16 h, SChapter chapter, Continuation<Object> c) {
        Guard.attach(h);
        if (Guard.isBlockedChapter(Safe.chapterUrl(chapter))) throw new BlockedException();
        return h.getPageList$gorig(chapter, c);
    }

    public static Object fetchRelatedMangaList(GuardHost16 h, SManga manga, Continuation<Object> c) {
        Guard.attach(h);
        GuardCont gc = new GuardCont(c, GuardCont.RELATED, null);
        return gc.done(h.fetchRelatedMangaList$gorig(manga, gc));
    }

    static Object update(String url, Object result) {
        SMangaUpdate u = (SMangaUpdate) result;
        if (!Guard.judgeDetails(url, u.getManga())) return u;
        Guard.blockChapters(u.getChapters());
        return new SMangaUpdate(u.getManga(), Collections.<SChapter>emptyList());
    }

    /** Loads a manga's details, waiting for the answer. Used to check a cover before it shows. */
    static SManga details(GuardHost16 h, SManga m) throws Exception {
        BlockingCont bc = new BlockingCont();
        Object r = h.getMangaUpdate$gorig(m, Collections.emptyList(), true, false, bc);
        if (r == IntrinsicsKt.getCOROUTINE_SUSPENDED()) r = bc.await(Guard.CHECK_TIMEOUT_MS);
        return ((SMangaUpdate) r).getManga();
    }
}
