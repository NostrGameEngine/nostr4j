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
 * Places player islands at random free spots, deterministically.
 *
 * <p>Given the same seed and the same already-placed islands, every client
 * computes the same spot. A client picks its island with
 * {@code seed = hash(identityPubkey)} the first time it joins and persists
 * the result in {@code localStorage} afterwards.</p>
 *
 * <p>Zero platform/JS dependencies.</p>
 */
public final class IslandPlacer {

    private IslandPlacer() {}

    /** Maximum candidate attempts before giving up (then the last candidate wins). */
    public static final int MAX_ATTEMPTS = 256;

    /**
     * Find a free island center.
     *
     * @param seed    placement seed (e.g. hash of the player's identity pubkey)
     * @param xs      x of already placed islands (same order/length as ys)
     * @param ys      y of already placed islands
     * @param world   world size in pixels (square)
     * @param radius  island radius in pixels
     * @param margin  extra clearance between islands, in pixels
     * @return {@code double[]{x, y}} island center
     */
    public static double[] placeIsland(long seed, double[] xs, double[] ys, double world, double radius, double margin) {
        if (xs.length != ys.length) throw new IllegalArgumentException("xs/ys length mismatch");
        Mulberry32 rnd = new Mulberry32(seed ^ 0x1234ABCDL);
        double minDist = radius * 2 + margin;
        double lo = radius + margin;
        double hi = world - radius - margin;
        double[] best = new double[] { lo, lo }; // must not consume PRNG (mirrors JS)
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double x = lo + rnd.nextDouble() * (hi - lo);
            double y = lo + rnd.nextDouble() * (hi - lo);
            if (isFree(x, y, xs, ys, minDist)) {
                return new double[] { x, y };
            }
            best[0] = x;
            best[1] = y;
        }
        // Extremely crowded map: return the last candidate rather than fail.
        return best;
    }

    /** True if (x,y) keeps {@code minDist} clearance from every placed island. */
    public static boolean isFree(double x, double y, double[] xs, double[] ys, double minDist) {
        double min2 = minDist * minDist;
        for (int i = 0; i < xs.length; i++) {
            double dx = x - xs[i];
            double dy = y - ys[i];
            if (dx * dx + dy * dy < min2) return false;
        }
        return true;
    }
}
