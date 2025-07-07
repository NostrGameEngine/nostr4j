package org.ngengine.nostrads.client.services;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostrads.client.negotiation.NegotiationHandler;
import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.VStore;

/**
 * PenaltyStorage is responsible for storing and retrieving POW penalties for Ad parties.
 * It uses a VStore to persist the penalties associated with each party's public key.
 */
public class PenaltyStorage {
    private static final Logger logger = Logger.getLogger(PenaltyStorage.class.getName());
    private final VStore store;

    public PenaltyStorage( VStore store ) {        
        this.store = store;
    }

    private String getPath(SignedNostrEvent event) {
        return "nostrads/powlist/" + event.getPubkey().asBech32() + ".dat";
    }

    public void set(NegotiationHandler neg){
        int v = neg.getCounterpartyPenalty();
        this.store.write(getPath(neg.getBidEvent())).then(os -> {
            try {
                os.write(new byte[] {
                        (byte) (v & 0xFF),
                        (byte) ((v >> 8) & 0xFF),
                        (byte) ((v >> 16) & 0xFF),
                        (byte) ((v >> 24) & 0xFF)
                });
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to store POW penalty", e);
                
            } finally{
                try {
                    os.close();
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to close output stream", e);
                }
            }
            return null;
        });
    }

    public AsyncTask<Integer> get(AdBidEvent ev){
        String  path = getPath(ev);
        String pubkey = ev.getPubkey().asBech32();
        return store.exists(path).compose(exists -> {
            if (!exists) {
                logger.fine("No POW penalty found for " + pubkey + ", returning default penalty of 0");
                return NGEPlatform.get().wrapPromise((res,rej)->{
                    res.accept(0); 
                });
            }
            return  store.read(path).then(is -> {
                byte[] data = new byte[4];
                try {
                    int read = is.read(data);
                    if (read == 4) {
                        int penalty = ((data[0] & 0xFF) | ((data[1] & 0xFF) << 8) | ((data[2] & 0xFF) << 16)
                                | ((data[3] & 0xFF) << 24));
                        logger.fine("Read POW penalty for " + pubkey + ": " + penalty);
                        return penalty;
                    } else {
                        logger.warning("Failed to read POW penalty for " + pubkey
                                + ", expected 4 bytes but got " + read);
                        return 0; // default penalty
                    }
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Failed to read POW penalty for " + pubkey, e);
                    return 0; // default penalty
                } finally {
                    try {
                        is.close();
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Failed to close input stream", e);
                    }
                }
            });
        });           
    }

}
