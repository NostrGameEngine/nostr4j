package org.ngengine.nostr4j.platform.jvm;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;

public class JVMThreadedPlatform extends JVMAsyncPlatform {

    public JVMThreadedPlatform() {
        super();
    }


    private NostrExecutor newExecutor(){
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

        return new NostrExecutor() {
            @Override
            public <T> AsyncTask<T> run(Callable<T> r) {
                return promisify((res, rej) -> {
                    executor.submit(() -> {
                        try {
                            res.accept(r.call());
                        } catch (Exception e) {
                            rej.accept(e);
                        }
                    });
                });
            }

            @Override
            public <T> AsyncTask<T> runLater(Callable<T> r, long delay, TimeUnit unit){
                
                return promisify((res, rej) -> {
                    executor.schedule(() -> {
                        try {
                            res.accept(r.call());
                        } catch (Exception e) {
                            rej.accept(e);
                        }
                    }, delay, unit);
                });
            }

        };

    }
   
    public NostrExecutor newRelayExecutor() {
        return newExecutor();
    }

    public NostrExecutor newSubscriptionExecutor() {
        return newExecutor();
    }

    @Override
    public NostrExecutor newPoolExecutor() {
        return newExecutor();
    }
    
}
