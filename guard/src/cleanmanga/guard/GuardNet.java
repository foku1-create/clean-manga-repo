package cleanmanga.guard;

import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The app downloads covers through the extension's network client. The guard hands the app a
 * copy of that client with a gate in front: a cover of a manga whose tags are not checked yet
 * waits for the check, and blocked covers come back black.
 */
public final class GuardNet {
    private static final Map<OkHttpClient, OkHttpClient> GUARDED =
            Collections.synchronizedMap(new WeakHashMap<OkHttpClient, OkHttpClient>());

    private GuardNet() {
    }

    public static OkHttpClient getClient(GuardHost h) {
        Guard.attach(h);
        OkHttpClient base = h.getClient$gorig();
        if (base == null) return null;
        OkHttpClient guarded = GUARDED.get(base);
        if (guarded == null) {
            OkHttpClient.Builder b = base.newBuilder();
            b.interceptors().add(0, new CoverGate(h));
            // Own request queue: covers waiting for a check must not use up the slots the check needs.
            Dispatcher d = new Dispatcher();
            d.setMaxRequests(base.dispatcher().getMaxRequests());
            d.setMaxRequestsPerHost(base.dispatcher().getMaxRequestsPerHost());
            b.dispatcher(d);
            guarded = b.build();
            GUARDED.put(base, guarded);
        }
        return guarded;
    }

    /** Covers are matched by their url as OkHttp writes it. */
    static String key(String url) {
        try {
            HttpUrl u = HttpUrl.parse(url);
            return u == null ? url : u.toString();
        } catch (Throwable e) {
            return url;
        }
    }

    static final class CoverGate implements Interceptor {
        private final GuardHost host;

        CoverGate(GuardHost host) {
            this.host = host;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            String url = request.url().toString();
            if (url.equals(key(Guard.BLOCKED_COVER))) return black(request);

            eu.kanade.tachiyomi.source.model.SManga owner = Guard.coverOwner(url);
            if (owner == null) return chain.proceed(request);
            Boolean blocked = Guard.check(host, owner);
            if (blocked == null) blocked = host.guard$mixed();
            return blocked ? black(request) : chain.proceed(request);
        }
    }

    static Response black(Request request) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "image/png")
                .body(new PngBody())
                .build();
    }

    private static final class PngBody extends ResponseBody {
        @Override
        public MediaType contentType() {
            return null;
        }

        @Override
        public long contentLength() {
            return Tags.COVER_PNG.length;
        }

        @Override
        public BufferedSource source() {
            return new Buffer().write(Tags.COVER_PNG);
        }
    }
}
