package eu.kanade.tachiyomi.source.model;

// Compile-only stand-in for the app's class. Never packaged; the app provides the real one.
public interface SManga {
    Companion Companion = new Companion();

    String getUrl();
    void setUrl(String value);
    String getTitle();
    void setTitle(String value);
    String getThumbnail_url();
    void setThumbnail_url(String value);
    String getArtist();
    void setArtist(String value);
    String getAuthor();
    void setAuthor(String value);
    String getDescription();
    void setDescription(String value);
    String getGenre();
    void setGenre(String value);

    final class Companion {
        public SManga create() {
            throw new RuntimeException("Stub!");
        }
    }
}
