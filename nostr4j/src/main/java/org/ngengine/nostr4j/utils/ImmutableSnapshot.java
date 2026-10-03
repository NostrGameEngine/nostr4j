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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.ngengine.platform.NGEUtils;

public final class ImmutableSnapshot {

    private ImmutableSnapshot() {}

    public static <K, V> Map<K, V> snapshotMap(Map<? extends K, ? extends V> source) {
        return snapshotMap(source, true);
    }

    private static final int MAX_DEPTH = 64;
    private static final int MAX_VALUES = 100_000;

    private static final class Budget {

        int values;
        final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<Object, Boolean>();
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> snapshotMap(Map<? extends K, ? extends V> source, boolean deep) {
        if (deep) return (Map<K, V>) copy(source == null ? Collections.emptyMap() : source, new Budget(), 0);
        Map<K, V> out = new LinkedHashMap<>();
        if (source != null) out.putAll(source);
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    public static <T> T snapshotValue(T value) {
        return (T) copy(value, new Budget(), 0);
    }

    private static Object copy(Object value, Budget budget, int depth) {
        if (depth > MAX_DEPTH || ++budget.values > MAX_VALUES) {
            throw new IllegalArgumentException("Metadata snapshot exceeds depth or value budget");
        }
        if (!(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) return value;
        if (budget.active.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Cyclic metadata snapshot");
        }
        try {
            if (value instanceof Map<?, ?>) {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    out.put(entry.getKey(), copy(entry.getValue(), budget, depth + 1));
                }
                return Collections.unmodifiableMap(out);
            }
            List<Object> out = new ArrayList<>();
            for (Object item : (Collection<?>) value) out.add(copy(item, budget, depth + 1));
            return Collections.unmodifiableList(out);
        } finally {
            budget.active.remove(value);
        }
    }

    /** Bound untrusted relay JSON before invoking a recursive JSON parser. */
    public static void validateJsonBounds(String json) {
        if (json == null || json.length() > 1_048_576) throw new IllegalArgumentException("Relay metadata exceeds size budget");
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true; else if (c == '[' || c == '{') {
                if (++depth > MAX_DEPTH) throw new IllegalArgumentException("Relay metadata exceeds nesting budget");
            } else if (c == ']' || c == '}') depth--;
        }
    }

    public static List<String> snapshotStringList(Collection<?> source) {
        return snapshotList(source, NGEUtils::safeString);
    }

    public static List<Integer> snapshotIntList(Collection<?> source) {
        return snapshotList(source, NGEUtils::safeInt);
    }

    public static <S, T> List<T> snapshotList(Collection<? extends S> source, Function<? super S, ? extends T> mapper) {
        List<T> out = new ArrayList<>();
        for (S item : source) {
            out.add(mapper.apply(item));
        }
        return Collections.unmodifiableList(out);
    }
}
