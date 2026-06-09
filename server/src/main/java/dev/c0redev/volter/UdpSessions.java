package dev.c0redev.volter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

final class UdpSessions implements AutoCloseable {

    private final Logger log = Log.logger(UdpSessions.class);
    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final Selector selector;
    private final Thread selectorThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger writerSeq = new AtomicInteger(1);
    private static final int UDP_BUFFER_SIZE = 64 * 1024;
    private static final int WRITE_RETRY_DELAY_MS = 2;
    private static final int WRITE_TIMEOUT_MS = 2_000;
    private static final long SESSION_IDLE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int MAX_UDP_SESSIONS = 2048;
    private static final int UDP_SOCKET_BUF = 4 * 1024 * 1024;
    private static final int EVICT_BATCH = 64;
    private static final int HIGH_WATER_MARK = 512;

    UdpSessions(int channels) throws IOException {
        this.selector = Selector.open();
        this.selectorThread = new Thread(this::selectLoop, "udp-selector");
        this.selectorThread.setDaemon(true);
        this.selectorThread.start();
    }

    UdpChannelWriter createWriter(
        OutputStream out,
        Protocol.ClientOptions opts
    ) {
        int id = writerSeq.getAndIncrement();
        return new UdpChannelWriter(id, out, opts);
    }

    void removeWriter(UdpChannelWriter writer) {
        if (writer == null) return;
        writer.close();
        for (Session s : sessions.values()) {
            if (s.writer == writer) {
                closeSession(s.key, s);
            }
        }
    }

    void onFrame(UdpChannelWriter writer, int channelId, Protocol.UdpFrame f)
        throws IOException {
        Key k = new Key(
            writer.id,
            f.addrType(),
            f.srcPort(),
            f.dst().getAddress(),
            f.dstPort()
        );
        Session s = sessions.get(k);
        if (s == null) {
            evictIfNeeded();
            Session created = createSession(k, f, writer);
            Session raced = sessions.putIfAbsent(k, created);
            if (raced != null) {
                created.close();
                s = raced;
            } else {
                s = created;
            }
        }
        try {
            s.send(f.payload());
        } catch (IOException e) {
            closeSession(k, s);
            throw e;
        }
    }

    private Session createSession(
        Key k,
        Protocol.UdpFrame f,
        UdpChannelWriter writer
    ) throws IOException {
        DatagramChannel dc = DatagramChannel.open();
        dc.configureBlocking(false);
        dc.setOption(StandardSocketOptions.SO_RCVBUF, UDP_SOCKET_BUF);
        dc.setOption(StandardSocketOptions.SO_SNDBUF, UDP_SOCKET_BUF);
        dc.connect(new InetSocketAddress(f.dst(), f.dstPort()));
        SelectionKey sk = dc.register(selector, SelectionKey.OP_READ);
        Session s = new Session(k, f.dst(), dc, sk, writer);
        sk.attach(s);
        selector.wakeup();
        return s;
    }

    private void selectLoop() {
        ByteBuffer buf = ByteBuffer.allocateDirect(UDP_BUFFER_SIZE);
        long lastCleanup = System.nanoTime();
        while (!closed.get()) {
            try {
                int n = selector.select(1000);
                if (n > 0) {
                    for (SelectionKey k : selector.selectedKeys()) {
                        if (!k.isValid() || !k.isReadable()) continue;
                        Object a = k.attachment();
                        if (!(a instanceof Session s)) continue;
                        try {
                            while (true) {
                                buf.clear();
                                int r = s.dc.read(buf);
                                if (r < 0) {
                                    closeSession(s.key, s);
                                    break;
                                }
                                if (r == 0) break;
                                buf.flip();
                                byte[] payload = new byte[buf.remaining()];
                                buf.get(payload);
                                s.touch();
                                if (s.writer != null) {
                                    boolean queued = s.writer.send(
                                        new Protocol.UdpFrame(
                                            s.key.addrType,
                                            s.key.srcPort,
                                            s.dst,
                                            s.key.dstPort,
                                            payload
                                        )
                                    );
                                    if (!queued) {
                                        closeSession(s.key, s);
                                        break;
                                    }
                                }
                            }
                        } catch (IOException e) {
                            log.warning(
                                "udp read failed for key=" +
                                    s.key.srcPort +
                                    ":" +
                                    s.key.dstPort +
                                    " - " +
                                    e.getMessage()
                            );
                            closeSession(s.key, s);
                        }
                    }
                    selector.selectedKeys().clear();
                }
                long now = System.nanoTime();
                if (now - lastCleanup >= TimeUnit.SECONDS.toNanos(5)) {
                    cleanupIdleSessions(now);
                    int sessN = sessions.size();
                    if (sessN >= HIGH_WATER_MARK) {
                        log.warning("udp sessions=" + sessN + " cap=" + MAX_UDP_SESSIONS);
                    }
                    lastCleanup = now;
                }
            } catch (IOException e) {
                log.warning("udp selector error: " + e.getMessage());
            }
        }
    }

    private void cleanupIdleSessions(long nowNanos) {
        for (Session s : sessions.values()) {
            long idleNanos = nowNanos - s.lastActivityNanos();
            if (idleNanos >= SESSION_IDLE_TIMEOUT_NANOS) {
                closeSession(s.key, s);
            }
        }
    }

    private void evictIfNeeded() {
        int evicted = 0;
        while (sessions.size() >= MAX_UDP_SESSIONS && evicted < EVICT_BATCH) {
            Session oldest = null;
            for (Session s : sessions.values()) {
                if (oldest == null || s.lastActivityNanos() < oldest.lastActivityNanos()) {
                    oldest = s;
                }
            }
            if (oldest == null) {
                break;
            }
            log.warning("udp sessions cap " + MAX_UDP_SESSIONS + ", evict key dstPort=" + oldest.key.dstPort);
            closeSession(oldest.key, oldest);
            evicted++;
        }
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        for (Session s : sessions.values()) {
            try { s.close(); } catch (IOException ignored) {}
        }
        sessions.clear();
        UdpChannelWriter.shutdownPool();
        try { selector.wakeup(); } catch (Exception ignored) {}
        try { selector.close(); } catch (Exception ignored) {}
        try { selectorThread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class Session implements AutoCloseable {

        final Key key;
        final java.net.InetAddress dst;
        final DatagramChannel dc;
        final SelectionKey sk;
        final UdpChannelWriter writer;
        private volatile long lastActivityNanos;

        Session(
            Key key,
            java.net.InetAddress dst,
            DatagramChannel dc,
            SelectionKey sk,
            UdpChannelWriter writer
        ) {
            this.key = key;
            this.dst = dst;
            this.dc = dc;
            this.sk = sk;
            this.writer = writer;
            this.lastActivityNanos = System.nanoTime();
        }

        void send(byte[] payload) throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(payload);
            synchronized (this) {

                touch();
                long timeoutAt =
                    System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(WRITE_TIMEOUT_MS);
                while (bb.hasRemaining()) {
                    int w = dc.write(bb);
                    if (w > 0) continue;
                    if (System.nanoTime() >= timeoutAt) throw new IOException(
                        "udp send timeout"
                    );
                    try {
                        TimeUnit.MILLISECONDS.sleep(WRITE_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("udp send interrupted", e);
                    }
                }
            }
        }

        void touch() {
            this.lastActivityNanos = System.nanoTime();
        }

        long lastActivityNanos() {
            return lastActivityNanos;
        }

        @Override
        public void close() throws IOException {
            try {
                sk.cancel();
            } catch (Exception ignored) {}
            dc.close();
        }
    }

public static final class UdpChannelWriter implements AutoCloseable {

    final int id;
    private final OutputStream out;
    private final Protocol.ClientOptions opts;
    private static final int MAX_QUEUE_FRAMES = 2048;
    private static final long MAX_QUEUE_BYTES = 8L * 1024 * 1024;
    private static final long ENQUEUE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private final LinkedBlockingQueue<QueuedFrame> q =
        new LinkedBlockingQueue<>(MAX_QUEUE_FRAMES);
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private static final int WRITER_POOL_SIZE = Math.max(32, Runtime.getRuntime().availableProcessors() * 2);
    private static final java.util.concurrent.ExecutorService writerPool =
        java.util.concurrent.Executors.newFixedThreadPool(WRITER_POOL_SIZE, r -> {
            Thread t = new Thread(r, "udp-writer");
            t.setDaemon(true);
            return t;
        });
    private final java.util.concurrent.Future<?> future;
    private long budgetWindowStartNanos;
    private long budgetWindowBytes;

    UdpChannelWriter(
        int id,
        OutputStream out,
        Protocol.ClientOptions opts
    ) {
        this.id = id;
        this.out = out;
        this.opts = opts;
        this.budgetWindowStartNanos = System.nanoTime();

        this.future = writerPool.submit(this::loop);
    }

        private static final Logger writerLog = Log.logger(UdpChannelWriter.class);

        boolean send(Protocol.UdpFrame f) {
            if (closed.get()) return false;
            long frameBytes = queuedBytes(f);
            if (!reserveBytes(frameBytes)) {
                writerLog.warning("udp writer id=" + id + " queue bytes full (" + queuedBytes.get() + "/" + MAX_QUEUE_BYTES + "), closing writer");
                close();
                return false;
            }
            QueuedFrame item = new QueuedFrame(f, frameBytes);
            boolean queued = false;
            try {
                queued = q.offer(item, ENQUEUE_TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
                if (!queued) {
                    writerLog.warning("udp writer id=" + id + " queue frames full (" + q.size() + "/" + MAX_QUEUE_FRAMES + "), closing writer");
                    close();
                }
                return queued;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                return false;
            } finally {
                if (!queued) {
                    releaseBytes(frameBytes);
                }
            }
        }

        private boolean reserveBytes(long frameBytes) {
            long deadline = System.nanoTime() + ENQUEUE_TIMEOUT_NANOS;
            while (!closed.get()) {
                long current = queuedBytes.get();
                if (current + frameBytes <= MAX_QUEUE_BYTES) {
                    if (queuedBytes.compareAndSet(current, current + frameBytes)) return true;
                    continue;
                }
                if (System.nanoTime() >= deadline) return false;
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        private static long queuedBytes(Protocol.UdpFrame f) {
            int payload = f != null && f.payload() != null ? f.payload().length : 0;
            return payload + 64L;
        }

        private void releaseBytes(long frameBytes) {
            queuedBytes.updateAndGet(current -> Math.max(0L, current - frameBytes));
        }

        private void loop() {
            while (!closed.get()) {
                try {
                    QueuedFrame item = q.poll(1, TimeUnit.SECONDS);
                    if (item == null) continue;
                    releaseBytes(item.bytes());
                    Protocol.UdpFrame f = item.frame();
                    synchronized (out) {
                        int pad = opts != null ? opts.padS4() : 0;
                        int maxPad =
                            pad > 0 && pad <= 64 ? pad : Protocol.MAX_PAD;
                        applyRelayBudget(f.payload().length);
                        Protocol.writeUdpFrame(out, f, maxPad);
                        out.flush();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closed.set(true);
                } catch (IOException e) {
                    closed.set(true);
                } finally {
                    if (closed.get()) {
                        q.clear();
                        queuedBytes.set(0);
                    }
                }
            }
        }

        private void applyRelayBudget(int payloadBytes) throws InterruptedException {
            int kbps = opts != null ? opts.relayBudgetKbps() : 0;
            if (kbps <= 0) {
                return;
            }
            long now = System.nanoTime();
            long windowNanos = now - budgetWindowStartNanos;
            if (windowNanos >= TimeUnit.SECONDS.toNanos(1)) {
                budgetWindowStartNanos = now;
                budgetWindowBytes = 0;
            }
            long maxBytesPerSec = (long) kbps * 1024 / 8;
            if (budgetWindowBytes >= maxBytesPerSec) {
                long sleepMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(TimeUnit.SECONDS.toNanos(1) - (now - budgetWindowStartNanos)));
                TimeUnit.MILLISECONDS.sleep(sleepMs);
                budgetWindowStartNanos = System.nanoTime();
                budgetWindowBytes = 0;
            }
            budgetWindowBytes += Math.max(0, payloadBytes);
        }

@Override
    public void close() {
        closed.set(true);
        q.clear();
        queuedBytes.set(0);
        if (future != null) future.cancel(true);
    }
    static void shutdownPool() {
        writerPool.shutdownNow();
    }

    private record QueuedFrame(Protocol.UdpFrame frame, long bytes) {}
    }

    private void closeSession(Key key, Session s) {
        Session removed = sessions.remove(key);
        if (removed != s) return;
        try {
            removed.close();
        } catch (IOException ignored) {}
    }

    private static final class Key {

        final int writerId;
        final byte addrType;
        final int srcPort;
        final byte[] dstIp;
        final int dstPort;

        Key(
            int writerId,
            byte addrType,
            int srcPort,
            byte[] dstIp,
            int dstPort
        ) {
            this.writerId = writerId;
            this.addrType = addrType;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return (
                writerId == k.writerId &&
                addrType == k.addrType &&
                srcPort == k.srcPort &&
                dstPort == k.dstPort &&
                Arrays.equals(dstIp, k.dstIp)
            );
        }

        @Override
        public int hashCode() {
            int r = Objects.hash(writerId, addrType, srcPort, dstPort);
            r = 31 * r + Arrays.hashCode(dstIp);
            return r;
        }
    }
}
