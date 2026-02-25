package io.roastedroot.jq4j;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe pool of {@link JqReactor} instances.
 *
 * <p>Each {@link #borrow()} returns a {@link Loan} that must be
 * {@linkplain Loan#close() closed} (ideally via try-with-resources)
 * to return the reactor to the pool.  The pool caps the number of
 * live instances and blocks callers when the limit is reached.
 *
 * <p>Uses only lock-free data structures and {@link Semaphore}
 * internally — no {@code synchronized} blocks — so it is safe to use
 * with virtual threads (no carrier-thread pinning).
 *
 * <pre>{@code
 * var pool = JqReactorPool.create(4);
 *
 * try (var loan = pool.borrow()) {
 *     byte[] result = loan.builder()
 *             .withInput("{\"a\":1}")
 *             .withFilter(".a")
 *             .withCompactOutput()
 *             .run();
 * }
 *
 * pool.close();
 * }</pre>
 */
public final class JqReactorPool implements AutoCloseable {

    private final ConcurrentLinkedDeque<JqReactor.Builder> idle;
    private final Semaphore permits;
    private final AtomicBoolean closed = new AtomicBoolean();

    private JqReactorPool(int maxSize) {
        this.idle = new ConcurrentLinkedDeque<>();
        this.permits = new Semaphore(maxSize);
    }

    /**
     * Creates a pool that allows at most {@code maxSize} concurrent
     * reactor instances.
     */
    public static JqReactorPool create(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive, got: " + maxSize);
        }
        return new JqReactorPool(maxSize);
    }

    /**
     * Borrows a reactor from the pool, blocking if the pool is at capacity.
     *
     * <p>The returned {@link Loan} <b>must</b> be closed when done (preferably
     * via try-with-resources) to return the reactor to the pool.
     *
     * @throws InterruptedException if the calling thread is interrupted
     *         while waiting for an available permit
     * @throws IllegalStateException if the pool has been closed
     */
    public Loan borrow() throws InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Pool is closed");
        }
        permits.acquire();
        try {
            JqReactor.Builder builder = idle.pollFirst();
            if (builder == null) {
                builder = JqReactor.build();
            }
            return new Loan(this, builder);
        } catch (Throwable t) {
            permits.release();
            throw t;
        }
    }

    private void release(JqReactor.Builder builder) {
        try {
            builder.reset();
            if (!closed.get()) {
                idle.offerFirst(builder);
            } else {
                builder.close();
            }
        } finally {
            permits.release();
        }
    }

    private void discard(JqReactor.Builder builder) {
        try {
            builder.close();
        } finally {
            permits.release();
        }
    }

    /**
     * Closes the pool and all idle reactor instances.
     *
     * <p>Outstanding {@link Loan}s are not forcibly closed — they will
     * be cleaned up as they are returned.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            JqReactor.Builder b;
            while ((b = idle.pollFirst()) != null) {
                b.close();
            }
        }
    }

    /**
     * A loan of a {@link JqReactor.Builder} from the pool.
     *
     * <p>Must be closed to return the reactor to the pool.  If a
     * processing error leaves the reactor in a bad state, call
     * {@link #discard()} instead of {@link #close()} to destroy
     * the instance rather than returning it.
     */
    public static final class Loan implements AutoCloseable {
        private final JqReactorPool pool;
        private JqReactor.Builder builder;

        Loan(JqReactorPool pool, JqReactor.Builder builder) {
            this.pool = pool;
            this.builder = builder;
        }

        /**
         * Returns the borrowed builder for configuring and running a
         * jq filter.
         *
         * @throws IllegalStateException if the loan has already been
         *         closed or discarded
         */
        public JqReactor.Builder jq() {
            if (builder == null) {
                throw new IllegalStateException("Loan already returned");
            }
            return builder;
        }

        /**
         * Returns the reactor to the pool for reuse.
         */
        @Override
        public void close() {
            if (builder != null) {
                pool.release(builder);
                builder = null;
            }
        }

        /**
         * Destroys the reactor instead of returning it to the pool.
         * Use this when a processing error may have left the reactor
         * in a corrupt state.
         */
        public void discard() {
            if (builder != null) {
                pool.discard(builder);
                builder = null;
            }
        }
    }
}
