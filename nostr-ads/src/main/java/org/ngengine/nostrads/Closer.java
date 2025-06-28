package org.ngengine.nostrads;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

@JSFunctor
public interface Closer extends JSObject {
    void close();
}