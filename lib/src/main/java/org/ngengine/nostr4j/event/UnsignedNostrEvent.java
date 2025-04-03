package org.ngengine.nostr4j.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.ngengine.nostr4j.utils.NostrUtils;

public class UnsignedNostrEvent implements NostrEvent {
    
    private long createdAt = System.currentTimeMillis()/1000;
    private int kind = 1;
    private String content = "";
    private Map<String,String[]> tags = new HashMap<String,String[]>();
  
    private transient Collection<String[]> taglist;

    public UnsignedNostrEvent setKind(int kind) {
        this.kind = kind;
     
        return this;
    }

    public UnsignedNostrEvent setContent(String content) {
        this.content = content;

        return this;
    }

    public UnsignedNostrEvent setCreatedAt(long created_at) {
        this.createdAt = created_at;
 
        return this;
    }

    public void setTag(String ...tag){
        tags.put(tag[0], tag);
  
    }

    @Override
    public String[] getTag(String key){
        return tags.get(key);
    }

    public UnsignedNostrEvent setTags(Collection<String[]> tags){
        this.tags.clear();
        for(String[] tag : tags){
            this.tags.put(tag[0], tag);
        }

        return this;
    }

    @Override
    public Collection<String[]> listTags(){
        if(taglist == null){
            taglist = Collections.unmodifiableCollection(tags.values());
        }
        return taglist;
    }

  
    public UnsignedNostrEvent fromMap(Map<String,Object> map){      
        this.kind = NostrUtils.safeInt(map.get("kind"));
        this.content = map.get("content").toString();
        this.createdAt = NostrUtils.safeLong(map.get("created_at"));
        Collection<String[]> tags = NostrUtils.safeCollectionOfStringArray(map.getOrDefault("tags", new ArrayList<String[]>()));
        setTags(tags);
        return this;
    }

     
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UnsignedNostrEvent{");
        sb.append("createdAt=").append(createdAt);
        sb.append(", kind=").append(kind);
        sb.append(", content='").append(content).append('\'');
        sb.append(", tags=").append(tags);
        sb.append('}');
        return sb.toString();        
    }

    @Override
    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public int getKind() {
        return kind;
    }

    @Override
    public String getContent() {
        return content;
    }

  

    @Override
    public boolean equals(Object obj) {
        if(obj == null || !(obj instanceof UnsignedNostrEvent))return false;
        if(obj == this) return true;
        
        UnsignedNostrEvent e = (UnsignedNostrEvent)obj;
        return e.getCreatedAt() == getCreatedAt() && e.getKind() == getKind() && e.getContent().equals(getContent()) && e.listTags().equals(listTags());
    }

    @Override
    public UnsignedNostrEvent clone() {
        return new UnsignedNostrEvent().setKind(kind).setContent(content).setTags(listTags()).setCreatedAt(createdAt);
    }



    
}
