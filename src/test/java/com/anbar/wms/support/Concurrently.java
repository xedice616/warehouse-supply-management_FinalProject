package com.anbar.wms.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Starts tasks at the same instant and collects what each one threw (null when it succeeded). */
public final class Concurrently {

    private Concurrently() {
    }

    public static List<Exception> run(int tasks, int threads, IntFunction<Callable<?>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Exception>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                Callable<?> work = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        work.call();
                        return null;
                    } catch (Exception ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            List<Exception> outcomes = new ArrayList<>();
            for (Future<Exception> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
