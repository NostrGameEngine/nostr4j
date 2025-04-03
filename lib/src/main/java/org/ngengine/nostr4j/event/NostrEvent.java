package org.ngengine.nostr4j.event;
import java.util.Arrays;
import java.util.Collection;
import org.ngengine.nostr4j.utils.NostrUtils;


public interface NostrEvent extends Cloneable {
    static final byte[] BECH32_PREVIX = "note".getBytes();
    public long getCreatedAt();
    public int getKind();    
    public String getContent();    
    public Collection<String[]> listTags();
    public String[] getTag(String key);
     

    public static String computeEventId(String pubkey, NostrEvent event){
        try{
            Collection<Object> serial = Arrays.asList(
                0, 
                pubkey,
                event.getCreatedAt(),
                event.getKind(),
                event.listTags(),
                event.getContent()
            );
            String json = NostrUtils.getPlatform().toJSON(serial);
            String id= NostrUtils.getPlatform().sha256(json);
            return id;
        }catch(Exception e){
            return null;
        }

    }
 
}
