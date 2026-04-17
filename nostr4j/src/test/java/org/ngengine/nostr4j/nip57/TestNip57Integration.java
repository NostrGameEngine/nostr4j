package org.ngengine.nostr4j.nip57;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;
import org.ngengine.lnurl.LnAddress;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.keypair.NostrPrivateKey;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.nostr4j.signer.NostrKeyPairSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.wallets.PayResponse;
import org.ngengine.wallets.nip47.NWCUri;
import org.ngengine.wallets.nip47.NWCWallet;


public class TestNip57Integration {

    private static final String RELAY = "wss://nostr.rblb.it";
    private static final String WALLET =
        "nostr+walletconnect://8e1e934ea0dd99cc2949805ed577abe76bb7d8c34d2d44a9e5f144a308b831f3?relay=wss://nostr.rblb.it&secret=e7ed7f71f3aebba15e125b9e19295951efd17e037f8ec95fe2afb9fd2b79ce57";

    private static final String LNADDRESS = "unit@lntest.rblb.it";

    @Test
    public void testZapPubkey() throws Exception {
        NostrPool pool = new NostrPool();
        pool.ensureRelay(RELAY);

        NostrPrivateKey p1 = NostrPrivateKey.generate();
        NostrPrivateKey p2 = NostrPrivateKey.generate();
        NostrKeyPairSigner s1 = new NostrKeyPairSigner(new NostrKeyPair(p1));
        NostrKeyPairSigner s2 = new NostrKeyPairSigner(new NostrKeyPair(p2));

        Nip01UserMetadata metadata = new Nip01UserMetadata();
        metadata.setName("Nostr4j Tester");
        metadata.setAbout("Testing NIP-57 end-to-end flow with NWC payment");
        metadata.setPaymentAddress(new LnAddress(LNADDRESS));
        SignedNostrEvent updateEvent = s2.sign(metadata.toUpdateEvent()).await();
        AsyncTask.all(pool.publish(updateEvent)).await();

        ZapInvoice invoice = AsyncTask
                .all(Nip57.getZapInvoices(pool, s1, null, p2.getPublicKey(), 2000, "Test Zap (nip57)")).await().get(0);
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET));
        String pq = invoice.getInvoice();
        PayResponse response = wallet.payInvoice(pq, invoice.getZapRequest().getAmountMsats()).await();
        String preimage = response.preimage();
        assertTrue(preimage != null);

        List<SignedNostrEvent> zaps = null;
        while (true) {
            zaps = Nip57.getZaps(pool, null, p2.getPublicKey(), null, null, null).await();
            if (zaps.size() > 0)
                break;
            Thread.sleep(2000);
        }

        ZapReceipt receipt = null;
        for (SignedNostrEvent zap : zaps) {
            try{
                receipt = Nip57.parseAndValidateZapReceipt(zap, invoice, null, null, null, null, null).await();
            } catch(Exception e){
                System.out.println("Failed to validate zap receipt: " + e.getMessage());
                throw e;
            }
        }
        assertNotNull(receipt);
    }


    @Test
    public void testZapEvent() throws Exception {
        NostrPool pool = new NostrPool();
        pool.ensureRelay(RELAY);

        NostrPrivateKey p1 = NostrPrivateKey.generate();
        NostrPrivateKey p2 = NostrPrivateKey.generate();
        NostrKeyPairSigner s1 = new NostrKeyPairSigner(new NostrKeyPair(p1));
        NostrKeyPairSigner s2 = new NostrKeyPairSigner(new NostrKeyPair(p2));

        Nip01UserMetadata metadata = new Nip01UserMetadata();
        metadata.setName("Nostr4j Tester");
        metadata.setAbout("Testing NIP-57 end-to-end flow with NWC payment");
        metadata.setPaymentAddress(new LnAddress(LNADDRESS));
        SignedNostrEvent updateEvent = s2.sign(metadata.toUpdateEvent()).await();
        AsyncTask.all(pool.publish(updateEvent)).await();

        ZapInvoice invoice = AsyncTask
                .all(Nip57.getZapInvoices(pool, s1, null, updateEvent, 2000, "Test Zap (nip57)")).await().get(0);
        NWCWallet wallet = new NWCWallet(new NWCUri(WALLET));
        String pq = invoice.getInvoice();
        PayResponse response = wallet.payInvoice(pq, invoice.getZapRequest().getAmountMsats()).await();
        String preimage = response.preimage();
        assertTrue(preimage != null);

        
        List<SignedNostrEvent> zaps = null;
        while (true) {
            zaps = Nip57.getZaps(pool, updateEvent, p2.getPublicKey(), null, null, null).await();
            if (zaps.size() > 0)
                break;
            Thread.sleep(2000);
        }

        ZapReceipt receipt = null;
        for (SignedNostrEvent zap : zaps) {
            try{
                receipt = Nip57.parseAndValidateZapReceipt(zap, invoice, null, null, null, null, null).await();
            } catch(Exception e){
                System.out.println("Failed to validate zap receipt: " + e.getMessage());
                throw e;
            }
        }
        assertNotNull(receipt);
    }
}