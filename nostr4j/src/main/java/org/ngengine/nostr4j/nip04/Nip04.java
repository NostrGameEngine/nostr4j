package org.ngengine.nostr4j.nip04;

import java.nio.charset.StandardCharsets;

import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.platform.AsyncExecutor;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public class Nip04 {
    private static final AsyncExecutor executor = NGEUtils.getPlatform().newAsyncExecutor(Nip04.class);

    public static String encryptSync(String plaintext, NostrPrivateKey ourPrivateKey , NostrPublicKey theirPublicKey) {
        byte pub[] = concatBytes(0x02, theirPublicKey._array());
        byte[] shared = NGEPlatform.get().secp256k1SharedSecret(ourPrivateKey._array(), pub);

        byte[] sharedX = new byte[32];
        System.arraycopy(shared, 1, sharedX, 0, 32);

        byte[] iv = NGEPlatform.get().randomBytes(16);
        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = NGEPlatform.get().aes256cbc(sharedX, iv, data, true);
        String b64ciphertext = NGEPlatform.get().base64encode(ciphertext);
        String b64iv = NGEPlatform.get().base64encode(iv);
        return b64ciphertext+"?iv="+b64iv;
       
    }
    
    public static AsyncTask<String> encrypt(String plaintext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        return executor.run(() -> {
            return encryptSync(plaintext, ourPrivateKey, theirPublicKey);
        });
    }

    private static byte[] concatBytes(int prefix, byte[] array) {
        byte[] result = new byte[array.length + 1];
        result[0] = (byte) prefix;
        System.arraycopy(array, 0, result, 1, array.length);
        return result;
    }

    public static String decryptSync(String ciphertext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        String[] parts = ciphertext.split("\\?iv=");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid ciphertext format");
        }
        byte[] iv = NGEPlatform.get().base64decode(parts[1]);
        byte[] data = NGEPlatform.get().base64decode(parts[0]);
        byte pub[] = concatBytes(0x02, theirPublicKey._array());
        byte[] shared = NGEPlatform.get().secp256k1SharedSecret(ourPrivateKey._array(), pub);

        byte[] sharedX = new byte[32];
        System.arraycopy(shared, 1, sharedX, 0, 32);

        byte[] plaintext = NGEPlatform.get().aes256cbc(sharedX, iv, data, false);
        return new String(plaintext, StandardCharsets.UTF_8);
    }


    public static AsyncTask<String> decrypt(String ciphertext, NostrPrivateKey ourPrivateKey, NostrPublicKey theirPublicKey) {
        return executor.run(() -> {
            return decryptSync(ciphertext, ourPrivateKey, theirPublicKey);
        });
    }
}
