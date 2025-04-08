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
package org.ngengine.nostr4j.utils;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.ngengine.nostr4j.platform.Platform;

public class NostrUtils {

    private static final Logger logger = Logger.getLogger(
        NostrUtils.class.getName()
    );
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    private static volatile Platform platform;

    public static void setPlatform(Platform platform) {
        NostrUtils.platform = platform;
    }

    public static Platform getPlatform() {
        if (NostrUtils.platform == null) {
            logger.warning("Platform not set, using default JVM platform.");
            String defaultPlatformClass =
                "org.ngengine.nostr4j.platform.jvm.JVMAsyncPlatform";
            try {
                Class<?> clazz = Class.forName(defaultPlatformClass);
                NostrUtils.platform =
                    (Platform) clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to load default platform: " + defaultPlatformClass,
                    e
                );
            }
        }
        return NostrUtils.platform;
    }

    public static String bytesToHex(ByteBuffer bbf) {
        char[] hexChars = new char[bbf.limit() * 2];
        for (int j = 0; j < bbf.limit(); j++) {
            int v = bbf.get(j) & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String bytesToHex(byte bbf[]) {
        char[] hexChars = new char[bbf.length * 2];
        for (int j = 0; j < bbf.length; j++) {
            int v = bbf[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static ByteBuffer hexToBytes(String s) {
        int len = s.length();
        ByteBuffer buf = ByteBuffer.allocate(len / 2);
        for (int i = 0; i < len; i += 2) {
            buf.put(
                i / 2,
                (byte) (
                    (Character.digit(s.charAt(i), 16) << 4) +
                    Character.digit(s.charAt(i + 1), 16)
                )
            );
        }
        return buf;
    }

    public static byte[] hexToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] =
                (byte) (
                    (Character.digit(s.charAt(i), 16) << 4) +
                    Character.digit(s.charAt(i + 1), 16)
                );
        }
        return data;
    }

    public static boolean allZeroes(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convert an input object to a long
     *
     * @param input
     * @return
     */
    public static long safeLong(Object input) {
        if (input instanceof Number) {
            return ((Number) input).longValue();
        } else {
            try {
                Long l = Long.parseLong(String.valueOf(input));
                return l.longValue();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Input is not a number: " + input
                );
            }
        }
    }

    public static int safeInt(Object input) {
        long l = safeLong(input);
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Input is out of range for int: " + l
            );
        }
        return (int) l;
    }

    public static String safeString(Object input) {
        if (input == null) return "";
        if (input instanceof String) {
            return (String) input;
        } else {
            return String.valueOf(input);
        }
    }

    public static String[] safeStringArray(Object tags) {
        if (tags instanceof Collection) {
            Collection<String> c = (Collection<String>) tags;
            return c.toArray(new String[c.size()]);
        } else if (tags instanceof String[]) {
            return (String[]) tags;
        } else {
            throw new IllegalArgumentException(
                "Input is not a string array: " + tags
            );
        }
    }

    public static Collection<String[]> safeCollectionOfStringArray(
        Object tags
    ) {
        if (tags instanceof Collection && !(tags instanceof List)) {
            tags = new ArrayList<>((Collection<?>) tags);
        }

        if (tags instanceof List) {
            List<?> c = (List<?>) tags;
            if (c.isEmpty()) {
                return (Collection<String[]>) c;
            }
            if (c.get(0) instanceof String[]) {
                return (Collection<String[]>) c;
            } else {
                ArrayList<String[]> list = new ArrayList<>();
                for (Object o : c) {
                    list.add(safeStringArray(o));
                }
                return list;
            }
        } else if (tags instanceof String[][]) {
            String[][] arr = (String[][]) tags;
            ArrayList<String[]> list = new ArrayList<>();
            for (String[] o : arr) {
                list.add(o);
            }
            return list;
        } else {
            throw new IllegalArgumentException(
                "Input is not a string array: " + tags
            );
        }
    }

    public static boolean safeBool(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        } else if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        } else if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        } else {
            throw new IllegalArgumentException("Input is not a boolean: " + v);
        }
    }

    public static Instant safeSecondsInstant(Object object) {
        if (object instanceof Instant) {
            return (Instant) object;
        } else if (object instanceof String) {
            try {
                return Instant.parse((String) object);
            } catch (Exception e) {
                return Instant.ofEpochSecond(safeLong(object));
            }
        } else {
            return Instant.ofEpochSecond(safeLong(object));
        }
    }

    /**
     * Wrapper to exploit assert to toggle on/off debug code
     * usage:
     *  assert dbg(()->{
     *     // heavy debug code
     *  });
     */
    public static boolean dbg(Runnable r) {
        Supplier<Boolean> s = () -> {
            try {
                r.run();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        };
        assert s.get() : "Debug statement failed";
        return true;
    }
}
