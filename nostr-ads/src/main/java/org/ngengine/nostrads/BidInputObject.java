package org.ngengine.nostrads;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;

public interface BidInputObject extends JSObject {
  

    @JSProperty
    String getDescription();
    
   
    @JSProperty
    String getMimeType();
    

    @JSProperty
    String getPayload();
    

    @JSProperty
    String getSize();
    
  
    @JSProperty
    String getLink();
    
 
    @JSProperty
    String getActionType();
    

    @JSProperty
    String getCallToAction();
    

    @JSProperty
    String getDelegate();
    

    @JSProperty
    double getBidMsats();
    

    @JSProperty
    double getHoldTime();
    

    @JSProperty
    double getExpireAt();

    @JSProperty
    String[] getCategories();
    

    @JSProperty
    String[] getLanguages();
    

    @JSProperty
    String[] getOfferersWhitelist();
    

    @JSProperty
    String[] getAppsWhitelist();
}
