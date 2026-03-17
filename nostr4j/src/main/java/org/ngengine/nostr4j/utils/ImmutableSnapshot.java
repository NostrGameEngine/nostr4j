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

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> snapshotMap(Map<? extends K, ? extends V> source, boolean deep) {
        Map<K, V> out = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<? extends K, ? extends V> entry : source.entrySet()) {
                out.put(entry.getKey(), deep ? snapshotValue(entry.getValue()) : entry.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    public static <T> T snapshotValue(T value) {
        if (value instanceof Map<?, ?>) {
            return (T) snapshotMap((Map<?, ?>) value, true);
        }
        if (value instanceof List<?>) {
            return (T) snapshotCollection((List<?>) value);
        }
        if (value instanceof Collection<?>) {
            return (T) snapshotCollection((Collection<?>) value);
        }
        return value;
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

    private static List<Object> snapshotCollection(Collection<?> source) {
        List<Object> out = new ArrayList<>();
        for (Object item : source) {
            out.add(snapshotValue(item));
        }
        return Collections.unmodifiableList(out);
    }
}
