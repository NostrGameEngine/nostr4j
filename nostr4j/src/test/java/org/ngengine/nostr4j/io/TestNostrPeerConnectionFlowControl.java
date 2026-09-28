/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.nostr4j.io;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.platform.AsyncTask;

public class TestNostrPeerConnectionFlowControl {

    private static final class Loopback extends NostrPeerConnection {
        Loopback peer;
        final List<Byte> controls = new ArrayList<>();
        final CountDownLatch paused = new CountDownLatch(1);
        boolean failControl;

        Loopback() {
            super(RTCSettings.getDefault("flow.test", "stream").withSignalingRelays(List.of()),
                "flow-test", null, 2, 2, 1, 3, 5);
        }

        @Override
        void sendFrame(ByteBuffer frame) {
            byte[] bytes = new byte[frame.remaining()];
            frame.duplicate().get(bytes);
            peer.receiveFrame(ByteBuffer.wrap(bytes));
        }

        @Override
        AsyncTask<Void> sendControlFrame(byte command) {
            if (failControl) return AsyncTask.failed(new IOException("control send failed"));
            controls.add(command);
            if (command == 1) paused.countDown();
            peer.receiveControlFrame(ByteBuffer.wrap(new byte[] { command }));
            return AsyncTask.completed(null);
        }
    }

    @Test(timeout = 10000)
    public void pauseBlocksWriteUntilReaderDrainsBelowLowWatermark() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            Future<?> write = workers.submit(() -> {
                sender.getOutputStream().write(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
                return null;
            });

            assertTrue(receiver.paused.await(2, TimeUnit.SECONDS));
            assertFalse(write.isDone());
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, receiver.getInputStream().readNBytes(4));
            write.get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 5, 6, 7, 8 }, receiver.getInputStream().readNBytes(4));
            assertEquals(List.of((byte) 1, (byte) 2), receiver.controls);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void maliciousSenderCannotExceedHardChunkLimit() throws Exception {
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            for (int i = 0; i < 5; i++) receiver.receiveFrame(ByteBuffer.wrap(new byte[] { 1, 2 }));
            receiver.receiveFrame(ByteBuffer.wrap(new byte[] { 3, 4 }));
            IOException error = assertThrows(IOException.class, () -> receiver.getInputStream().read());
            assertTrue(error.getMessage().contains("Maximum received chunk limit"));
            assertThrows(IOException.class, () -> receiver.getOutputStream().write(1));
        }
    }

    @Test(timeout = 10000)
    public void malformedControlClosesTheConnection() throws Exception {
        try (Loopback connection = new Loopback()) {
            connection.receiveControlFrame(ByteBuffer.wrap(new byte[] { 3 }));
            IOException error = assertThrows(IOException.class, () -> connection.getInputStream().read());
            assertTrue(error.getMessage().contains("Invalid stream control"));
        }
    }

    @Test(timeout = 10000)
    public void closingConnectionWakesPausedWriter() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            Future<?> write = workers.submit(() -> {
                sender.getOutputStream().write(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
                return null;
            });
            assertTrue(receiver.paused.await(2, TimeUnit.SECONDS));
            sender.close();
            ExecutionException error = assertThrows(ExecutionException.class, () -> write.get(2, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IOException);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void recycledChunksDoNotCountTowardTheHardLimit() throws Exception {
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            for (int i = 0; i < 20; i++) {
                sender.getOutputStream().write(new byte[] { 1, 2 });
                assertArrayEquals(new byte[] { 1, 2 }, receiver.getInputStream().readNBytes(2));
            }
            sender.getOutputStream().close();
            assertEquals(-1, receiver.getInputStream().read());
        }
    }

    @Test(timeout = 10000)
    public void failedControlSendAbortsConnection() throws Exception {
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            receiver.failControl = true;
            sender.getOutputStream().write(new byte[] { 1, 2, 3, 4, 5, 6 });
            IOException error = assertThrows(IOException.class, () -> receiver.getInputStream().read());
            assertTrue(error.getMessage().contains("Unable to send stream control"));
        }
    }
}
