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

package org.ngengine.site.demos.relay;

/**
 * Fixed allowlist of reputable, well-known Nostr authors whose public notes
 * the relay-fetch demo is allowed to show.
 *
 * <p>This is the demo's content-safety mechanism: the demo NEVER fetches notes
 * from arbitrary authors, only from the hex pubkeys listed here. Hex values
 * were decoded from the authors' published npubs (bech32 checksum verified).
 */
public final class WellKnownAuthors {

    public static final class Author {

        public final String name;
        public final String hex;

        public Author(String name, String hex) {
            this.name = name;
            this.hex = hex;
        }
    }

    public static final Author[] ALL = {
        // jack (Jack Dorsey) — npub from his own Dec 2022 verification tweet
        new Author("jack", "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2"),
        // fiatjaf (Nostr protocol creator) — npub from github.com/fiatjaf profile
        new Author("fiatjaf", "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"),
        // NVK (Coinkite/Coldcard founder)
        new Author("NVK", "e88a691e98d9987c964521dff60025f60700378a4879180dcbbb4a5027850411"),
        // calle (Cashu) — NIP-05 calle@cashu.me
        new Author("calle", "50d94fc2d8580c682b071a542f8b1e31a200b0508bab95a33bef0855df281d63"),
        // hodlbod (Coracle) — NIP-05 hodlbod@coracle.social
        new Author("hodlbod", "97c70a44366a6535c145b333f973ea86dfdc2d7a99da618c40c64705ad98e322"),
    };

    private WellKnownAuthors() {}
}
