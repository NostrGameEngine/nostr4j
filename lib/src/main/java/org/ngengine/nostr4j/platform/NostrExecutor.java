package org.ngengine.nostr4j.platform;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public interface NostrExecutor {
    public <T> AsyncTask<T> runLater(Callable<T> r,long delay, TimeUnit unit);
    public <T> AsyncTask<T> run(Callable<T> r);
}
