package org.ngengine.nostr4j.pool.fetchpolicy;

import java.util.function.Consumer;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.ThrowableFunction;

/**
 * Associates a fetch result with the lifetime of its underlying subscription.
 * Cancelling this task closes the subscription before cancelling the delegated task.
 * Other operations delegate to the result task; tasks returned by then or compose
 * are ordinary chained tasks and do not themselves retain this close action.
 *
 * @param <T> fetch result type
 */
public final class NostrFetchTask<T> implements AsyncTask<T> {
    private final AsyncTask<T> task;
    private final Runnable close;

    /**
     * Wraps a result task with the action that retires its subscription.
     * The close action should be idempotent because cancellation may be requested more
     * than once or after normal fetch completion.
     *
     * @param task underlying fetch result
     * @param close action that closes the associated subscription
     */
    public NostrFetchTask(AsyncTask<T> task, Runnable close) {
        this.task = task;
        this.close = close;
    }

    @Override public void cancel() { close.run(); task.cancel(); }
    @Override public boolean isDone() { return task.isDone(); }
    @Override public boolean isFailed() { return task.isFailed(); }
    @Override public boolean isSuccess() { return task.isSuccess(); }
    @Override public T await() throws Exception { return task.await(); }
    @Override public <R> AsyncTask<R> then(ThrowableFunction<T, R> function) { return task.then(function); }
    @Override public <R> AsyncTask<R> compose(ThrowableFunction<T, AsyncTask<R>> function) { return task.compose(function); }
    @Override public AsyncTask<T> catchException(Consumer<Throwable> function) { task.catchException(function); return this; }
}
