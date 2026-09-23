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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Winding dotted-trail waypoint generation.
 *
 * <p>When a player picks up a treasure map, a dotted trail renders from the
 * pickup point to the victim's island — like a real treasure map, a winding
 * route rather than a straight arrow. The waypoints are a deterministic
 * function of {@code (mapSeed, ax, ay, bx, by)}, so the JS client can render
 * the identical trail (the algorithm is mirrored in
 * {@code site/assets/js/sats-seas.js} — keep them in sync).</p>
 *
 * <p>Zero platform/JS dependencies.</p>
 */
public final class MapTrail {

    private MapTrail() {}

    /**
     * Generate {@code count} waypoints from (ax,ay) to (bx,by).
     *
     * <p>The path wobbles perpendicular to the straight line with two sine
     * harmonics whose phases come from the seed; the envelope is zero at both
     * ends so the trail starts and ends exactly on A and B.</p>
     *
     * @param mapSeed seed unique to the stolen map (e.g. hash of map id)
     * @param ax      start x (pickup point)
     * @param ay      start y
     * @param bx      end x (victim island)
     * @param by      end y
     * @param count   number of waypoints (>= 2)
     * @return immutable list of {@code double[]{x, y}}, first == A, last == B
     */
    public static List<double[]> waypoints(long mapSeed, double ax, double ay, double bx, double by, int count) {
        if (count < 2) throw new IllegalArgumentException("count must be >= 2");
        Mulberry32 rnd = new Mulberry32(mapSeed ^ 0x5EEDL);
        double ph1 = rnd.nextDouble() * Math.PI * 2;
        double ph2 = rnd.nextDouble() * Math.PI * 2;

        double dx = bx - ax;
        double dy = by - ay;
        double dist = Math.sqrt(dx * dx + dy * dy);
        // Perpendicular unit vector (guard the degenerate A == B case).
        double px = dist > 0 ? -dy / dist : 1.0;
        double py = dist > 0 ? dx / dist : 0.0;
        double amp = dist * 0.18;

        List<double[]> out = new ArrayList<double[]>(count);
        for (int k = 0; k < count; k++) {
            double t = (double) k / (count - 1);
            double env = Math.sin(Math.PI * t); // 0 at both ends
            double off = (Math.sin(t * Math.PI * 3 + ph1) * 0.6 + Math.sin(t * Math.PI * 7 + ph2) * 0.25) * env * amp;
            out.add(new double[] { ax + dx * t + px * off, ay + dy * t + py * off });
        }
        return Collections.unmodifiableList(out);
    }
}
