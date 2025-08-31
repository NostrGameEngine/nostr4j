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

package org.ngengine.wallets;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public class TransactionInfo {
    private final @Nonnull TransactionType type;
    private final @Nullable String invoice;
    private final @Nullable String description;
    private final @Nullable String descriptionHash;
    private final @Nullable String preimage;
    private final @Nonnull String paymentHash;
    private final long amountMsats;
    private final long feesPaid;
    private final @Nonnull Instant createdAt;
    private final @Nullable Instant expiresAt;
    private final @Nullable Instant settledAt;
    private final @Nullable Map<String, Object> metadata;

    public TransactionInfo(
            @Nonnull TransactionType type,
            @Nullable String invoice,
            @Nullable String description,
            @Nullable String descriptionHash,
            @Nullable String preimage,
            @Nonnull String paymentHash,
            long amountMsats,
            long feesPaid,
            @Nonnull Instant createdAt,
            @Nullable Instant expiresAt,
            @Nullable Instant settledAt,
            @Nullable Map<String, Object> metadata) {
        this.type = type;
        this.invoice = invoice;
        this.description = description;
        this.descriptionHash = descriptionHash;
        this.preimage = preimage;
        this.paymentHash = paymentHash;
        this.amountMsats = amountMsats;
        this.feesPaid = feesPaid;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.settledAt = settledAt;
        this.metadata = metadata;
    }

    @Nonnull
    public TransactionType type() {
        return type;
    }

    @Nullable
    public String invoice() {
        return invoice;
    }

    @Nullable
    public String description() {
        return description;
    }

    @Nullable
    public String descriptionHash() {
        return descriptionHash;
    }

    @Nullable
    public String preimage() {
        return preimage;
    }

    @Nonnull
    public String paymentHash() {
        return paymentHash;
    }

    public long amountMsats() {
        return amountMsats;
    }

    public long feesPaid() {
        return feesPaid;
    }

    @Nonnull
    public Instant createdAt() {
        return createdAt;
    }

    @Nullable
    public Instant expiresAt() {
        return expiresAt;
    }

    @Nullable
    public Instant settledAt() {
        return settledAt;
    }

    @Nullable
    public Map<String, Object> metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        TransactionInfo that = (TransactionInfo) o;
        return amountMsats == that.amountMsats &&
                feesPaid == that.feesPaid &&
                Objects.equals(type, that.type) &&
                Objects.equals(invoice, that.invoice) &&
                Objects.equals(description, that.description) &&
                Objects.equals(descriptionHash, that.descriptionHash) &&
                Objects.equals(preimage, that.preimage) &&
                Objects.equals(paymentHash, that.paymentHash) &&
                Objects.equals(createdAt, that.createdAt) &&
                Objects.equals(expiresAt, that.expiresAt) &&
                Objects.equals(settledAt, that.settledAt) &&
                Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, invoice, description, descriptionHash, preimage, paymentHash,
                amountMsats, feesPaid, createdAt, expiresAt, settledAt, metadata);
    }

    @Override
    public String toString() {
        return "TransactionInfo[" +
                "type=" + type + ", " +
                "invoice=" + invoice + ", " +
                "description=" + description + ", " +
                "descriptionHash=" + descriptionHash + ", " +
                "preimage=" + preimage + ", " +
                "paymentHash=" + paymentHash + ", " +
                "amountMsats=" + amountMsats + ", " +
                "feesPaid=" + feesPaid + ", " +
                "createdAt=" + createdAt + ", " +
                "expiresAt=" + expiresAt + ", " +
                "settledAt=" + settledAt + ", " +
                "metadata=" + metadata + ']';
    }
}