/* BSD 3-Clause License - Copyright (c) 2026, Riccardo Balbo */
package org.ngengine.nostr4j.io;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.ngengine.nostr4j.RTCSettings;
import org.ngengine.nostr4j.rtc.NostrTURNPool;
import org.ngengine.nostr4j.rtc.signal.NostrRTCPeer;

public class TestNostrPeerConnectionEof {

    private static final class Loopback extends NostrPeerConnection {
        private Loopback peer;
        private final List<byte[]> sent = new ArrayList<>();
        private CountDownLatch dataEntered;
        private CountDownLatch releaseData;
        private CountDownLatch eofEntered;
        private CountDownLatch releaseEof;
        private boolean failEof;

        private Loopback() {
            super(
                RTCSettings.getDefault("org.ngengine.nostr4j.peer-stream", "byte-stream-v3")
                    .withStunServers(List.of()).withSignalingRelays(List.of()),
                "loopback-test", 2, 2
            );
        }

        @Override
        void sendFrame(ByteBuffer frame) throws IOException {
            if (!frame.hasRemaining()) {
                if (eofEntered != null) {
                    eofEntered.countDown();
                    try {
                        if (!releaseEof.await(5, TimeUnit.SECONDS))
                            throw new IOException("Timed out waiting to release test EOF");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
                if (failEof) throw new IOException("EOF send failed");
            }
            if (frame.hasRemaining() && dataEntered != null) {
                dataEntered.countDown();
                try {
                    if (!releaseData.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting to release test send");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
            }
            byte[] bytes = new byte[frame.remaining()];
            frame.duplicate().get(bytes);
            sent.add(bytes);
            peer.receiveFrame(ByteBuffer.wrap(bytes));
        }
    }

    @Test
    public void eofFollowsAllBinaryDataAndClosesTheRemoteConnection() throws Exception {
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            a.peer = b;
            b.peer = a;

            byte[] data = { 1, 0, 1, 2, 1 };
            a.getOutputStream().write(new byte[0]);
            assertTrue(a.sent.isEmpty());
            a.getOutputStream().write(data);
            a.getOutputStream().close();

            assertArrayEquals(data, b.getInputStream().readAllBytes());
            assertEquals(-1, b.getInputStream().read());
            assertThrows(IOException.class, () -> b.getOutputStream().write(42));
            assertTrue(turnPoolClosed(turnPool(b)));
            assertEquals(4, a.sent.size());
            assertArrayEquals(new byte[] { 1, 0 }, a.sent.get(0));
            assertArrayEquals(new byte[] { 1, 2 }, a.sent.get(1));
            assertArrayEquals(new byte[] { 1 }, a.sent.get(2));
            assertArrayEquals(new byte[0], a.sent.get(3));

            a.getOutputStream().close();
            assertEquals(4, a.sent.size());
            assertThrows(IOException.class, () -> a.getOutputStream().write(3));
        }
    }

    @Test(timeout = 10000)
    public void outputCloseWaitsForTheLastWriteBeforeSendingEof() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            a.peer = b;
            b.peer = a;
            a.dataEntered = new CountDownLatch(1);
            a.releaseData = new CountDownLatch(1);

            Future<?> write = workers.submit(() -> {
                a.getOutputStream().write(new byte[] { 1, 2 });
                return null;
            });
            assertTrue(a.dataEntered.await(2, TimeUnit.SECONDS));
            CountDownLatch closingStarted = new CountDownLatch(1);
            Future<?> close = workers.submit(() -> {
                closingStarted.countDown();
                a.getOutputStream().close();
                return null;
            });
            assertTrue(closingStarted.await(2, TimeUnit.SECONDS));
            assertFalse(close.isDone());
            a.releaseData.countDown();

            write.get(2, TimeUnit.SECONDS);
            close.get(2, TimeUnit.SECONDS);
            assertArrayEquals(new byte[] { 1, 2 }, b.getInputStream().readNBytes(2));
            assertEquals(-1, b.getInputStream().read());
            assertArrayEquals(new byte[] { 1, 2 }, a.sent.get(0));
            assertArrayEquals(new byte[0], a.sent.get(1));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void preservesUnsignedBytesOffsetsAndChunkBoundaries() throws Exception {
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            a.peer = b;
            b.peer = a;

            byte[] allBytes = new byte[256];
            for (int i = 0; i < allBytes.length; i++) allBytes[i] = (byte) i;
            byte[] source = new byte[allBytes.length + 2];
            System.arraycopy(allBytes, 0, source, 1, allBytes.length);
            a.getOutputStream().write(source, 1, allBytes.length);
            a.getOutputStream().close();

            byte[] first = new byte[4];
            assertEquals(2, b.getInputStream().read(first, 1, 2));
            assertArrayEquals(new byte[] { 0, 0, 1, 0 }, first);
            assertEquals(2, b.getInputStream().read());
            assertArrayEquals(Arrays.copyOfRange(allBytes, 3, allBytes.length), b.getInputStream().readAllBytes());
            assertEquals(-1, b.getInputStream().read());
            assertEquals(0, b.getInputStream().read(first, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> a.getOutputStream().write(source, -1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> b.getInputStream().read(first, 3, 2));
        }
    }

    @Test(timeout = 10000)
    public void eofWakesBlockedReader() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            a.peer = b;
            b.peer = a;

            CountDownLatch firstStarted = new CountDownLatch(1);
            Future<Integer> first = workers.submit(() -> {
                firstStarted.countDown();
                return b.getInputStream().read();
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            a.getOutputStream().close();
            assertEquals(-1, (int) first.get(2, TimeUnit.SECONDS));

            assertThrows(IOException.class, () -> b.getOutputStream().write(1));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void explicitCloseAbortsBlockedReader() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback receiver = new Loopback()) {
            CountDownLatch started = new CountDownLatch(1);
            Future<Integer> read = workers.submit(() -> {
                started.countDown();
                return receiver.getInputStream().read();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            receiver.close();
            try {
                read.get(2, TimeUnit.SECONDS);
                fail("Expected a closed connection error");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof IOException);
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void interruptedReadKeepsInterruptAndDoesNotConsumeData() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            a.peer = b;
            b.peer = a;
            Future<?> read = workers.submit(() -> {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedIOException.class, () -> b.getInputStream().read());
                assertTrue(Thread.currentThread().isInterrupted());
            });
            read.get(2, TimeUnit.SECONDS);
            a.getOutputStream().write(255);
            assertEquals(255, b.getInputStream().read());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void concurrentWritesRemainInWholeWriteOrder() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            a.peer = b;
            b.peer = a;
            a.dataEntered = new CountDownLatch(1);
            a.releaseData = new CountDownLatch(1);

            Future<?> first = workers.submit(() -> {
                a.getOutputStream().write(new byte[] { 1, 1, 1, 1 });
                return null;
            });
            assertTrue(a.dataEntered.await(2, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<?> second = workers.submit(() -> {
                secondStarted.countDown();
                a.getOutputStream().write(new byte[] { 2, 2, 2, 2 });
                return null;
            });
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            a.releaseData.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            a.getOutputStream().close();
            assertArrayEquals(new byte[] { 1, 1, 1, 1, 2, 2, 2, 2 }, b.getInputStream().readAllBytes());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void closeRejectsReconnectAndInvalidPeerIdentity() throws Exception {
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            assertThrows(IllegalArgumentException.class, () -> a.connect(a.getPeerId()));
            a.close();
            assertThrows(IOException.class, () -> a.connect(b.getPeerId()));
            assertThrows(IOException.class, () -> a.getOutputStream().write(1));
        }
    }

    @Test(timeout = 10000)
    public void dataAfterEofIsIgnoredAfterQueuedBytesAreDrained() throws Exception {
        try (Loopback receiver = new Loopback()) {
            receiver.receiveFrame(ByteBuffer.wrap(new byte[] { 7, 8 }));
            receiver.receiveFrame(ByteBuffer.allocate(0));
            receiver.receiveFrame(ByteBuffer.wrap(new byte[] { 9 }));
            assertEquals(7, receiver.getInputStream().read());
            assertEquals(8, receiver.getInputStream().read());
            assertEquals(-1, receiver.getInputStream().read());
        }
    }

    @Test
    public void failedEofSendRemainsVisibleOnRepeatedClose() throws Exception {
        try (Loopback a = new Loopback(); Loopback b = new Loopback()) {
            a.peer = b;
            b.peer = a;
            a.failEof = true;
            a.getOutputStream().write(5);

            IOException failure = assertThrows(IOException.class, () -> a.getOutputStream().close());
            assertSame(failure, assertThrows(IOException.class, () -> a.getOutputStream().close()));
            assertThrows(IOException.class, () -> a.getOutputStream().write(6));
            assertEquals(5, b.getInputStream().read());
            assertEquals(1, a.sent.size());
        }
    }

    @Test(timeout = 5000)
    public void disconnectWithoutEofReportsErrorAfterBufferedBytes() throws Exception {
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            setRemoteIdentity(receiver, sender);
            NostrRTCPeer session = remoteSession(sender, "first");
            receiver.onRemotePeerAvailable(session);
            sender.getOutputStream().write(new byte[] { 7, 8 });

            receiver.onRemotePeerDisconnected(session);

            assertArrayEquals(new byte[] { 7, 8 }, receiver.getInputStream().readNBytes(2));
            assertThrows(IOException.class, () -> receiver.getInputStream().read());
            assertThrows(IOException.class, () -> receiver.getOutputStream().write(1));
            assertTrue(turnPoolClosed(turnPool(receiver)));
        }
    }

    @Test(timeout = 5000)
    public void disconnectWithoutEofWakesBlockedReader() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            setRemoteIdentity(receiver, sender);
            NostrRTCPeer session = remoteSession(sender, "only");
            receiver.onRemotePeerAvailable(session);
            CountDownLatch started = new CountDownLatch(1);
            Future<Integer> read = workers.submit(() -> {
                started.countDown();
                return receiver.getInputStream().read();
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            receiver.onRemotePeerDisconnected(session);

            try {
                read.get(1, TimeUnit.SECONDS);
                fail("Expected a disconnect error");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof IOException);
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 5000)
    public void disconnectAfterLocalEofEndsInputNormally() throws Exception {
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            setRemoteIdentity(receiver, sender);
            NostrRTCPeer session = remoteSession(sender, "only");
            receiver.onRemotePeerAvailable(session);
            receiver.getOutputStream().close();
            receiver.onRemotePeerDisconnected(session);
            assertEquals(-1, receiver.getInputStream().read());
        }
    }

    @Test(timeout = 10000)
    public void disconnectDuringEofSendWaitsForItsOutcome() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            setRemoteIdentity(sender, receiver);
            NostrRTCPeer session = remoteSession(receiver, "only");
            sender.onRemotePeerAvailable(session);
            sender.eofEntered = new CountDownLatch(1);
            sender.releaseEof = new CountDownLatch(1);
            Future<?> close = workers.submit(() -> {
                sender.getOutputStream().close();
                return null;
            });
            assertTrue(sender.eofEntered.await(1, TimeUnit.SECONDS));

            sender.onRemotePeerDisconnected(session);
            assertFalse(close.isDone());
            sender.releaseEof.countDown();

            close.get(2, TimeUnit.SECONDS);
            assertEquals(-1, sender.getInputStream().read());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void failedEofDuringDisconnectRemainsAnIoError() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            setRemoteIdentity(sender, receiver);
            NostrRTCPeer session = remoteSession(receiver, "only");
            sender.onRemotePeerAvailable(session);
            sender.eofEntered = new CountDownLatch(1);
            sender.releaseEof = new CountDownLatch(1);
            sender.failEof = true;
            Future<?> close = workers.submit(() -> {
                sender.getOutputStream().close();
                return null;
            });
            assertTrue(sender.eofEntered.await(1, TimeUnit.SECONDS));

            sender.onRemotePeerDisconnected(session);
            sender.releaseEof.countDown();

            try {
                close.get(2, TimeUnit.SECONDS);
                fail("Expected EOF send failure");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof IOException);
            }
            assertThrows(IOException.class, () -> sender.getInputStream().read());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void disconnectWakesEofWaitingForAChannel() throws Exception {
        RTCSettings settings = RTCSettings.getDefault("org.ngengine.nostr4j.peer-stream", "byte-stream-v3")
            .withStunServers(List.of()).withSignalingRelays(List.of());
        CountDownLatch sendingEof = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (
            NostrPeerConnection receiver = new NostrPeerConnection(settings, "waiting-eof", 2, 2);
            NostrPeerConnection sender = new NostrPeerConnection(settings, "waiting-eof", 2, 2) {
                @Override
                void sendFrame(ByteBuffer frame) throws IOException {
                    if (!frame.hasRemaining()) sendingEof.countDown();
                    super.sendFrame(frame);
                }
            }
        ) {
            sender.connect(receiver.getPeerId());
            NostrRTCPeer session = remoteSession(receiver, "only");
            sender.onRemotePeerAvailable(session);
            Future<?> close = workers.submit(() -> {
                sender.getOutputStream().close();
                return null;
            });
            assertTrue(sendingEof.await(2, TimeUnit.SECONDS));

            sender.onRemotePeerDisconnected(session);

            try {
                close.get(2, TimeUnit.SECONDS);
                fail("Expected EOF send failure");
            } catch (java.util.concurrent.ExecutionException error) {
                assertTrue(error.getCause() instanceof IOException);
            }
            assertThrows(IOException.class, () -> sender.getInputStream().read());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test(timeout = 10000)
    public void remoteEofWinsOverDisconnectAndConcurrentEofSendFailure() throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            sender.peer = receiver;
            receiver.peer = sender;
            setRemoteIdentity(sender, receiver);
            NostrRTCPeer session = remoteSession(receiver, "only");
            sender.onRemotePeerAvailable(session);
            sender.eofEntered = new CountDownLatch(1);
            sender.releaseEof = new CountDownLatch(1);
            sender.failEof = true;
            Future<?> close = workers.submit(() -> {
                sender.getOutputStream().close();
                return null;
            });
            assertTrue(sender.eofEntered.await(1, TimeUnit.SECONDS));

            sender.receiveFrame(session, ByteBuffer.allocate(0));
            sender.onRemotePeerDisconnected(session);
            sender.releaseEof.countDown();

            close.get(2, TimeUnit.SECONDS);
            assertEquals(-1, sender.getInputStream().read());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void firstRemoteSessionRemainsPinned() throws Exception {
        try (Loopback sender = new Loopback(); Loopback receiver = new Loopback()) {
            setRemoteIdentity(receiver, sender);
            NostrRTCPeer first = remoteSession(sender, "first");
            NostrRTCPeer second = remoteSession(sender, "second");
            receiver.onRemotePeerAvailable(first);
            receiver.onRemotePeerAvailable(second);
            receiver.receiveFrame(second, ByteBuffer.wrap(new byte[] { 9 }));
            receiver.onRemotePeerDisconnected(second);
            receiver.receiveFrame(first, ByteBuffer.wrap(new byte[] { 7 }));
            assertEquals(7, receiver.getInputStream().read());
            receiver.onRemotePeerDisconnected(first);
            assertThrows(IOException.class, () -> receiver.getInputStream().read());
        }
    }

    private static void setRemoteIdentity(Loopback receiver, Loopback sender) throws Exception {
        java.lang.reflect.Field field = NostrPeerConnection.class.getDeclaredField("remotePeerId");
        field.setAccessible(true);
        field.set(receiver, sender.getPeerId());
    }

    private static NostrRTCPeer remoteSession(NostrPeerConnection sender, String sessionId) {
        return new NostrRTCPeer(
            sender.getPeerId(), "org.ngengine.nostr4j.peer-stream", "byte-stream-v3",
            sessionId, sender.getPeerId(), null
        );
    }

    @Test
    public void turnPoolIsClosedWithItsConnection() throws Exception {
        RTCSettings settings = RTCSettings.getDefault("org.ngengine.nostr4j.peer-stream", "byte-stream-v3")
            .withStunServers(List.of()).withSignalingRelays(List.of());
        NostrPeerConnection connection = new NostrPeerConnection(
            settings, "turn-test", "wss://turn.example.invalid", 2, 2
        );
        NostrTURNPool pool = turnPool(connection);
        connection.close();
        assertTrue(turnPoolClosed(pool));
    }

    private static NostrTURNPool turnPool(NostrPeerConnection connection) throws ReflectiveOperationException {
        java.lang.reflect.Field field = NostrPeerConnection.class.getDeclaredField("turnPool");
        field.setAccessible(true);
        return (NostrTURNPool) field.get(connection);
    }

    private static boolean turnPoolClosed(NostrTURNPool pool) throws ReflectiveOperationException {
        java.lang.reflect.Field closed = NostrTURNPool.class.getDeclaredField("closed");
        closed.setAccessible(true);
        return closed.getBoolean(pool);
    }
}
