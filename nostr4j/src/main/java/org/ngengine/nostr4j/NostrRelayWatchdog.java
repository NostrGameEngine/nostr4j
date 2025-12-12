package org.ngengine.nostr4j;

import java.time.Duration;
import java.time.Instant;

import org.ngengine.nostr4j.listeners.NostrRelayComponent;
import org.ngengine.nostr4j.proto.NostrMessage;

/**
 * Last resort watchdog that attempts to reconnect to the relay
 * if it becomes unresponsive or broken even if the connection is still alive.
 * (eg. some invalid state or breakage on the relay side)
 */
public class NostrRelayWatchdog implements NostrRelayComponent {

    private Instant lastCheck = Instant.EPOCH;
    private Duration checkInterval = Duration.ofMinutes(10);

    private void watchdog(NostrRelay relay){
        if(
            relay.getStatus() != NostrRelay.Status.CONNECTED
        ){
            return;
        }
        NostrPool pool = new NostrPool();
        pool.connectRelay(relay);
        pool.fetch(new NostrFilter().limit(1).withKind(0),1,Duration.ofMinutes(5)).then(evs->{
            pool.close();
            return null;
        }).catchException(ex->{
            pool.close();
            relay.disconnect("killed by watchdog due to unresponsiveness", true);
        });
    }

    @Override
    public boolean onRelayConnectRequest(NostrRelay relay) {
        return true;
    }

    @Override
    public boolean onRelayConnect(NostrRelay relay) {
        return true;
    }

    @Override
    public boolean onRelayMessage(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelayError(NostrRelay relay, Throwable error) {
       return true;
    }

    @Override
    public boolean onRelayLoop(NostrRelay relay, Instant nowInstant) {
        if(Duration.between(this.lastCheck, nowInstant).compareTo(this.checkInterval) >= 0){
            this.lastCheck = nowInstant;
            this.watchdog(relay);
        }
        return true;
    }

    @Override
    public boolean onRelayDisconnect(NostrRelay relay, String reason, boolean byClient) {
        return true;
    }

    @Override
    public boolean onRelayBeforeSend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelaySend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelayAfterSend(NostrRelay relay, NostrMessage message) {
        return true;
    }

    @Override
    public boolean onRelayDisconnectRequest(NostrRelay relay, String reason) {
        return true;
    }
    
}
