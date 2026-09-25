package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import kotlin.coroutines.Continuation;

import java.util.List;

/** Extension API 1.6 (suspend functions). */
public interface GuardHost16 extends GuardHost {
    Object getPopularManga$gorig(int page, Continuation<?> c);

    Object getLatestUpdates$gorig(int page, Continuation<?> c);

    Object getSearchManga$gorig(int page, String query, FilterList filters, Continuation<?> c);

    Object getMangaUpdate$gorig(SManga manga, List<?> chapters, boolean fetchDetails, boolean fetchChapters, Continuation<?> c);

    Object getPageList$gorig(SChapter chapter, Continuation<?> c);

    Object fetchRelatedMangaList$gorig(SManga manga, Continuation<?> c);
}
