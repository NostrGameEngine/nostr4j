package org.ngengine.nostr4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.tracker.EventTracker;
import org.ngengine.nostr4j.listeners.sub.NostrSubCloseListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubEoseListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubEventListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubListener;
import org.ngengine.nostr4j.listeners.sub.NostrSubStatusListener;
import org.ngengine.nostr4j.platform.AsyncTask;
import org.ngengine.nostr4j.platform.NostrExecutor;
import org.ngengine.nostr4j.platform.Platform;
import org.ngengine.nostr4j.transport.NostrMessage;
import org.ngengine.nostr4j.transport.NostrMessageAck;
import org.ngengine.nostr4j.utils.NostrUtils;

public class NostrSubscription extends NostrMessage {
    private static final Logger logger = Logger.getLogger(NostrSubscription.class.getName());
    protected final EventTracker eventTracker;
    private final String subId;

    private final Collection<NostrSubEoseListener> onEoseListeners = new CopyOnWriteArrayList<>();
    private final Collection<NostrSubEventListener> onEventListeners = new CopyOnWriteArrayList<>();
    private final Collection<NostrSubCloseListener> onCloseListeners = new CopyOnWriteArrayList<>();

    private boolean eose;
    private final NostrExecutor executor;
    private final Collection<NostrFilter> filters;

    private final Function<NostrSubscription, AsyncTask<List<NostrMessageAck>>> onOpen;
    private final BiFunction<NostrSubscription,NostrSubCloseMessage, AsyncTask<List<NostrMessageAck>>> onClose;

    protected NostrSubscription(
            String subId, 
            Collection<NostrFilter> filters, 
            EventTracker eventTracker,
            Function<NostrSubscription,AsyncTask<List<NostrMessageAck>>> onOpen,
            BiFunction<NostrSubscription, NostrSubCloseMessage, AsyncTask<List<NostrMessageAck>>> onClose
    ) {
        Platform platform =NostrUtils.getPlatform();
        this.subId = subId;
        this.eventTracker = eventTracker;
        this.executor = platform.newSubscriptionExecutor();
        this.filters = filters;
        this.onOpen = onOpen;
        this.onClose = onClose;
    }
 

    public String getId() {
        return this.subId;
    }

    public AsyncTask<List<NostrMessageAck>> open() {
        return this.onOpen.apply(this);
    }

    public AsyncTask<List<NostrMessageAck>> close(String reason) {
        AsyncTask<List<NostrMessageAck>> out = this.onClose.apply(this, getCloseMessage(reason));
        callCloseListeners(reason);
        return out;
    }

    public String getSubId() {
        return subId;
    }

    protected void setEose(boolean v) {
        this.eose = true;
    }

    public boolean isEose() {
        return this.eose;
    }

    public NostrSubscription listenEvent(NostrSubEventListener listener) {
        return listen(listener);
    }

    public NostrSubscription listenEose(NostrSubEoseListener listener) {
        return listen(listener);
    }

    public NostrSubscription listenClose(NostrSubCloseListener listener) {
        return listen(listener);
    }

    public NostrSubscription listen(NostrSubListener listener) {
        if (listener instanceof NostrSubEoseListener) {
            onEoseListeners.add((NostrSubEoseListener) listener);
        }
        if (listener instanceof NostrSubEventListener) {
            onEventListeners.add((NostrSubEventListener) listener);
        }
        if (listener instanceof NostrSubCloseListener) {
            onCloseListeners.add((NostrSubCloseListener) listener);
        }
        return this;
    }

    public NostrSubscription stopListening(NostrSubListener listener) {
        if (listener instanceof NostrSubEoseListener) {
            onEoseListeners.remove((NostrSubEoseListener) listener);
        }
        if (listener instanceof NostrSubEventListener) {
            onEventListeners.remove((NostrSubEventListener) listener);
        }
        if (listener instanceof NostrSubCloseListener) {
            onCloseListeners.remove((NostrSubCloseListener) listener);
        }
        return this;
    }

    protected void callEoseListeners() {
        if(onEoseListeners.isEmpty())return;
        for (NostrSubEoseListener listener : onEoseListeners) {
            this.executor.run(() -> {
                listener.onSubEose(this);
                return null;
            }).exceptionally(ex->{
                logger.warning("Error calling EOSE listener: " + listener + " " + ex);
             });
        }
    }

    protected void callEventListeners(SignedNostrEvent event, boolean stored) {
        if(onEventListeners.isEmpty())return;
        for (NostrSubEventListener listener : onEventListeners) {
            this.executor.run(() -> {
                    listener.onSubEvent(this, event, stored);
                    return null;
              
            }).exceptionally(ex -> {
                logger.warning("Error calling EOSE listener: " + listener + " " + ex);
            });
        }
    }

    protected void callCloseListeners(String reason) {
        if(onCloseListeners.isEmpty())return;
        for (NostrSubCloseListener listener : onCloseListeners) {
            this.executor.run(() -> {
                listener.onSubClose(this, reason);
                return null;
            }).exceptionally(ex -> {
                logger.warning("Error calling EOSE listener: " + listener + " " + ex);
            });
        }
    }

    @Override
    protected String getPrefix() {
        return "REQ";
    }

    @Override
    protected Collection<Object> getFragments() {
        List<Object> fragments = new ArrayList<>(filters.size() + 1);
        fragments.add(subId);
        for (NostrFilter filter : filters) {
            fragments.add(filter);
        }
        return fragments;
    }

    static final class NostrSubCloseMessage extends NostrMessage {
        private transient List<Object> fragments;
        private final String id;
        private final String message;

        public NostrSubCloseMessage(String id, String message) {
            this.id = id;
            this.message = message;
        }

        public String getId() {
            return id;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String getPrefix() {
            return "CLOSE";
        }

        @Override
        public Collection<Object> getFragments() {
            if (fragments != null) return fragments;
            fragments = new ArrayList<>(2);
            fragments.add(id);
            fragments.add(message);
            return fragments;
        }
    };

    private NostrSubCloseMessage getCloseMessage(String reason) {
        return new NostrSubCloseMessage(getId(), reason);
    }


}
