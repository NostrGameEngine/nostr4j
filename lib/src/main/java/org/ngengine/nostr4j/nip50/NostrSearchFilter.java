package org.ngengine.nostr4j.nip50;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.ngengine.nostr4j.NostrFilter;

public class NostrSearchFilter extends NostrFilter{
    private String search;

    public NostrSearchFilter search(String search) {
        this.search = search;
        return this;
    }

    public NostrSearchFilter(){

    }
    protected Map<String,Object> toMap( ){
        Map<String,Object> map = super.toMap();
        if (search != null&&!search.isEmpty()) {
            map.put("search", search);
        }
        return map;
    }

    public NostrSearchFilter(Map<String, Object> map) throws Exception{
        super(map);
        if (map.containsKey("search")) {
            String search = (String) map.get("search");
            if(!search.isEmpty()){
                this.search = search;
            }
        }       
    }


    public NostrSearchFilter id(String id){
        return (NostrSearchFilter)super.id(id);
    }

    public NostrSearchFilter author(String author){
        return (NostrSearchFilter)super.author(author);
    }

    public NostrSearchFilter kind(int kind){
        return (NostrSearchFilter)super.kind(kind);
    }

    public NostrSearchFilter since(Instant since){
        return (NostrSearchFilter)super.since(since);
    }

    public NostrSearchFilter until(Instant until){
        return (NostrSearchFilter)super.until(until);
    }

    public NostrSearchFilter limit(int limit){
        return (NostrSearchFilter)super.limit(limit);
    }

    public NostrSearchFilter tag(String key, String ...values){
        return (NostrSearchFilter)super.tag(key, values);
    }

}
