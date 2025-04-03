package org.ngengine.nostr4j.utils;

import java.nio.ByteBuffer;
import java.util.AbstractList;

public class ByteBufferList extends AbstractList<Byte> {

    private final ByteBuffer buffer;

    public ByteBufferList(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public Byte get(int index) {
        return buffer.get(index);
    }

    @Override
    public int size() {
        return buffer.limit();
    }

}
