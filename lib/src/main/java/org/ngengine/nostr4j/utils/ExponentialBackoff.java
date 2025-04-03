package org.ngengine.nostr4j.utils;

import java.util.concurrent.TimeUnit;

 
public class ExponentialBackoff {
    private final long initialDelay;
    private final long maxDelay;
    private final float multiplier;
    private final long cooldown;
    private final TimeUnit timeUnit;

    private volatile long currentDelay;
    private volatile long nextAttemptTimestamp = 0;
    private volatile long cooldownStartTimestamp=0;
    
    public ExponentialBackoff() {
        this(1, 2*60, 21, TimeUnit.SECONDS, 2.0f);
    }

  
    public ExponentialBackoff(
        long initialDelay, 
        long maxDelay,
        long cooldown, 
        TimeUnit timeUnit,
        float multiplier
    ) {
        if (initialDelay <= 0) {
            throw new IllegalArgumentException("Initial delay must be positive");
        }
        if (maxDelay < initialDelay) {
            throw new IllegalArgumentException("Max delay must be >= initial delay");
        }
        if (multiplier <= 1.0f) {
            throw new IllegalArgumentException("Multiplier must be > 1.0");
        }
        if (cooldown <= 0) {
            throw new IllegalArgumentException("Cooldown period must be positive");
        }
        this.timeUnit = timeUnit;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.cooldown = cooldown;
        this.currentDelay = initialDelay;
    }

 
    /**
     * Register a failure. This will increase the delay for the next attempt.
     */
    public void registerFailure() {
        cooldownStartTimestamp=0;
        nextAttemptTimestamp = timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS) + currentDelay;
        currentDelay = Math.min((long) (currentDelay * multiplier), maxDelay);
    }

    /**
     * Register a success. This will reset the delay to the initial value.
     */
    public void registerSuccess() {
        cooldownStartTimestamp=timeUnit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }
 
    /**
     * Get the delay for the next attempt.
     * @param now current time
     * @param unit the time unit for the now parameter and the return value
     * @return the delay for the next attempt in the specified time unit
     */
    public long getNextAttemptTime(long now, TimeUnit unit) {
        long nowInternal = timeUnit.convert(now, unit);
        if(cooldownStartTimestamp!=0 && (nowInternal - cooldownStartTimestamp) > cooldown){
            currentDelay = initialDelay;
            cooldownStartTimestamp=0;
            nextAttemptTimestamp = 0;
        }
                
        long remaining = Math.max(0, nextAttemptTimestamp - nowInternal);
        return unit.convert(remaining, timeUnit);
    }

}