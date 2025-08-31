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

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;

public class InvoiceProperties {
    private final long amountMsats;
    private final @Nullable String description;
    private final @Nullable String descriptionHash;
    private final @Nullable Duration expiry;

    public InvoiceProperties(
            long amountMsats,
            @Nullable String description,
            @Nullable String descriptionHash,
            @Nullable Duration expiry) {
        this.amountMsats = amountMsats;
        this.description = description;
        this.descriptionHash = descriptionHash;
        this.expiry = expiry;
    }

    public long amountMsats() {
        return amountMsats;
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
    public Duration expiry() {
        return expiry;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        InvoiceProperties that = (InvoiceProperties) o;
        return amountMsats == that.amountMsats &&
                Objects.equals(description, that.description) &&
                Objects.equals(descriptionHash, that.descriptionHash) &&
                Objects.equals(expiry, that.expiry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMsats, description, descriptionHash, expiry);
    }

    @Override
    public String toString() {
        return "InvoiceProperties[" +
                "amountMsats=" + amountMsats + ", " +
                "description=" + description + ", " +
                "descriptionHash=" + descriptionHash + ", " +
                "expiry=" + expiry + ']';
    }
}