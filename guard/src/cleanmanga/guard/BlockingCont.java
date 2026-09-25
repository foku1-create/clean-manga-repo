package cleanmanga.guard;

import kotlin.Result;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Receives the answer of a suspend function on a thread that is allowed to wait. */
final class BlockingCont implements Continuation<Object> {
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile Object result;

    @Override
    public CoroutineContext getContext() {
        return EmptyCoroutineContext.INSTANCE;
    }

    @Override
    public void resumeWith(Object value) {
        result = value;
        latch.countDown();
    }

    Object await(long timeoutMs) throws Exception {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new TimeoutException("tag check took too long");
        Object r = result;
        if (r instanceof Result.Failure) {
            Throwable e = ((Result.Failure) r).exception;
            if (e instanceof Exception) throw (Exception) e;
            throw new RuntimeException(e);
        }
        return r;
    }
}
