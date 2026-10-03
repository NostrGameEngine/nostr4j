package org.ngengine.nostr4j.nip57;

import static org.junit.Assert.*;

import org.junit.Test;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;

public class TestZapReceiptTrust {

    @Test
    public void arbitrarySignedReceiptCannotValidateWithoutTrustedProvider() throws Exception {
        var receipt = new NostrKeyPairSigner(new NostrKeyPair()).sign(new UnsignedNostrEvent().withKind(9735)).await();
        Exception failure = assertThrows(
            Exception.class,
            () -> Nip57.parseAndValidateZapReceipt(receipt, null, null, null, null, null, null).await()
        );
        assertTrue(
            failure.toString().contains("Trusted zap provider") ||
            (failure.getCause() != null && failure.getCause().toString().contains("Trusted zap provider"))
        );
    }
}
