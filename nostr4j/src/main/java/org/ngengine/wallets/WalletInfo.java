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

import java.util.List;
import java.util.Objects;

public class WalletInfo {

    private final String alias;
    private final String color;
    private final String pubkey;
    private final String network;
    private final int blockHeight;
    private final String blockHash;
    private final List<String> methods;
    private final List<String> notifications;

    public WalletInfo(
        String alias,
        String color,
        String pubkey,
        String network,
        int blockHeight,
        String blockHash,
        List<String> methods,
        List<String> notifications
    ) {
        this.alias = alias;
        this.color = color;
        this.pubkey = pubkey;
        this.network = network;
        this.blockHeight = blockHeight;
        this.blockHash = blockHash;
        this.methods = methods;
        this.notifications = notifications;
    }

    public String alias() {
        return alias;
    }

    public String color() {
        return color;
    }

    public String pubkey() {
        return pubkey;
    }

    public String network() {
        return network;
    }

    public int blockHeight() {
        return blockHeight;
    }

    public String blockHash() {
        return blockHash;
    }

    public List<String> methods() {
        return methods;
    }

    public List<String> notifications() {
        return notifications;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WalletInfo that = (WalletInfo) o;
        return (
            blockHeight == that.blockHeight &&
            Objects.equals(alias, that.alias) &&
            Objects.equals(color, that.color) &&
            Objects.equals(pubkey, that.pubkey) &&
            Objects.equals(network, that.network) &&
            Objects.equals(blockHash, that.blockHash) &&
            Objects.equals(methods, that.methods) &&
            Objects.equals(notifications, that.notifications)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, color, pubkey, network, blockHeight, blockHash, methods, notifications);
    }

    @Override
    public String toString() {
        return (
            "WalletInfo[" +
            "alias=" +
            alias +
            ", " +
            "color=" +
            color +
            ", " +
            "pubkey=" +
            pubkey +
            ", " +
            "network=" +
            network +
            ", " +
            "blockHeight=" +
            blockHeight +
            ", " +
            "blockHash=" +
            blockHash +
            ", " +
            "methods=" +
            methods +
            ", " +
            "notifications=" +
            notifications +
            ']'
        );
    }
}
