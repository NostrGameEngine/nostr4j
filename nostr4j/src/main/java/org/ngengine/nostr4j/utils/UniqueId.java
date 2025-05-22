package org.ngengine.nostr4j.utils;

import java.util.concurrent.atomic.AtomicLong;

public class UniqueId {
    private static final AtomicLong COUNTER = new AtomicLong(0);
    private static final String INSTANCE_ID = System.currentTimeMillis() +"j"+ random();

    public static String getNext(){
        long current = COUNTER.getAndIncrement();
        return "nostr4j"+INSTANCE_ID+"j"+random()+"j"+current;
    }

    private static String random(){
        int i = (int)(Math.random()*Integer.MAX_VALUE);
        return Integer.toHexString(i);
    }
    
}

