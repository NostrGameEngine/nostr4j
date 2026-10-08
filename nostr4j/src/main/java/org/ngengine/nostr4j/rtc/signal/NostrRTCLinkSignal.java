/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 */
package org.ngengine.nostr4j.rtc.signal;

import java.time.Instant;
import org.ngengine.nostr4j.event.SignedNostrEvent;
import org.ngengine.nostr4j.event.UnsignedNostrEvent;
import org.ngengine.nostr4j.keypair.NostrKeyPair;
import org.ngengine.nostr4j.signer.NostrSigner;
import org.ngengine.platform.AsyncTask;
import org.ngengine.platform.NGEUtils;

/** Small authenticated and encrypted reservation request/response, negotiated by presence. */
public final class NostrRTCLinkSignal extends NostrRTCSignal {

    public enum Command {
        REQUEST,
        TURN_REQUEST,
        ACCEPT,
        TURN_ACCEPT,
        BUSY,
        ABORT,
    }

    private final AsyncTask<String> command;

    public NostrRTCLinkSignal(
        NostrSigner signer,
        NostrKeyPair roomKeys,
        NostrRTCPeer local,
        Command command,
        String attempt,
        String targetSession
    ) {
        super(signer, "link", roomKeys, local);
        withLinkAttempt(attempt, targetSession);
        this.command = AsyncTask.completed(command.name());
    }

    public NostrRTCLinkSignal(NostrSigner signer, NostrKeyPair roomKeys, SignedNostrEvent event) {
        super(signer, "link", roomKeys, event);
        if (getLinkAttemptId() == null || event.getContent().length() > 2048) {
            throw new IllegalArgumentException("Invalid link admission signal");
        }
        command = decrypt(event.getContent(), event.getPubkey());
    }

    public Command getCommand() {
        return Command.valueOf(NGEUtils.awaitNoThrow(command));
    }

    @Override
    public void await() {
        getCommand();
    }

    @Override
    protected AsyncTask<UnsignedNostrEvent> computeEvent(UnsignedNostrEvent event) {
        event.withContent(getCommand().name());
        event.withTag("expiration", String.valueOf(Instant.now().plusSeconds(120).getEpochSecond()));
        return AsyncTask.completed(event);
    }

    @Override
    protected boolean requireRoomSignature() {
        return true;
    }
}
