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

package org.ngengine.site.demos.game;

/**
 * mulberry32 — a tiny, well-documented 32-bit PRNG.
 *
 * <p>Chosen because it is trivially portable: the exact same algorithm is
 * mirrored in {@code site/assets/js/sats-seas.js} using {@code Math.imul},
 * so Java and JS produce bit-identical streams from the same seed. This is
 * what lets every client compute the same NPC positions, island spots and
 * map trails without a server.</p>
 */
public final class Mulberry32 {

    private int state;

    public Mulberry32(long seed) {
        // Must stay bit-identical to the JS mirror in site/assets/js/sats-seas.js
        // (which does `seed ^ 0x9e3779b9`). Java int arithmetic wraps mod 2^32
        // exactly like Math.imul, so the streams match bit for bit.
        this.state = (int) seed ^ 0x9E3779B9;
    }

    /** Next unsigned 32-bit value, returned as a long in [0, 2^32). */
    public long nextUint() {
        int s = state += 0x6D2B79F5;
        int z = s;
        z = imul(z ^ (z >>> 15), z | 1);
        z ^= z + imul(z ^ (z >>> 7), z | 61);
        return (z ^ (z >>> 14)) & 0xFFFFFFFFL;
    }

    /** Next double in [0, 1). */
    public double nextDouble() {
        return nextUint() / 4294967296.0;
    }

    /** Next double in [min, max). */
    public double nextRange(double min, double max) {
        return min + nextDouble() * (max - min);
    }

    /** Next int in [0, bound). */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        return (int) (nextDouble() * bound);
    }

    private static int imul(int a, int b) {
        // Java int multiplication already wraps mod 2^32, exactly like Math.imul.
        return a * b;
    }
}
