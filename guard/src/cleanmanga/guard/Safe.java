package cleanmanga.guard;

import eu.kanade.tachiyomi.source.model.SChapter;
import eu.kanade.tachiyomi.source.model.SManga;

/** Field reads that never throw (url and title are "lateinit" and throw when unset). */
final class Safe {
    private Safe() {
    }

    static String url(SManga m) {
        try {
            return m.getUrl();
        } catch (Throwable e) {
            return null;
        }
    }

    static String genre(SManga m) {
        try {
            return m.getGenre();
        } catch (Throwable e) {
            return null;
        }
    }

    static String thumbnail(SManga m) {
        try {
            return m.getThumbnail_url();
        } catch (Throwable e) {
            return null;
        }
    }

    static String chapterUrl(Object c) {
        try {
            return c instanceof SChapter ? ((SChapter) c).getUrl() : null;
        } catch (Throwable e) {
            return null;
        }
    }
}
