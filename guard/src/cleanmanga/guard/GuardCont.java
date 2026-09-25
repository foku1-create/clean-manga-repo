package cleanmanga.guard;

import kotlin.Result;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/**
 * Stands between the app and a suspend function of the extension, and filters its result
 * whether it arrives straight away ({@link #done}) or later ({@link #resumeWith}).
 */
final class GuardCont implements Continuation<Object> {
    static final int PAGE = 1;
    static final int UPDATE = 2;
    static final int RELATED = 3;

    private final Continuation<Object> app;
    private final int kind;
    private final String url;

    GuardCont(Continuation<Object> app, int kind, String url) {
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
        Object out;
        try {
            out = filter(result);
        } catch (Throwable e) {
            out = ResultKt.createFailure(e);
        }
        app.resumeWith(out);
    }

    /** The extension answered without suspending. */
    Object done(Object result) {
        if (result == IntrinsicsKt.getCOROUTINE_SUSPENDED()) return result;
        return filter(result);
    }

    private Object filter(Object result) {
        switch (kind) {
            case PAGE:
                return Guard.page((eu.kanade.tachiyomi.source.model.MangasPage) result);
            case RELATED:
                return Guard.list((java.util.List<?>) result);
            case UPDATE:
                return Guard16.update(url, result);
            default:
                return result;
        }
    }
}
