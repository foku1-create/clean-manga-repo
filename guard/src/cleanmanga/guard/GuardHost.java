package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;
import okhttp3.OkHttpClient;

/**
 * Added by the patcher to the extension's source classes. The {@code $gorig} methods are the
 * extension's own, unguarded implementations, renamed so the guard can sit in front of them.
 */
public interface GuardHost {
    /** True for sites Keiyoushi marks "mixed" or "adult". (A failed check now counts as blocked everywhere.) */
    boolean guard$mixed();

    /** True for sites Keiyoushi marks "adult": a title only shows when AniList confirms it clean, and the site itself never opens. */
    boolean guard$strict();

    /** The extension's package name, to keep each extension's memory apart. */
    String guard$pkg();

    OkHttpClient getClient$gorig();

    String getBaseUrl$gorig();

    String getMangaUrl$gorig(SManga manga);

    String getChapterUrl$gorig(SChapter chapter);
}
