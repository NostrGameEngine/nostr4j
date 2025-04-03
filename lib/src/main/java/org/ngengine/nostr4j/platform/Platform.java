package org.ngengine.nostr4j.platform;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.transport.NostrTransport;

public interface Platform {
    public byte[] generatePrivateKey() throws Exception;
    public byte[] genPubKey(byte[] secKey) throws Exception ;
    public String toJSON(Object obj) throws Exception;
    public <T> T fromJSON(String json, Class<T> claz) throws Exception ;

    public byte[] secp256k1SharedSecret(byte[] privKey, byte[] pubKey) throws Exception;
    public byte[] hmac(byte[] key, byte[] data1, byte[] data2) throws Exception;
    public byte[] hkdf_extract(byte[] salt, byte[] ikm) throws Exception;
    public byte[] hkdf_expand(byte[] prk, byte[] info, int length) throws Exception;
    public String base64encode(byte[] data) throws Exception;
    public byte[] base64decode(String data) throws Exception;
    public byte[] chacha20(byte[] key, byte[] nonce, byte[] data, boolean forEncryption) throws Exception;

    public NostrTransport newTransport();

    public String sha256(String data) throws NoSuchAlgorithmException;
    public byte[] sha256(byte[] data) throws NoSuchAlgorithmException;
    public String sign(String data, NostrPrivateKey privKey) throws Exception;
    public boolean verify( String data, String sign, NostrPublicKey pubKey) throws Exception;

    public byte[] randomBytes(int n) ;

    public NostrExecutor newRelayExecutor();
    
    public NostrExecutor newPoolExecutor();
    public NostrExecutor newSubscriptionExecutor();

    public <T> AsyncTask<T> promisify(BiConsumer<Consumer<T>, Consumer<Throwable>> func);

    public <T> AsyncTask<List<T>> waitAll(List< AsyncTask<T>> promises);

    public long getTimestampSeconds();

 }
