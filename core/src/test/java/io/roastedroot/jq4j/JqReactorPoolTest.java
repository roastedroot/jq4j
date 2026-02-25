package io.roastedroot.jq4j;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

public class JqReactorPoolTest {

    @Test
    public void basicBorrowAndReturn() throws Exception {
        var pool = JqReactorPool.create(2);

        try (var loan = pool.borrow()) {
            byte[] result = loan.jq()
                    .withInput("{\"a\":1}")
                    .withFilter(".a")
                    .withCompactOutput()
                    .run();
            assertEquals("1\n", new String(result, UTF_8));
        }

        pool.close();
    }

    @Test
    public void instanceIsReusedAcrossBorrows() throws Exception {
        var pool = JqReactorPool.create(1);

        JqReactor firstReactor;
        try (var loan = pool.borrow()) {
            firstReactor = loan.jq().reactor;
            loan.jq()
                    .withInput("{\"x\":42}")
                    .withFilter(".x")
                    .withCompactOutput()
                    .run();
        }

        // Second borrow should return the same reactor
        try (var loan = pool.borrow()) {
            assertEquals(firstReactor, loan.jq().reactor);
            byte[] result = loan.jq()
                    .withInput("{\"y\":99}")
                    .withFilter(".y")
                    .withCompactOutput()
                    .run();
            assertEquals("99\n", new String(result, UTF_8));
        }

        pool.close();
    }

    @Test
    public void concurrentAccess() throws Exception {
        int poolSize = 4;
        int tasks = 16;
        var pool = JqReactorPool.create(poolSize);
        var barrier = new CyclicBarrier(tasks);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(tasks);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < tasks; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                    try (var loan = pool.borrow()) {
                        byte[] result = loan.jq()
                                .withInput("{\"i\":" + idx + "}")
                                .withFilter(".i")
                                .withCompactOutput()
                                .run();
                        String expected = idx + "\n";
                        String actual = new String(result, UTF_8);
                        if (!expected.equals(actual)) {
                            errors.add("task " + idx + ": expected " + expected + " got " + actual);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.add("task " + idx + ": " + e.getMessage());
                } catch (BrokenBarrierException | TimeoutException | RuntimeException e) {
                    errors.add("task " + idx + ": " + e.getMessage());
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
        pool.close();

        assertEquals(List.of(), errors);
    }

    @Test
    public void discardDestroysInstance() throws Exception {
        var pool = JqReactorPool.create(1);

        try (var loan = pool.borrow()) {
            loan.jq()
                    .withInput("{}")
                    .withFilter(".")
                    .run();
            loan.discard();
        }

        // After discard, a new reactor is created for the next borrow
        try (var loan = pool.borrow()) {
            byte[] result = loan.jq()
                    .withInput("{\"ok\":true}")
                    .withFilter(".ok")
                    .withCompactOutput()
                    .run();
            assertEquals("true\n", new String(result, UTF_8));
        }

        pool.close();
    }

    @Test
    public void borrowAfterCloseThrows() throws Exception {
        var pool = JqReactorPool.create(2);
        pool.close();

        assertThrows(IllegalStateException.class, pool::borrow);
    }

    @Test
    public void invalidMaxSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> JqReactorPool.create(0));
        assertThrows(IllegalArgumentException.class, () -> JqReactorPool.create(-1));
    }
}
