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

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Plain-JVM smoke test for the platform-free game logic
 * ({@link Mulberry32}, {@link NpcSim}, {@link IslandPlacer},
 * {@link MapTrail}, {@link WireCodec}).
 *
 * <p>No JUnit, no network, no TeaVM: {@code java JvmSmokeTest}. Exit code 0
 * means every check passed; any failure prints {@code FAIL} lines and exits
 * non-zero.</p>
 */
public final class JvmSmokeTest {

    private static int failures = 0;

    public static void main(String[] args) {
        check(
            "mulberry32.determinism",
            () -> {
                Mulberry32 a = new Mulberry32(12345L);
                Mulberry32 b = new Mulberry32(12345L);
                for (int i = 0; i < 100; i++) {
                    assertEq(a.nextUint(), b.nextUint(), "stream diverged at " + i);
                }
            }
        );
        check(
            "mulberry32.distinctSeeds",
            () -> {
                Mulberry32 a = new Mulberry32(1L);
                Mulberry32 b = new Mulberry32(2L);
                boolean anyDiff = false;
                for (int i = 0; i < 10; i++) anyDiff |= a.nextUint() != b.nextUint();
                assertTrue(anyDiff, "different seeds produced identical streams");
            }
        );
        check(
            "mulberry32.range",
            () -> {
                Mulberry32 r = new Mulberry32(99L);
                for (int i = 0; i < 1000; i++) {
                    double d = r.nextDouble();
                    assertTrue(d >= 0.0 && d < 1.0, "nextDouble out of range: " + d);
                }
            }
        );

        check(
            "npcsim.determinism",
            () -> {
                List<NpcSim.Npc> a = NpcSim.npcsFor(0xC0FFEE, 123456789L);
                List<NpcSim.Npc> b = NpcSim.npcsFor(0xC0FFEE, 123456789L);
                assertEq(a.size(), b.size(), "npc count");
                assertEq(NpcSim.SHARK_COUNT + NpcSim.GHOST_COUNT + NpcSim.MEGA_COUNT, a.size(), "expected npc count");
                for (int i = 0; i < a.size(); i++) {
                    NpcSim.Npc na = a.get(i);
                    NpcSim.Npc nb = b.get(i);
                    assertEq(na.id, nb.id, "id");
                    assertTrue(na.kind == nb.kind, "kind");
                    assertEqD(na.x, nb.x, "x");
                    assertEqD(na.y, nb.y, "y");
                    assertEqD(na.angle, nb.angle, "angle");
                }
            }
        );
        check(
            "npcsim.movesWithTime",
            () -> {
                List<NpcSim.Npc> a = NpcSim.npcsFor(7L, 0L);
                List<NpcSim.Npc> b = NpcSim.npcsFor(7L, 60_000L);
                boolean moved = false;
                for (int i = 0; i < a.size(); i++) {
                    moved |= Math.abs(a.get(i).x - b.get(i).x) > 1e-9;
                }
                assertTrue(moved, "npcs did not move over 60s");
            }
        );
        check(
            "npcsim.seedChangesWorld",
            () -> {
                List<NpcSim.Npc> a = NpcSim.npcsFor(1L, 5000L);
                List<NpcSim.Npc> b = NpcSim.npcsFor(2L, 5000L);
                boolean diff = false;
                for (int i = 0; i < a.size(); i++) {
                    diff |= Math.abs(a.get(i).x - b.get(i).x) > 1e-9;
                }
                assertTrue(diff, "different seeds gave identical npc layouts");
            }
        );
        check(
            "npcsim.zones",
            () -> {
                // Sharks and megalodons stay inside the deep band (with orbit margin),
                // ghosts near cursed zones.
                List<NpcSim.Npc> npcs = NpcSim.npcsFor(4242L, 999_999L);
                for (NpcSim.Npc n : npcs) {
                    assertTrue(n.x >= -400 && n.x <= NpcSim.WORLD + 400, "npc out of world x: " + n);
                    assertTrue(n.y >= -400 && n.y <= NpcSim.WORLD + 400, "npc out of world y: " + n);
                    if (n.kind == NpcSim.Kind.SHARK || n.kind == NpcSim.Kind.MEGA) {
                        assertTrue(
                            n.y > NpcSim.DEEP_BAND_Y0 - 400 && n.y < NpcSim.DEEP_BAND_Y1 + 400,
                            "deep-water npc outside deep band: " + n
                        );
                    }
                }
            }
        );

        check(
            "island.nonOverlap",
            () -> {
                double[] xs = new double[12];
                double[] ys = new double[12];
                double radius = 120, margin = 260, world = NpcSim.WORLD;
                for (int i = 0; i < 12; i++) {
                    double[] p = IslandPlacer.placeIsland(
                        0xBEEF + i,
                        java.util.Arrays.copyOf(xs, i),
                        java.util.Arrays.copyOf(ys, i),
                        world,
                        radius,
                        margin
                    );
                    xs[i] = p[0];
                    ys[i] = p[1];
                    assertTrue(p[0] >= radius && p[0] <= world - radius, "island x out of bounds");
                    assertTrue(p[1] >= radius && p[1] <= world - radius, "island y out of bounds");
                }
                double minDist = radius * 2 + margin;
                for (int i = 0; i < 12; i++) {
                    for (int j = i + 1; j < 12; j++) {
                        double dx = xs[i] - xs[j], dy = ys[i] - ys[j];
                        double d = Math.sqrt(dx * dx + dy * dy);
                        assertTrue(d + 1e-9 >= minDist, "islands " + i + "," + j + " overlap: d=" + d);
                    }
                }
            }
        );
        check(
            "island.determinism",
            () -> {
                double[] xs = { 500, 1500 };
                double[] ys = { 500, 1500 };
                double[] a = IslandPlacer.placeIsland(777L, xs, ys, 4096, 120, 260);
                double[] b = IslandPlacer.placeIsland(777L, xs, ys, 4096, 120, 260);
                assertEqD(a[0], b[0], "x");
                assertEqD(a[1], b[1], "y");
            }
        );

        check(
            "trail.determinismAndEndpoints",
            () -> {
                List<double[]> a = MapTrail.waypoints(0x5EEDL, 100, 100, 3000, 2000, 48);
                List<double[]> b = MapTrail.waypoints(0x5EEDL, 100, 100, 3000, 2000, 48);
                assertEq(48, a.size(), "count");
                for (int i = 0; i < 48; i++) {
                    assertEqD(a.get(i)[0], b.get(i)[0], "x@" + i);
                    assertEqD(a.get(i)[1], b.get(i)[1], "y@" + i);
                }
                assertEqD(100.0, a.get(0)[0], "start x");
                assertEqD(100.0, a.get(0)[1], "start y");
                assertEqD(3000.0, a.get(47)[0], "end x");
                assertEqD(2000.0, a.get(47)[1], "end y");
                // Midpoints must actually wind (not be the straight line).
                double straight = 0, wound = 0;
                for (int i = 0; i < 47; i++) {
                    double dx = a.get(i + 1)[0] - a.get(i)[0], dy = a.get(i + 1)[1] - a.get(i)[1];
                    wound += Math.sqrt(dx * dx + dy * dy);
                }
                straight = Math.sqrt(2900 * 2900 + 1900 * 1900);
                assertTrue(wound > straight * 1.02, "trail is not winding: " + wound + " vs " + straight);
            }
        );
        check(
            "trail.seedMatters",
            () -> {
                List<double[]> a = MapTrail.waypoints(1L, 0, 0, 1000, 0, 16);
                List<double[]> b = MapTrail.waypoints(2L, 0, 0, 1000, 0, 16);
                boolean diff = false;
                for (int i = 1; i < 15; i++) diff |= Math.abs(a.get(i)[1] - b.get(i)[1]) > 1e-9;
                assertTrue(diff, "different trail seeds gave identical trails");
            }
        );

        check(
            "codec.roundtrip",
            () -> {
                String[] samples = {
                    "{\"type\":\"hello\",\"name\":\"Anne\"}",
                    "",
                    "{\"chat\":\"héllo wörld ⚓\"}",
                    "{\"big\":\"" + repeat("x", 5000) + "\"}",
                };
                for (String s : samples) {
                    ByteBuffer enc = WireCodec.encode(s);
                    String dec = WireCodec.decodeOne(enc.slice());
                    assertTrue(s.equals(dec), "roundtrip failed for len=" + s.length());
                }
            }
        );
        check(
            "codec.multiFrame",
            () -> {
                ByteBuffer a = WireCodec.encode("{\"a\":1}");
                ByteBuffer c = WireCodec.encode("{\"b\":2}");
                ByteBuffer cat = ByteBuffer.allocate(a.remaining() + c.remaining());
                cat.put(a);
                cat.put(c);
                cat.flip();
                List<String> frames = WireCodec.decodeAll(cat);
                assertEq(2, frames.size(), "frame count");
                assertTrue("{\"a\":1}".equals(frames.get(0)), "frame 0");
                assertTrue("{\"b\":2}".equals(frames.get(1)), "frame 1");
            }
        );
        check(
            "codec.partialFrame",
            () -> {
                ByteBuffer enc = WireCodec.encode("hello");
                ByteBuffer partial = enc.slice();
                partial.limit(3); // truncated length prefix
                assertTrue(WireCodec.decodeOne(partial) == null, "partial frame should decode to null");
                assertEq(0, partial.position(), "position must be untouched on partial");
            }
        );
        check(
            "codec.rejectsOversizedFrame",
            () -> {
                boolean rejected = false;
                try {
                    WireCodec.encode(repeat("x", WireCodec.MAX_FRAME_BYTES + 1));
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                assertTrue(rejected, "oversized outgoing frame was accepted");

                ByteBuffer incoming = ByteBuffer.allocate(4);
                incoming.putInt(WireCodec.MAX_FRAME_BYTES + 1).flip();
                assertTrue(WireCodec.decodeOne(incoming) == null, "oversized incoming frame was accepted");
                assertEq(0, incoming.position(), "position must be untouched on rejected frame");
            }
        );

        if (failures == 0) {
            System.out.println("JvmSmokeTest: ALL CHECKS PASSED");
        } else {
            System.out.println("JvmSmokeTest: " + failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void check(String name, Check c) {
        try {
            c.run();
            System.out.println("PASS " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertEq(long expected, long actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
    }

    private static void assertEqD(double expected, double actual, String msg) {
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }
}
