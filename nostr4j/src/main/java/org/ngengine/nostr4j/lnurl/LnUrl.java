package org.ngengine.nostr4j.lnurl;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.ngengine.nostr4j.lnurl.services.LnUrlPayRequest;
import org.ngengine.nostr4j.lnurl.services.LnUrlException;
import org.ngengine.nostr4j.utils.Bech32;
import org.ngengine.nostr4j.utils.Bech32.Bech32DecodingException;
import org.ngengine.nostr4j.utils.Bech32.Bech32EncodingException;
import org.ngengine.nostr4j.utils.Bech32.Bech32InvalidChecksumException;
import org.ngengine.nostr4j.utils.Bech32.Bech32InvalidRangeException;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;

// lud06
public class LnUrl {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);
    private static final byte[] hrp = "lnurl".getBytes();
    private final static Logger logger = Logger.getLogger(LnUrl.class.getName());
    private final String bech32;
    private final URI plainUrl;
    private final String tag;

    private record Lud16Type(
        String prefix,
        String tag
    ){
        public boolean isAssignableTo(String lnurl) {
            return lnurl.toLowerCase().trim().startsWith(prefix + "://");
        }

        public String toHttps(String url){
            if (!isAssignableTo(url)) {
                throw new IllegalArgumentException("URL does not start with the expected prefix: " + prefix);
            }
            return "https://"+url.substring(prefix.length() + 3); // +3 for "://"

        }
    };

    private static final List<Lud16Type> LUD_16_TYPES = List.of(
        new Lud16Type("lnurlc", "channelRequest"),
        new Lud16Type("lnurlw", "withdrawRequest"),
        new Lud16Type("lnurlp", "payRequest"),
        new Lud16Type("keyauth", "login")
    );

    public static boolean isLud16(String lnurl) {
        if (lnurl == null || lnurl.isEmpty()) return false;
        return LUD_16_TYPES.stream()
            .anyMatch(prefix -> prefix.isAssignableTo(lnurl));
    }

    private static Lud16Type getLud16(String lnurl) {
        if (lnurl == null || lnurl.isEmpty()) return null;
        return LUD_16_TYPES.stream()
            .filter(prefix -> prefix.isAssignableTo(lnurl))
            .findFirst()
            .orElse(null);
    }
    
    public LnUrl(String lnurl) throws   URISyntaxException{
        try{
            lnurl = lnurl.toLowerCase().trim();
            if(lnurl.startsWith("lightning:")){
                lnurl = lnurl.substring(10);
            }

            Lud16Type lud16 = getLud16(lnurl);
            if(lud16!=null){
                logger.finer("LUD16 lnurl: " + lnurl);

                plainUrl = new URI(lud16.toHttps(lnurl));
                logger.finer("Decoded lnurl: " + plainUrl);

                bech32 = Bech32.bech32Encode(hrp, ByteBuffer.wrap(plainUrl.toString().getBytes()));
                logger.finer("Bech32 lnurl: " + bech32);

                tag = lud16.tag;
                logger.finer("LUD16 tag: " + tag);
            } else {
                this.bech32 = lnurl;
                logger.finer("Bech32 lnurl: " + bech32);
                
                ByteBuffer decodedB = Bech32.bech32Decode(lnurl);
                plainUrl = new URI(new String(decodedB.array(), 0, decodedB.limit()));        
                logger.finer("Decoded lnurl: " + plainUrl);

                tag = loadTag();
                logger.finer("Tag: " + tag);
            }   
        }  catch(Exception e){
            if(e instanceof URISyntaxException){
                throw (URISyntaxException)e;
            } else {
                throw new URISyntaxException(lnurl, "Failed to parse LNURL: " + e.getMessage(), 0);
            }
        }

    }


    protected LnUrl(URI url) throws URISyntaxException {
        try{
            plainUrl = url;
            bech32 = Bech32.bech32Encode(hrp, ByteBuffer.wrap(url.toString().getBytes()));
            tag = loadTag();
        } catch(Exception e){
            throw new URISyntaxException(url.toString(), "Failed to parse LNURL: " + e.getMessage(), 0);
        }
    }

    private String loadTag(){
        String tag = null;
        if (plainUrl.getQuery() != null) {
            String[] params = plainUrl.getQuery().split("&");
            for (String param : params) {
                if (param.startsWith("tag=")) {
                    tag = param.substring(4);
                    tag = URLDecoder.decode(tag, StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        return tag;
    }
 
    public static LnUrl encode(
            URI url) throws Bech32DecodingException, Bech32InvalidChecksumException, Bech32InvalidRangeException,
            URISyntaxException, Bech32EncodingException {
        return new LnUrl(url);
    }

    public static LnUrl encode(String url) throws Bech32DecodingException, Bech32InvalidChecksumException,
            Bech32InvalidRangeException, URISyntaxException, Bech32EncodingException {
        return new LnUrl(new URI(url));
    }

    public String toLnUrlLink() {
        return "lightning:"+bech32;
    }

  
    public URI toURI(){
        return plainUrl;
    }

   

    public String toBech32() {
        return bech32;
    }

    @Override
    public String toString(){
        return bech32;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LnUrl)) return false;
        LnUrl other = (LnUrl) obj;
        return bech32.equals(other.bech32);
    }

    @Override
    public int hashCode() {
        return bech32.hashCode();
    }


    public String getTag(){
        
        return tag;
    }
   

    public <T extends LnUrlService> AsyncTask<T>  getService() throws LnUrlException {
        return getService(DEFAULT_TIMEOUT);
    }
    public <T extends LnUrlService> AsyncTask<T> getService(Duration timeout) throws LnUrlException{
        return NGEPlatform.get().httpGet(toURI().toString(),timeout, null).then(res->{
            try{
                Map<String,Object> data = NGEPlatform.get().fromJSON(res, Map.class);
                if(LnUrlException.isAssignableTo(data)){
                    throw new LnUrlException(data);
                }
                LnUrlService service = null;
                if(LnUrlPayRequest.isAssignableTo(data)){
                    service = new LnUrlPayRequest(data);
                }  

                if(service==null){
                    throw new LnUrlException(LnUrlException.Status.NOT_FOUND, "No LNURL service found for: " + toURI().toString());
                }
                return (T)service;
            } catch(Exception e){
                throw new RuntimeException(new LnUrlException(LnUrlException.Status.INVALID, "Failed to parse LNURL service response: " + e.getMessage(), e));
            }
        });
    }
}
