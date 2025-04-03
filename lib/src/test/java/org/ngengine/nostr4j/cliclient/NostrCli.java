package org.ngengine.nostr4j.cliclient;

import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrSubscription;
import org.ngengine.nostr4j.TestLogger;

public class NostrCli {
    private static final Logger rootLogger = TestLogger.getRoot();
    
 
    public static void main(String _a[]){
    


        // initialize pool
        NostrPool pool = new NostrPool();
        // add relays
        pool.ensureRelay("wss://nostr.rblb.it");

        // listen for notices
        pool.addNoticeListener((relay, msg) -> {
            System.out.println("Notice: " + msg+" from relay: " + relay);
        });

        // initialize subscription
        NostrSubscription sub = pool.subscribe(
            new NostrFilter()
            .kind(1)
            .limit(2)
        );

        // append listeners
        sub.listenClose((s,reason) -> {
            // System.out.println("Subscription closed: " + s + " reason: " + reason);
            rootLogger.fine("Subscription closed: " + s + " reason: " + reason);
        });

        sub.listenEvent((s, event, stored) -> {
            System.out.println("!!! Event: " + event+" stored: " + stored);
        });
        
        sub.listenEose(s -> {
            rootLogger.fine("End of stored events: " + s);
        });


        // start sub
        sub.open();

        // System.out.println("started: " + sub);
        rootLogger.info("started: " + sub);

        // sleep for ever
        while(true){
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        
    }
    
}
