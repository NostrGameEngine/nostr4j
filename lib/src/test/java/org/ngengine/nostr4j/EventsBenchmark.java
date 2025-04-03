package org.ngengine.nostr4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.PassthroughEventTracker;
import org.ngengine.nostr4j.listeners.NostrRelayListener;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.platform.jvm.JVMThreadedPlatform;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.utils.NostrUtils;

public class EventsBenchmark {
    private static final int EVENTS = 200;

    public Collection<List<Object>> generateMessages(String subId) throws Exception {
        Collection<List<Object>> messages = new ArrayList<>();
        String baseContent = "";
        for (int i = 0; i < EVENTS; i++) {
            baseContent += "a";
        }
        NostrKeyPairSigner signer = NostrKeyPairSigner.generate();
        for (int i = 0; i < EVENTS; i++) {
            UnsignedNostrEvent event = new UnsignedNostrEvent();
            for (int t = 0; t < i; t++) {
                event.setTag("t", "nada");
            }
            event.setKind(1);
            event.setContent(baseContent.substring(0, EVENTS - i));
            SignedNostrEvent signed = signer.sign(event);
            List<Object> message = new ArrayList<>();
            message.addAll(NostrMessage.toSerial(signed));
            message.add(1, subId);
            messages.add(message);
        }
        return messages;
    }

    Collection<List<Object>> messages;
    NostrSubscription sub;
    NostrPool pool;
    NostrRelay relay;

    public EventsBenchmark(boolean trusted, boolean threaded) throws Exception {
        if(threaded)NostrUtils.setPlatform(new JVMThreadedPlatform());
        pool = new NostrPool();
        pool.setVerifyEvents(!trusted);
        relay = new NostrRelay("wss:/127.0.0.1");
        pool.ensureRelay(relay);
        NostrSubscription sub = pool.subscribe(new NostrFilter(), PassthroughEventTracker.class);
        messages = generateMessages(sub.getSubId());      
    }

    public String run(int iterations) throws Exception {
        long sum = 0;
        long min = Long.MAX_VALUE;
        // warmup
        for (List<Object> message : messages) {
            pool.onRelayMessage(relay, message);
        }

        for (int i = 0; i < iterations; i++) {

            // long iterationSum = 0;
            long t = System.currentTimeMillis();
            for (List<Object> message : messages) {
                pool.onRelayMessage(relay, message);
                // iterationSum += System.currentTimeMillis() - t;
            }
            long iterationSum = System.currentTimeMillis() - t;
            sum+= iterationSum;
            if(iterationSum < min){
                min = iterationSum;
            }
        }
        
        sum = sum / iterations;
        return "avg "+ (sum) + "ms - min " + (min) + "ms";
        
    }

    public static void main(String[] args) throws Exception {
        EventsBenchmark benchmark = new EventsBenchmark(false, false);
        String t;

        benchmark = new EventsBenchmark(false,false);
        t = benchmark.run(3);
        System.out.println("Time: " + (t) );

        benchmark = new EventsBenchmark(false, true);
        t = benchmark.run(3);
        System.out.println("Time (threaded): " + (t));

        benchmark = new EventsBenchmark(true, false);
        t = benchmark.run(3);
        System.out.println("Time (trusted): " + (t) );
    }
}