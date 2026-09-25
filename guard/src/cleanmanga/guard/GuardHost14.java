package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import kotlin.coroutines.Continuation;
import rx.Observable;

/** Extension API 1.4 (RxJava). */
public interface GuardHost14 extends GuardHost {
    Observable<?> fetchPopularManga$gorig(int page);

    Observable<?> fetchLatestUpdates$gorig(int page);

    Observable<?> fetchSearchManga$gorig(int page, String query, FilterList filters);

    Observable<?> fetchMangaDetails$gorig(SManga manga);

    Observable<?> fetchChapterList$gorig(SManga manga);

    Observable<?> fetchPageList$gorig(SChapter chapter);

    Object fetchRelatedMangaList$gorig(SManga manga, Continuation<?> c);
}
