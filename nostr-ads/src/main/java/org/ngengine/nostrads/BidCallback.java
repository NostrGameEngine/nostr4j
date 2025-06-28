package org.ngengine.nostrads;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

@JSFunctor
public interface BidCallback extends JSObject {
    void onBid(JSObject bidEvent, String error);
}