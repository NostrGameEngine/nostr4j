package org.ngengine.platform.transport;

import java.nio.ByteBuffer;

public interface RTCTransportListener {
    void onLocalRTCIceCandidate(String candidate);

    void onRTCBinaryMessage(ByteBuffer msg);

    void onRTCDisconnected(String reason);

    void onRTCChannelError(Throwable e);

    void onRTCConnected();
}