package org.ngengine.nostr4j.platform;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public interface AsyncTask<T> {
    public boolean isDone();
     public boolean isFailed();
    public boolean isSuccess();
    public T await()  throws Exception;    
    public <R> AsyncTask<R> then(Function<T, R> func2);
    public  AsyncTask<T> exceptionally(Consumer<Throwable> func2);
}
