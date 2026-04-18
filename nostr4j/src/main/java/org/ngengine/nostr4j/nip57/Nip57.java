/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.ngengine.nostr4j.nip57;

import jakarta.annotation.Nullable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.ngengine.bech32.Bech32Exception;
import org.ngengine.bolt11.Bolt11;
import org.ngengine.bolt11.Bolt11Invoice;
import org.ngengine.bolt11.Bolt11TagName;
import org.ngengine.lnurl.LnUrl;
import org.ngengine.lnurl.LnUrlException;
import org.ngengine.lnurl.LnUrlPay;
import org.ngengine.lnurl.LnUrlPayerData;
import org.ngengine.nostr4j.NostrFilter;
import org.ngengine.nostr4j.NostrPool;
import org.ngengine.nostr4j.NostrRelay;
import org.ngengine.nostr4j.event.NostrEvent;
import org.ngengine.nostr4j.event.NostrEvent.TagValue;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrPublicKey;
import org.ngengine.nostr4j.nip01.Nip01;
import org.ngengine.nostr4j.nip01.Nip01UserMetadata;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrAllEOSEPoolFetchPolicy;
import org.ngengine.nostr4j.pool.fetchpolicy.NostrPoolFetchPolicy;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEPlatform;
import org.ngengine.platform.NGEUtils;

public final class Nip57 {

    public static AsyncTask<List<SignedNostrEvent>> getZaps(
        NostrPool pool,
        @Nullable SignedNostrEvent event,
        @Nullable NostrPublicKey recipient,
        @Nullable NostrPublicKey sender,
        @Nullable NostrPublicKey provider,
        @Nullable NostrPoolFetchPolicy fetchPolicy
    ) {
        NostrFilter filter = new NostrFilter();
        filter.withKind(9735);
        if (event != null) {
            NostrEvent.Coordinates coords = event.getCoordinates();
            if (coords != null) {
                filter.withTag(coords.type(), coords.coords());
            }
        }
        if (recipient != null) {
            filter.withTag("p", recipient.asHex());
        }
        if (provider != null) {
            filter.withAuthor(provider);
        }
        if (sender != null) {
            filter.withTag("P", sender.asHex());
        }

        return pool.fetch(List.of(filter), fetchPolicy == null ? new NostrAllEOSEPoolFetchPolicy() : fetchPolicy);
    }

    /**
     * This method parses and validates a zap receipt at various different levels.
     *
     * <p>
     * The validation takes into account all the arguments passed to this method,
     * only the zapReceiptEvent argument is required, all the other ones are optional and are used for additional validation.
     *
     * This lets many different callers that have access to different levels of information about the zap request and receipt
     * to use this same method for validation, passing the arguments they have available and leaving the others as null.
     *
     *
     * @param zapReceiptEvent the zap receipt event to parse and validate. This event must be already signed and the signature is verified in this method as part of the validation process.
     * @param expectedInvoice  if the caller has access to the original zap invoice that was paid, it can be passed in this argument for additional validation.
     * @param expectedProviderPublickey if the caller has access to the expected provider public key, it can be passed in this argument for additional validation.
     * @param expectedZapRequestEvent if the caller has access to the original zap request event, it can be passed in this argument for additional validation.
     * @param expectedZapPreimage if the caller has access to the expected preimage of the zap, it can be passed in this argument for additional validation.
     * @param expectedLnUrl if the caller has access to the expected LNURL of the zap recipient, it can be passed in this argument for additional validation.
     * @param expectedSender if the caller has access to the expected sender public key of the zap, it can be passed in this argument for additional validation.
     * @return
     */
    public static AsyncTask<ZapReceipt> parseAndValidateZapReceipt(
        SignedNostrEvent zapReceiptEvent,
        @Nullable ZapInvoice expectedInvoice,
        @Nullable NostrPublicKey expectedProviderPublickey,
        @Nullable SignedNostrEvent expectedZapRequestEvent,
        @Nullable String expectedZapPreimage,
        @Nullable LnUrl expectedLnUrl,
        @Nullable NostrPublicKey expectedSender
    ) {
        Objects.requireNonNull(zapReceiptEvent, "zapReceiptEvent");
        return zapReceiptEvent
            .verifyAsync()
            .then(valid -> {
                if (!valid) throw new InvalidZapException("Zap receipt event signature is invalid");
                return _parseAndValidateZapReceipt(
                    expectedInvoice,
                    expectedProviderPublickey,
                    expectedZapRequestEvent,
                    expectedZapPreimage,
                    expectedLnUrl,
                    expectedSender,
                    zapReceiptEvent
                );
            });
    }

    private static ZapReceipt _parseAndValidateZapReceipt(
        @Nullable ZapInvoice expectedInvoice,
        @Nullable NostrPublicKey expectedProviderPublickey,
        @Nullable SignedNostrEvent expectedZapRequestEvent,
        @Nullable String expectedZapPreimage,
        @Nullable LnUrl expectedLnUrl,
        @Nullable NostrPublicKey expectedSender,
        SignedNostrEvent zapReceiptEvent
    ) throws InvalidZapException, Bech32Exception, URISyntaxException {
        Objects.requireNonNull(zapReceiptEvent, "zapReceiptEvent");
        if (zapReceiptEvent.getKind() != 9735) {
            throw new InvalidZapException("Zap receipt event must have kind 9735");
        }

        // CORE VALIDATION
        // validate provider
        //     if unspecified, try to retrieve it from the invoice object (if provided)
        if (expectedProviderPublickey == null && expectedInvoice != null) expectedProviderPublickey =
            expectedInvoice.getProviderPubkey();
        if (expectedProviderPublickey != null && !expectedProviderPublickey.equals(zapReceiptEvent.getPubkey())) {
            throw new InvalidZapException("Receipt pubkey does not match invoice provider pubkey");
        }

        // validate zap request
        String zapRequestRaw = zapReceiptEvent.getFirstTagFirstValue("description");
        if (zapRequestRaw == null || zapRequestRaw.isEmpty()) throw new InvalidZapException(
            "A description tag containing zap request is required in zap receipt"
        );
        String descriptionHash = NGEPlatform.get().sha256(zapRequestRaw);
        @SuppressWarnings("unchecked")
        Map<String, Object> zapRequestMap = (Map<String, Object>) NGEPlatform.get().fromJSON(zapRequestRaw, Map.class);
        ZapRequest zapRequestEvent = new ZapRequest(zapRequestMap);
        if (zapRequestEvent.getKind() != 9734) throw new InvalidZapException("Zap request in receipt must have kind 9734");
        try {
            if (!zapRequestEvent.verify()) throw new InvalidZapException("Zap request in receipt has invalid signature");
        } catch (Exception e) {
            throw new InvalidZapException("Failed to verify zap request signature: " + e.getMessage());
        }
        if (zapRequestEvent.getTagRows().isEmpty()) throw new InvalidZapException("Zap request in receipt must have tags");

        List<TagValue> pTags = zapRequestEvent.getTag("p");
        if (pTags == null || pTags.size() != 1) throw new InvalidZapException("Zap request must have exactly one p tag");
        List<TagValue> eTags = zapRequestEvent.getTag("e");
        if (eTags != null && eTags.size() > 1) throw new InvalidZapException("Zap request must have at most one e tag");
        List<TagValue> aTags = zapRequestEvent.getTag("a");
        if (aTags != null && aTags.size() > 1) throw new InvalidZapException("Zap request must have at most one a tag");

        List<TagValue> providerTags = zapRequestEvent.getTag("P");
        if (providerTags != null && providerTags.size() > 1) throw new InvalidZapException(
            "Zap request must have at most one P tag"
        );

        List<TagValue> relaysTag = zapRequestEvent.getTag("relays");
        if (relaysTag == null || relaysTag.isEmpty() || relaysTag.get(0).size() == 0) throw new InvalidZapException(
            "Zap request must include a non-empty relays tag"
        );

        String requestProviderTag = zapRequestEvent.getFirstTagFirstValue("P");
        if (requestProviderTag != null && !requestProviderTag.equals(zapReceiptEvent.getPubkey().asHex())) {
            throw new InvalidZapException("Zap request P tag must match zap receipt pubkey");
        }

        //     if unspecified, try to retrieve it from the invoice object (if provided)
        if (expectedZapRequestEvent == null && expectedInvoice != null) expectedZapRequestEvent =
            expectedInvoice.getZapRequest();
        if (expectedZapRequestEvent != null && !expectedZapRequestEvent.equals(zapRequestEvent)) throw new InvalidZapException(
            "Zap Request in receipt does not match provided zap request"
        );

        // validate invoice
        String bolt11Raw = zapReceiptEvent.getFirstTagFirstValue("bolt11");
        if (
            bolt11Raw == null ||
            bolt11Raw.isEmpty() ||
            (expectedInvoice != null && !bolt11Raw.equals(expectedInvoice.getInvoice()))
        ) throw new InvalidZapException("Bolt11 invoice does not match");
        Bolt11Invoice bolt11;
        try {
            bolt11 = Bolt11.decode(bolt11Raw);
        } catch (LinkageError e) {
            throw new InvalidZapException(
                "Bolt11 runtime dependency issue while validating zap receipt: " + e.getClass().getSimpleName()
            );
        } catch (RuntimeException e) {
            throw new InvalidZapException("Failed to decode bolt11 invoice in zap receipt");
        }
        if (zapRequestEvent.hasTag("amount")) {
            try {
                long amountInRequest = NGEUtils.safeMSats(zapRequestEvent.getFirstTagFirstValue("amount"));
                if (bolt11.getMillisatoshis() == null || NGEUtils.safeMSats(bolt11.getMillisatoshis()) != amountInRequest) {
                    throw new InvalidZapException("Zap receipt invoice amount does not match zap request amount");
                }
            } catch (LinkageError e) {
                throw new InvalidZapException(
                    "Bolt11 runtime dependency issue while validating receipt amount: " + e.getClass().getSimpleName()
                );
            }
        }

        // validate lnurl
        String lnurlInRequest = zapRequestEvent.getFirstTagFirstValue("lnurl");
        if (expectedLnUrl != null && lnurlInRequest != null && !lnurlInRequest.equals(expectedLnUrl.toBech32())) {
            throw new InvalidZapException("Zap request LNURL does not match expected recipient LNURL");
        }

        // ADDITIONAL VALIDATION
        // validate preimage (optional)
        String preimage = zapReceiptEvent.getFirstTagFirstValue("preimage"); // optional
        if (
            preimage != null && expectedZapPreimage != null && !preimage.equals(expectedZapPreimage)
        ) throw new InvalidZapException("Preimage does not match");

        // validate description hash
        String bolt11DescriptionHash;
        try {
            bolt11DescriptionHash = bolt11.getTag(Bolt11TagName.DESCRIPTION_HASH).getValueAsString();
        } catch (LinkageError e) {
            throw new InvalidZapException(
                "Bolt11 runtime dependency issue while validating receipt description hash: " + e.getClass().getSimpleName()
            );
        }
        if (
            bolt11DescriptionHash == null || bolt11DescriptionHash.isEmpty() || !descriptionHash.equals(bolt11DescriptionHash)
        ) {
            throw new InvalidZapException("Description hash does not match");
        }

        // validate p (recipient pubkey)
        NostrPublicKey recipient = NostrPublicKey.fromHex(zapReceiptEvent.getFirstTagFirstValue("p"));
        NostrPublicKey expectedRecipient = NostrPublicKey.fromHex(zapRequestEvent.getFirstTagFirstValue("p"));
        if (!recipient.equals(expectedRecipient)) throw new InvalidZapException("Recipient pubkey does not match");

        // validate e, a and k tags (optional, but if present must match between request and receipt)
        String reTag = zapRequestEvent.getFirstTagFirstValue("e"); // optional
        String rkTag = zapRequestEvent.getFirstTagFirstValue("k"); // optional
        String raTag = zapRequestEvent.getFirstTagFirstValue("a"); // optional

        String eTag = zapReceiptEvent.getFirstTagFirstValue("e"); // optional
        String kTag = zapReceiptEvent.getFirstTagFirstValue("k"); // optional
        String aTag = zapReceiptEvent.getFirstTagFirstValue("a"); // optional

        if (!Objects.equals(reTag, eTag)) throw new InvalidZapException("Event ID tag does not match");
        if (rkTag != null && kTag != null && !Objects.equals(rkTag, kTag)) throw new InvalidZapException(
            "Event kind tag does not match"
        );
        if (!Objects.equals(raTag, aTag)) throw new InvalidZapException("Event author tag does not match");

        // validate P (sender pubkey, optional)
        NostrPublicKey sender = zapReceiptEvent.hasTag("P")
            ? NostrPublicKey.fromHex(zapReceiptEvent.getFirstTagFirstValue("P"))
            : null; // optional
        NostrPublicKey requestSender = zapRequestEvent.getPubkey();
        if (expectedSender != null && !requestSender.equals(expectedSender)) throw new InvalidZapException(
            "Zap request sender pubkey does not match expected sender pubkey"
        );
        if (sender != null && !sender.equals(requestSender)) throw new InvalidZapException(
            "Sender pubkey does not match zap request sender"
        );

        return new ZapReceipt(zapReceiptEvent.toMap());
    }

    /**
     * Get invoice to zap a Nostr event.
     *
     * <p>
     * The relays attached to the pool passed to this method are used to fetch recipient metadata.
     * They are also sent to the LNURL-Pay service so it can publish the zap receipt to those relays.
     *
     * @param pool the NostrPool to use for fetching recipient metadata and publishing zap receipt
     * @param payer the signer to use for signing the zap request event
     * @param payerMetadata optional metadata of the payer to fill payer data fields in LNURL-Pay request
     * @param eventToZap  the event to zap. The method will look for "zap" tags in the event to determine zap targets. If no "zap" tags are found, the method will default to zapping the event's author with a single zap of the full amount.
     * @param amountInMsats the amount to zap in millisatoshis
     * @param comment optional comment to include in the zap request
     * @return a list of asynchronous tasks that will:
     *  - resolve to zap invoices, or
     *  - resolve to one or more null values if the recipient(s) do not have a payment address in their metadata, or
     *  - reject if there is an error during the process
     * @throws MalformedZapTargetException if any of the zap targets in the event is malformed (e.g. missing required fields in the "zap" tag)
     */
    public static List<AsyncTask<ZapInvoice>> getZapInvoices(
        NostrPool pool,
        NostrSigner payer,
        @Nullable Nip01UserMetadata payerMetadata,
        SignedNostrEvent eventToZap,
        long amountInMsats,
        String comment
    ) throws MalformedZapTargetException {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(payer, "payer");
        Objects.requireNonNull(eventToZap, "eventToZap");
        long amountMsats = NGEUtils.safeMSats(amountInMsats);
        List<TagValue> zapTargets = eventToZap.getTag("zap");
        if (zapTargets == null || zapTargets.isEmpty()) {
            if (pool.getRelays().isEmpty()) {
                throw new MalformedZapTargetException(
                    "At least one relay is required in pool to zap an event without zap tags"
                );
            }
            zapTargets = new ArrayList<>();
            zapTargets.add(new TagValue(eventToZap.getPubkey().asHex(), pool.getRelays().get(0).getUrl()));
        }

        for (TagValue target : zapTargets) {
            if (target.size() < 2) {
                throw new MalformedZapTargetException("Each zap target must include recipient pubkey and relay URL");
            }
        }

        boolean isWeightedZap = false;
        float weightSum = 0;
        for (TagValue target : zapTargets) {
            if (target.size() >= 3) {
                weightSum += NGEUtils.safeDouble(target.get(2));
                isWeightedZap = true;
            }
        }
        long amounts[] = new long[zapTargets.size()];
        if (!isWeightedZap) {
            for (int i = 0; i < zapTargets.size(); i++) {
                amounts[i] = NGEUtils.safeMSats(amountMsats / zapTargets.size());
            }
        } else {
            if (weightSum <= 0) {
                throw new MalformedZapTargetException("Sum of zap weights must be greater than 0");
            }
            for (int i = 0; i < zapTargets.size(); i++) {
                TagValue v = zapTargets.get(i);
                if (v.size() < 3) {
                    amounts[i] = NGEUtils.safeMSats(0);
                } else {
                    double weight = NGEUtils.safeDouble(v.get(2));
                    amounts[i] = NGEUtils.safeMSats((weight / weightSum) * amountMsats);
                }
            }
        }

        long totalAmount = 0;
        for (long a : amounts) totalAmount += a;
        if (totalAmount > amountMsats) throw new MalformedZapTargetException(
            "Calculated total zap amount exceeds specified amount"
        );
        if (totalAmount <= 0) throw new MalformedZapTargetException("Calculated total zap amount must be greater than 0");

        Set<String> baseRelays = new LinkedHashSet<>();
        for (NostrRelay relay : pool.getRelays()) {
            baseRelays.add(relay.getUrl());
        }

        List<AsyncTask<ZapInvoice>> invoiceTasks = new ArrayList<>();
        for (int ic = 0; ic < zapTargets.size(); ic++) {
            final int i = ic;
            List<TagValue> targets = zapTargets;
            invoiceTasks.add(
                AsyncTask.create((res, rej) -> {
                    try {
                        long amount = NGEUtils.safeMSats(amounts[i]);
                        NostrPublicKey recipientPubkey = NostrPublicKey.fromHex(targets.get(i).get(0));

                        String relayUrl = targets.get(i).get(1);
                        Set<String> relaysForResponse = new LinkedHashSet<>();
                        relaysForResponse.add(relayUrl);
                        relaysForResponse.addAll(baseRelays);

                        pool
                            .ensureRelay(relayUrl)
                            .compose(r -> {
                                NostrPool subPool = new NostrPool();
                                subPool.addRelay(r);
                                return Nip01
                                    .fetch(subPool, recipientPubkey)
                                    .then(meta -> {
                                        subPool.clean();
                                        return meta;
                                    });
                            })
                            .compose(meta -> {
                                if (meta == null || meta.getPaymentAddress() == null) {
                                    return AsyncTask.completed(null);
                                }
                                return getZapInvoices(
                                    payer,
                                    payerMetadata,
                                    meta.getPaymentAddress(),
                                    amount,
                                    comment,
                                    relaysForResponse,
                                    recipientPubkey,
                                    eventToZap.getCoordinates()
                                )
                                    .get(0);
                            })
                            .then(invoice -> {
                                res.accept(invoice);
                                return null;
                            })
                            .catchException(e -> {
                                rej.accept(e);
                            });
                    } catch (Exception e) {
                        rej.accept(e);
                    }
                })
            );
        }
        return invoiceTasks;
    }

    /**
     * Get an invoice to zap a public key
     *
     * <p>
     * The relays attached to the pool passed to this method are used to fetch recipient metadata.
     * They are also sent to the LNURL-Pay service so it can publish the zap receipt to those relays.
     *
     * @param pool  the NostrPool to use for fetching recipient metadata and to request zap receipt publication
     * @param payer the signer of the payer of the zap
     * @param payerMetadata optional metadata of the payer to fill payer data fields in LNURL-Pay request
     * @param recipientPubkey the public key of the recipient
     * @param amountInMsats amount to zap in millisatoshis
     * @param comment comment to include in the zap request event content
     * @return a list of asynchronous tasks that will:
     *  - resolve to zap invoices, or
     *  - resolve to one or more null values if the recipient does not have a payment address in their metadata, or
     *  - reject if there is an error during the process
     */
    public static List<AsyncTask<ZapInvoice>> getZapInvoices(
        NostrPool pool,
        NostrSigner payer,
        @Nullable Nip01UserMetadata payerMetadata,
        NostrPublicKey recipientPubkey,
        long amountInMsats,
        String comment
    ) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(payer, "payer");
        Objects.requireNonNull(recipientPubkey, "recipientPubkey");
        long amountMsats = NGEUtils.safeMSats(amountInMsats);
        Set<String> baseRelays = new LinkedHashSet<>();
        for (NostrRelay relay : pool.getRelays()) {
            baseRelays.add(relay.getUrl());
        }
        AsyncTask<ZapInvoice> zap = Nip01
            .fetch(pool, recipientPubkey)
            .compose(meta -> {
                if (meta == null || meta.getPaymentAddress() == null) {
                    return AsyncTask.completed(null);
                }
                return getZapInvoices(
                    payer,
                    payerMetadata,
                    meta.getPaymentAddress(),
                    amountMsats,
                    comment,
                    baseRelays,
                    recipientPubkey,
                    null
                )
                    .get(0);
            });

        return List.of(zap);
    }

    /**
     * Raw method to get a zap invoice for something.
     *
     * <p>
     * This method gives more control to the called but usually it is advised to
     * use the other higher level {@link #getZapInvoices(NostrPool, NostrSigner, Nip01UserMetadata, SignedNostrEvent, long, String)} or {@link #getZapInvoices(NostrPool, NostrSigner, Nip01UserMetadata, NostrPublicKey, long, String)} methods instead
     * which will handle most of the logic of determining zap targets and relays.
     *
     * <p>
     * The amount of the returned invoice is verified to be the same
     * as the amount passed in the arguments
     *
     * @param payer The payer signer
     * @param payerMetadata optional payer metadata to fill payer data fields in LNURL-Pay request
     * @param lnurlOrAddress LNURL or Lightning Address of the recipient
     * @param amountInMsats amount to zap in millisatoshis
     * @param comment comment to include in the zap request event content
     * @param relays set of relays to which the zap receipt should be published
     * @param recipientPubkey the public key of the recipient
     * @param eventCoordinates optional coordinates of the event being zapped
     * @return a list of asynchronous tasks that will complete with the zap invoices
     * @throws LnUrlException if there is an error with the LNURL-Pay request
     */
    public static List<AsyncTask<ZapInvoice>> getZapInvoices(
        NostrSigner payer,
        @Nullable Nip01UserMetadata payerMetadata,
        LnUrl lnurlOrAddress,
        long amountInMsats,
        String comment,
        Set<String> relays,
        NostrPublicKey recipientPubkey,
        @Nullable NostrEvent.Coordinates eventCoordinates
    ) throws LnUrlException {
        Objects.requireNonNull(payer, "payer");
        Objects.requireNonNull(lnurlOrAddress, "lnurlOrAddress");
        Objects.requireNonNull(relays, "relays");
        Objects.requireNonNull(recipientPubkey, "recipientPubkey");
        long amountMsats = NGEUtils.safeMSats(amountInMsats);
        if (relays.isEmpty()) {
            throw new IllegalArgumentException("relays must not be empty");
        }
        String zapComment = comment == null ? "" : comment;

        ArrayList<String> relayList = new ArrayList<>();
        for (String relay : relays) {
            if (relay != null && !relay.isEmpty()) {
                relayList.add(relay);
            }
        }
        if (relayList.isEmpty()) {
            throw new IllegalArgumentException("relays must contain at least one non-empty relay URL");
        }

        AsyncTask<LnUrlPay> paySv = lnurlOrAddress.getService();
        return List.of(
            payer
                .getPublicKey()
                .compose(pkey -> {
                    return paySv.compose(pay -> {
                        String nostrPubkeyRaw = pay.getNostrPubkey();
                        if (nostrPubkeyRaw == null || nostrPubkeyRaw.isEmpty()) {
                            throw new MalformedZapTargetException("LNURL-Pay service does not provide a nostrPubkey");
                        }
                        // Some LNURL providers may expose nostrPubkey but serialize allowsNostr in a non-standard way.
                        boolean isNostrAllowed = pay.isNostrAllowed() || !nostrPubkeyRaw.isEmpty();
                        NostrPublicKey providerPubkey = NostrPublicKey.fromHex(nostrPubkeyRaw);
                        if (!isNostrAllowed) {
                            throw new MalformedZapTargetException("LNURL-Pay service does not allow Nostr zap requests");
                        }

                        LnUrlPayerData payerData = pay.getPayerData();
                        if (payerData != null) {
                            payerData.setPubkey(pkey.asHex());
                            if (payerMetadata != null) {
                                payerData.setName(payerMetadata.getDisplayName());
                            }
                        }

                        UnsignedNostrEvent zapRequestEvent = new UnsignedNostrEvent();
                        zapRequestEvent.withKind(9734);
                        zapRequestEvent.withContent(zapComment);
                        zapRequestEvent.withTag("relays", relayList.toArray(new String[0]));
                        zapRequestEvent.withTag("amount", String.valueOf(amountMsats));
                        zapRequestEvent.withTag("lnurl", lnurlOrAddress.toBech32());
                        zapRequestEvent.withTag("p", recipientPubkey.asHex());

                        if (eventCoordinates != null) {
                            zapRequestEvent.withTag(eventCoordinates.type(), eventCoordinates.coords());
                            zapRequestEvent.withTag("k", eventCoordinates.kind());
                        }

                        return payer
                            .sign(zapRequestEvent)
                            .compose(signedZapRequest -> {
                                Map<String, Object> signedRaw = signedZapRequest.toMap();
                                String signedJson = NGEPlatform.get().toJSON(signedRaw);
                                return pay
                                    .fetchInvoice(amountMsats, zapComment, payerData, signedJson)
                                    .then(invoice -> {
                                        // Keep strict invoice amount validation when bolt11 decoding is available,
                                        // but do not fail the whole zap flow on runtime linkage issues in optional deps.
                                        try {
                                            Bolt11Invoice bolt11 = Bolt11.decode(invoice.getPr());
                                            if (
                                                bolt11.getMillisatoshis() == null ||
                                                NGEUtils.safeMSats(bolt11.getMillisatoshis()) != amountMsats
                                            ) {
                                                throw new MalformedZapTargetException(
                                                    "LNURL-Pay service returned an invoice with unexpected amount"
                                                );
                                            }
                                        } catch (NoClassDefFoundError ignored) {
                                            // Ignore optional runtime linkage issues and continue with best-effort validation.
                                        }
                                        return new ZapInvoice(invoice.getPr(), zapComment, signedZapRequest, providerPubkey);
                                    });
                            });
                    });
                })
        );
    }
}
