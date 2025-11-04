package org.nikolait.crptapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowRateLimiterTest {

    @Test
    @DisplayName("Конструктор: отрицательные/нулевые значения выбрасывают IllegalArgumentException")
    @Timeout(2)
    void constructor_rejects_nonPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi.SlidingWindowRateLimiter(0L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi.SlidingWindowRateLimiter(-1L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(100), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(100), -5));
    }

    @Test
    @DisplayName("Последовательные acquire до лимита не блокируют")
    @Timeout(2)
    void sequential_upToLimit_noBlock() throws InterruptedException {
        long windowMs = 200;
        int limit = 3;
        CrptApi.SlidingWindowRateLimiter limiter =
                new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        long start = System.nanoTime();
        for (int i = 0; i < limit; i++) {
            limiter.acquire();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 50, "acquire() до лимита не должны заметно блокировать");
    }

    @Test
    @DisplayName("Превышение лимита блокирует до сдвига окна")
    @Timeout(3)
    void exceedLimit_blocks_untilOldestExpires() throws InterruptedException {
        long windowMs = 200;
        int limit = 2;
        CrptApi.SlidingWindowRateLimiter limiter =
                new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        long start = System.nanoTime();
        limiter.acquire();
        limiter.acquire();
        limiter.acquire(); // должно ждать пока истечёт окно первого
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= windowMs - 20, "третье acquire() должно ждать пока истечёт окно первого");
    }

    @Test
    @DisplayName("Скользящее окно: ожидание соответствует сроку самого старого запроса")
    @Timeout(3)
    void rollingWindow_waits_only_until_oldest_moves_out() throws InterruptedException {
        long windowMs = 150;
        int limit = 2;
        CrptApi.SlidingWindowRateLimiter limiter =
                new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        limiter.acquire();
        Thread.sleep(120);
        limiter.acquire();

        long before = System.nanoTime();
        limiter.acquire(); // ждём ~ оставшуюся часть окна первого acquire
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

        assertTrue(waitedMs >= 15);
        assertTrue(waitedMs < 120);
    }

    @Test
    @DisplayName("Конкурентный сценарий: в любую ширину окна не более limit отметок")
    @Timeout(5)
    void concurrency_neverMoreThanLimitInAnyWindow() throws Exception {
        long windowMs = 100;
        int limit = 3;
        int threads = 10;

        CrptApi.SlidingWindowRateLimiter limiter =
                new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Long> stamps = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        limiter.acquire();
                        stamps.add(System.nanoTime());
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(ready.await(1, TimeUnit.SECONDS));
            start.countDown();

            pool.shutdown();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));

            List<Long> sorted = new ArrayList<>(stamps);
            Collections.sort(sorted);

            long windowNs = TimeUnit.MILLISECONDS.toNanos(windowMs);
            for (int i = 0; i < sorted.size(); i++) {
                long left = sorted.get(i);
                int cnt = 1;
                for (int j = i + 1; j < sorted.size(); j++) {
                    if (sorted.get(j) - left < windowNs) {
                        cnt++;
                    } else {
                        break;
                    }
                }
                assertTrue(cnt <= limit, "В окне " + windowMs + "мс зафиксировано " + cnt + " acquire(), limit=" + limit);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Прерывание одного ожидающего не портит состояние: второй успешно занимает слот после сдвига окна")
    @Timeout(4)
    void interrupted_waiter_doesNotCorruptState() throws Exception {
        long windowMs = 250;
        int limit = 1;
        CrptApi.SlidingWindowRateLimiter limiter =
                new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        limiter.acquire(); // заняли единственный слот

        CountDownLatch startedA = new CountDownLatch(1);
        CountDownLatch startedB = new CountDownLatch(1);

        AtomicReference<Throwable> errA = new AtomicReference<>(null);
        AtomicReference<Throwable> errB = new AtomicReference<>(null);
        AtomicReference<Long> bElapsedMs = new AtomicReference<>(null);

        Thread A = new Thread(() -> {
            startedA.countDown();
            try {
                limiter.acquire();
                errA.set(new AssertionError("Ожидали InterruptedException для A"));
            } catch (InterruptedException ignored) {
                // ok
            } catch (Throwable t) {
                errA.set(t);
            }
        }, "waiter-A");

        Thread B = new Thread(() -> {
            startedB.countDown();
            long before = System.nanoTime();
            try {
                limiter.acquire();
                bElapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before));
            } catch (Throwable t) {
                errB.set(t);
            }
        }, "waiter-B");

        A.start();
        B.start();

        assertTrue(startedA.await(500, TimeUnit.MILLISECONDS));
        assertTrue(startedB.await(500, TimeUnit.MILLISECONDS));

        Thread.sleep(50);     // дать потокам встать на ожидание
        A.interrupt();        // прерываем только A

        A.join(1000);
        B.join(2000);

        if (errA.get() != null) fail("A завершился с ошибкой: " + errA.get());
        if (errB.get() != null) fail("B завершился с ошибкой: " + errB.get());

        Long waitedB = bElapsedMs.get();
        assertNotNull(waitedB, "B должен завершить acquire()");
        assertTrue(waitedB >= windowMs - 80 && waitedB <= windowMs + 200,
                "B должен ждать примерно длительность окна первого acquire (waited=" + waitedB + "ms)");

        long t2 = System.nanoTime();
        limiter.acquire();
        long waitedAfterB = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t2);
        assertTrue(waitedAfterB >= windowMs - 80 && waitedAfterB <= windowMs + 200,
                "Сразу после B следующий acquire должен ждать новое окно (waited=" + waitedAfterB + "ms)");
    }


    @Test
    @DisplayName("Один из нескольких ожидающих прерван — остальные продолжают корректно")
    @Timeout(5)
    void multiple_waiters_oneInterruptedOthersProceed() throws Exception {
        long windowMs = 200;
        int limit = 2;
        CrptApi.SlidingWindowRateLimiter limiter =
                new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        limiter.acquire();
        limiter.acquire();

        CountDownLatch started = new CountDownLatch(3);
        AtomicReference<Throwable> err1 = new AtomicReference<>(null);
        AtomicReference<Throwable> err2 = new AtomicReference<>(null);
        AtomicReference<Throwable> err3 = new AtomicReference<>(null);

        Thread w1 = new Thread(() -> {
            started.countDown();
            try {
                limiter.acquire();
                err1.set(new AssertionError("Ожидали InterruptedException для w1"));
            } catch (InterruptedException ignored) {
                // ok
            } catch (Throwable t) {
                err1.set(t);
            }
        }, "w1");

        Thread w2 = new Thread(() -> {
            started.countDown();
            try {
                limiter.acquire();
            } catch (Throwable t) {
                err2.set(t);
            }
        }, "w2");

        Thread w3 = new Thread(() -> {
            started.countDown();
            try {
                limiter.acquire();
            } catch (Throwable t) {
                err3.set(t);
            }
        }, "w3");

        w1.start();
        w2.start();
        w3.start();
        assertTrue(started.await(800, TimeUnit.MILLISECONDS));

        Thread.sleep(30); // дать потокам встать на ожидание
        w1.interrupt();   // прерываем только одного

        w1.join(1000);
        w2.join(1500);
        w3.join(2000);

        if (err1.get() != null) fail("w1 завершился с ошибкой: " + err1.get());
        if (err2.get() != null) fail("w2 завершился с ошибкой: " + err2.get());
        if (err3.get() != null) fail("w3 завершился с ошибкой: " + err3.get());
    }


    @Test
    @DisplayName("Многоразовые acquire не накапливают задержки сверх ожидаемых окон")
    @Timeout(5)
    void repeated_acquire_respects_windows() throws InterruptedException {
        long windowMs = 200;
        int limit = 2;
        long gapMs = 120;   // задержка между #1 и #2
        long deltaMs = 10;  // короткая пауза перед #4

        CrptApi.SlidingWindowRateLimiter limiter =
                new CrptApi.SlidingWindowRateLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        // #1
        limiter.acquire();
        // пауза, чтобы #2 «жил» заметно дольше #1
        Thread.sleep(gapMs);
        // #2
        limiter.acquire();

        // #3 — должен ждать, пока выйдет #1 (~ windowMs - gapMs)
        long t1 = System.nanoTime();
        limiter.acquire();
        long waited1 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);

        // короткая пауза; #2 ещё внутри окна, поэтому #4 тоже должен ждать до его выхода
        Thread.sleep(deltaMs);
        long t2 = System.nanoTime();
        limiter.acquire();
        long waited2 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t2);

        // Ожидание для #3: ~ (windowMs - gapMs) ~= 80ms (допускаем джиттер)
        assertTrue(waited1 >= 40 && waited1 <= 180, "ожидание #3 должно быть порядка окна минус gap");

        // Ожидание для #4: ~ (windowMs - (gapMs + deltaMs)) ~= 70ms
        assertTrue(waited2 >= 35 && waited2 <= 180, "ожидание #4 должно зависеть от выхода второго таймстемпа");
    }

}
