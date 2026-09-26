package cleanmanga.guard;

import kotlin.Result;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Stands between the app and a suspend function of the extension, and filters its result
 * whether it arrives straight away ({@link #done}) or later ({@link #resumeWith}).
 *
 * Lists are screened on the guard's own threads (checking tags takes a few seconds) and then
 * handed back to the app on the app's own dispatcher, so no app thread waits for the checks.
 */
final class GuardCont implements Continuation<Object> {
    static final int PAGE = 1;
    static final int UPDATE = 2;
    static final int RELATED = 3;

    private final GuardHost host;
    private final Continuation<Object> app;
    private final int kind;
    private final String url;

    GuardCont(GuardHost host, Continuation<Object> app, int kind, String url) {
        this.host = host;
        this.app = app;
        this.kind = kind;
        this.url = url;
    }

    @Override
    public CoroutineContext getContext() {
        return app.getContext();
    }

    @Override
    public void resumeWith(Object result) {
        if (result instanceof Result.Failure) {
            app.resumeWith(result);
            return;
        }
        if (kind == UPDATE) {
            app.resumeWith(filterOrFailure(result));
        } else {
            screenLater(result);
        }
    }

    /** The extension answered without suspending. */
    Object done(Object result) {
        if (result == IntrinsicsKt.getCOROUTINE_SUSPENDED()) return result;
        if (kind == UPDATE) return filter(result);
        screenLater(result);
        return IntrinsicsKt.getCOROUTINE_SUSPENDED();
    }

    private void screenLater(final Object result) {
        final Continuation<Object> back = IntrinsicsKt.intercepted(app);
        Guard.POOL.execute(new Runnable() {
            @Override
            public void run() {
                back.resumeWith(filterOrFailure(result));
            }
        });
    }

    private Object filterOrFailure(Object result) {
        try {
            return filter(result);
        } catch (Throwable e) {
            return ResultKt.createFailure(e);
        }
    }

    private Object filter(Object result) {
        switch (kind) {
            case PAGE:
                return Guard.page(host, (eu.kanade.tachiyomi.source.model.MangasPage) result);
            case RELATED:
                return Guard.list(host, (java.util.List<?>) result);
            case UPDATE:
                return Guard16.update(url, result);
            default:
                return result;
        }
    }
}
