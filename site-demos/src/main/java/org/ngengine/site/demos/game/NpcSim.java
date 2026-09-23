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
 * Deterministic NPC simulation for Sats Seas.
 *
 * <p>Sharks and megalodons (deep-water band) and ghost ships (cursed zones)
 * are pure functions of {@code (worldSeed, timeMs)}: every client computes
 * identical NPC positions with no server and no network traffic. Combat
 * damage is resolved locally by each client against these positions
 * (see PROTOCOL.md).</p>
 *
 * <p>The motion model is parametric: each NPC orbits a seeded home point with
 * seeded radius, angular speed and phase. The exact formulas are mirrored in
 * {@code site/assets/js/sats-seas.js} — keep them in sync.</p>
 *
 * <p>Zero platform/JS dependencies: runs on plain JVM (tested by
 * {@link JvmSmokeTest}) and under TeaVM.</p>
 */
public final class NpcSim {

    private NpcSim() {}

    /** World is a WORLD x WORLD square of pixels. */
    public static final int WORLD = 4096;

    /** Deep-water band (shark territory): horizontal strip across the map. */
    public static final int DEEP_BAND_Y0 = 1800;
    public static final int DEEP_BAND_Y1 = 2300;

    /** Cursed zones (ghost ships): fixed, placed circles — never random. */
    public static final double CURSED_A_X = 950;
    public static final double CURSED_A_Y = 950;
    public static final double CURSED_B_X = 3150;
    public static final double CURSED_B_Y = 3150;
    public static final double CURSED_R = 420;

    public static final int SHARK_COUNT = 6;
    public static final int GHOST_COUNT = 3;
    public static final int MEGA_COUNT = 2;

    /** NPC hit points (local combat). */
    public static final int SHARK_HP = 30;
    public static final int GHOST_HP = 120;
    public static final int MEGA_HP = 150;

    /** Collision radii in pixels. */
    public static final double SHARK_R = 12;
    public static final double GHOST_R = 20;
    public static final double MEGA_R = 26;

    public enum Kind {
        SHARK,
        GHOST,
        MEGA,
    }

    public static final class Npc {

        public final int id;
        public final Kind kind;
        public final double x;
        public final double y;
        /** Heading in radians (direction of travel). */
        public final double angle;
        public final int maxHp;

        Npc(int id, Kind kind, double x, double y, double angle, int maxHp) {
            this.id = id;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.angle = angle;
            this.maxHp = maxHp;
        }

        @Override
        public String toString() {
            return "Npc{id=" + id + ", kind=" + kind + ", x=" + x + ", y=" + y + "}";
        }
    }

    /**
     * Compute every NPC's state at the given time.
     *
     * @param worldSeed shared world seed (derived from the room key seed)
     * @param timeMs    game time in milliseconds (client clock is fine: NPCs
     *                  only need to look the same, not tick in lockstep)
     * @return immutable list of NPCs, deterministic for (worldSeed, timeMs)
     */
    public static List<Npc> npcsFor(long worldSeed, long timeMs) {
        double t = (double) timeMs;
        List<Npc> out = new ArrayList<Npc>(SHARK_COUNT + GHOST_COUNT + MEGA_COUNT);
        for (int i = 0; i < SHARK_COUNT; i++) {
            out.add(shark(worldSeed, i, t));
        }
        for (int i = 0; i < GHOST_COUNT; i++) {
            out.add(ghost(worldSeed, i, t));
        }
        for (int i = 0; i < MEGA_COUNT; i++) {
            out.add(megalodon(worldSeed, i, t));
        }
        return Collections.unmodifiableList(out);
    }

    private static Mulberry32 rngFor(long worldSeed, int npcIndex) {
        // Per-NPC stream: same seed + same index => same stream, on every client.
        return new Mulberry32(worldSeed ^ (npcIndex * 0x9E3779B9L + 0x85EBCA6BL));
    }

    private static Npc shark(long worldSeed, int i, double t) {
        Mulberry32 rnd = rngFor(worldSeed, i);
        double hx = 240 + rnd.nextDouble() * (WORLD - 480);
        double hy = DEEP_BAND_Y0 + 40 + rnd.nextDouble() * (DEEP_BAND_Y1 - DEEP_BAND_Y0 - 80);
        double r = 130 + rnd.nextDouble() * 170;
        double dir = rnd.nextDouble() < 0.5 ? -1.0 : 1.0;
        double w = dir * (0.00013 + rnd.nextDouble() * 0.00015); // rad/ms
        double p = rnd.nextDouble() * Math.PI * 2;

        double wt = w * t + p;
        double x = hx + Math.cos(wt) * r;
        double y = hy + Math.sin(w * 0.83 * t + p * 1.7) * r * 0.55;
        // Heading from the analytic derivative of the position.
        double dx = -Math.sin(wt) * r * w;
        double dy = Math.cos(w * 0.83 * t + p * 1.7) * r * 0.55 * w * 0.83;
        return new Npc(i, Kind.SHARK, x, y, Math.atan2(dy, dx), SHARK_HP);
    }

    private static Npc ghost(long worldSeed, int i, double t) {
        Mulberry32 rnd = rngFor(worldSeed, SHARK_COUNT + i);
        double cx = (i % 2 == 0) ? CURSED_A_X : CURSED_B_X;
        double cy = (i % 2 == 0) ? CURSED_A_Y : CURSED_B_Y;
        double hx = cx + (rnd.nextDouble() - 0.5) * 560;
        double hy = cy + (rnd.nextDouble() - 0.5) * 560;
        double r = 190 + rnd.nextDouble() * 150;
        double dir = rnd.nextDouble() < 0.5 ? -1.0 : 1.0;
        double w = dir * (0.00005 + rnd.nextDouble() * 0.00006); // slower than sharks
        double p = rnd.nextDouble() * Math.PI * 2;

        double wt = w * t + p;
        double x = hx + Math.cos(wt) * r;
        double y = hy + Math.sin(w * 0.9 * t + p) * r * 0.7;
        double dx = -Math.sin(wt) * r * w;
        double dy = Math.cos(w * 0.9 * t + p) * r * 0.7 * w * 0.9;
        return new Npc(SHARK_COUNT + i, Kind.GHOST, x, y, Math.atan2(dy, dx), GHOST_HP);
    }

    private static Npc megalodon(long worldSeed, int i, double t) {
        Mulberry32 rnd = rngFor(worldSeed, SHARK_COUNT + GHOST_COUNT + i);
        double hx = 240 + rnd.nextDouble() * (WORLD - 480);
        double hy = DEEP_BAND_Y0 + 60 + rnd.nextDouble() * (DEEP_BAND_Y1 - DEEP_BAND_Y0 - 120);
        double r = 170 + rnd.nextDouble() * 200;
        double dir = rnd.nextDouble() < 0.5 ? -1.0 : 1.0;
        double w = dir * (0.00020 + rnd.nextDouble() * 0.00020); // faster than sharks
        double p = rnd.nextDouble() * Math.PI * 2;

        double wt = w * t + p;
        double x = hx + Math.cos(wt) * r;
        double y = hy + Math.sin(w * 0.8 * t + p * 1.3) * r * 0.5;
        double dx = -Math.sin(wt) * r * w;
        double dy = Math.cos(w * 0.8 * t + p * 1.3) * r * 0.5 * w * 0.8;
        return new Npc(SHARK_COUNT + GHOST_COUNT + i, Kind.MEGA, x, y, Math.atan2(dy, dx), MEGA_HP);
    }
}
