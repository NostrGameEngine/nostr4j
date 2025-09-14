package org.ngengine.nostr4j;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.platform.NGEPlatform;

public class Propagator {
    public static void main(String[] args) throws Exception {
        String relayListUrl = "https://raw.githubusercontent.com/sesseor/nostr-relays-list/refs/heads/main/relays.txt";
        List<String> sourceRelay = Arrays.asList(
            args[0]
        );
        String sourceEvent = args[1];


        NostrPool pool = new NostrPool();
        for (String relay : sourceRelay) {
            pool.connectRelay(new NostrRelay(relay));
        }

        
        
        List<SignedNostrEvent> events = pool.fetch(new NostrFilter().withId(sourceEvent)).await();
        SignedNostrEvent event = events.get(0);
        if(event == null) {
            System.out.println("Event not found");
            return;
        }

        pool.close();

        String relayList = NGEPlatform.get().httpGet(relayListUrl, Duration.ofSeconds(60), null).await();
        String[] relays = relayList.split("\n");
        for (String relay : relays) {
            try{
                System.out.println("Publishing to " + relay);
                relay = relay.trim();
                if (relay.isEmpty() || relay.startsWith("#")) {
                    continue;
                }
                pool = new NostrPool();
                pool.ensureRelay(relay).await();
                pool.publish(event).await();
                System.out.println("Published to " + relay);
                pool.close();
            } catch (Exception e) {
            }
        }






    }
}
