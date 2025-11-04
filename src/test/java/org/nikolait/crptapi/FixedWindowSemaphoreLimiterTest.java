package org.nikolait.crptapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(5)
public class FixedWindowSemaphoreLimiterTest {

    // --- Настройки допуска/робастности ---
    private static final long EPSILON_MS = 15;               // допуск на планировщик/джиттер
    private static final long WAIT_UPPER_SLACK_MS = 400;     // «разумный» верх на любых машинах
    private static final double MIN_FRACTION_AFTER_RESET = 0.60; // минимум доли окна, которую реально ждём при переходе
    private static final double NEAR_FULL_FRACTION = 0.80;       // «почти полное окно» для одиночного лимита

    // ---------- Вспомогательные методы ----------

    private static long nowNanos() {
        return System.nanoTime();
    }

    private static long toMs(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static long elapsedMsSince(long startNanos) {
        return toMs(nowNanos() - startNanos);
    }

    // ===== Reflection: читаем приватное поле без правок прод-кода =====
    private static long getWindowStartNanosViaReflection(CrptApi.FixedWindowSemaphoreLimiter limiter) {
        try {
            Field f = CrptApi.FixedWindowSemaphoreLimiter.class.getDeclaredField("windowStartNanos");
            f.setAccessible(true);
            return f.getLong(limiter);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to reflect windowStartNanos", e);
        }
    }

    /**
     * Остаток времени текущего окна (best-effort, без лока, но нас устроит для грубой оценки).
     */
    private static long remainingWindowMs(CrptApi.FixedWindowSemaphoreLimiter limiter, long windowMs) {
        long start = getWindowStartNanosViaReflection(limiter);
        long elapsed = toMs(nowNanos() - start);
        return Math.max(0, windowMs - elapsed);
    }

    /**
     * Если остаток окна слишком мал, коротко подождать, чтобы «сдвинуть» момент замера.
     */
    private static void ensureNonTrivialRemainder(CrptApi.FixedWindowSemaphoreLimiter limiter, long windowMs) {
        long rem = remainingWindowMs(limiter, windowMs);
        if (rem < 6) {
            sleepSilently(8);
        }
    }

    private static void sleepSilently(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted during sleep");
        }
    }

    /**
     * Корректно останавливает пул потоков.
     */
    private static void shutdownQuietly(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                fail("ExecutorService did not terminate within 2 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Interrupted while awaiting ExecutorService termination");
        }
    }

    /**
     * Поток, который вызывает acquire() и ожидает прерывания.
     */
    private static Thread startInterruptibleWaiter(CrptApi.FixedWindowSemaphoreLimiter limiter,
                                                   CountDownLatch started,
                                                   AtomicLong observedDelayMs) {
        Thread waiter = new Thread(() -> {
            started.countDown();
            long start = nowNanos();
            try {
                limiter.acquire();
                fail("Expected InterruptedException");
            } catch (InterruptedException e) {
                observedDelayMs.set(elapsedMsSince(start));
                Thread.currentThread().interrupt();
            }
        }, "limiter-waiter");
        waiter.start();
        return waiter;
    }

    // ---------- Тесты ----------

    @Test
    @DisplayName("До лимита в пределах окна — не блокирует заметно")
    void allowsUpToLimitWithoutBlocking() throws Exception {
        long windowMs = 300; // чуть длиннее окно — стабильнее на разных машинах
        int limit = 3;

        var limiter = new CrptApi.FixedWindowSemaphoreLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        long t0 = nowNanos();
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();
        long elapsedMs = elapsedMsSince(t0);

        // «не блокирует заметно»: суммарно сильно меньше половины окна
        assertTrue(elapsedMs < windowMs * 0.4,
                "Acquires should not significantly block before reaching the limit; elapsed=" + elapsedMs + "ms");
    }

    @Test
    @DisplayName("Сверх лимита — ждём как минимум значимую долю текущего окна")
    void blocksWhenExceedingUntilNextWindow() throws Exception {
        long windowMs = 200;
        int limit = 2;

        var limiter = new CrptApi.FixedWindowSemaphoreLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        // Съели лимит текущего окна
        limiter.acquire();
        limiter.acquire();

        // Стабилизируем момент замера (чтобы не попасть на последние миллисекунды окна)
        ensureNonTrivialRemainder(limiter, windowMs);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Long> future = pool.submit(() -> {
                long start = nowNanos();
                limiter.acquire(); // должен дождаться ресета окна
                return elapsedMsSince(start);
            });

            long waitedMs = future.get(2, TimeUnit.SECONDS);

            // Должны ждать как минимум ~60% окна (в среднем будет ближе к 100%)
            assertTrue(waitedMs + EPSILON_MS >= (long) (windowMs * MIN_FRACTION_AFTER_RESET),
                    "Third acquire should wait a meaningful fraction of window; waited=" + waitedMs + "ms");
            // И точно не бесконечно дольше окна
            assertTrue(waitedMs <= windowMs + WAIT_UPPER_SLACK_MS,
                    "Wait should not exceed a reasonable bound; waited=" + waitedMs + "ms");
        } finally {
            shutdownQuietly(pool);
        }
    }

    @Test
    @DisplayName("Несколько ожидающих: первые limit — в первое новое окно, следующий — в следующее")
    void multipleWaitersSpanOverTwoWindows() throws Exception {
        long windowMs = 180;
        int limit = 2;

        var limiter = new CrptApi.FixedWindowSemaphoreLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        // Съели лимит текущего окна
        limiter.acquire();
        limiter.acquire();

        int waiters = 3;
        ExecutorService pool = Executors.newFixedThreadPool(waiters);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Long>> results = new ArrayList<>(waiters);
        long t0 = nowNanos();

        for (int i = 0; i < waiters; i++) {
            results.add(pool.submit(() -> {
                startLatch.await();
                limiter.acquire();
                return elapsedMsSince(t0);
            }));
        }

        // Запуск всех ожидающих
        ensureNonTrivialRemainder(limiter, windowMs);
        startLatch.countDown();

        try {
            List<Long> waited = new ArrayList<>(waiters);
            for (Future<Long> f : results) {
                waited.add(f.get(3, TimeUnit.SECONDS));
            }
            waited.sort(Long::compareTo);

            long first = waited.get(0);
            long second = waited.get(1);
            long third = waited.get(2);

            // Первые двое должны пройти в первое новое окно: ждать не меньше ~60% окна
            assertTrue(first + EPSILON_MS >= (long) (windowMs * MIN_FRACTION_AFTER_RESET),
                    "First waiter should pass after the first window reset; first=" + first + "ms");
            assertTrue(second + EPSILON_MS >= (long) (windowMs * MIN_FRACTION_AFTER_RESET),
                    "Second waiter should pass after the first window reset; second=" + second + "ms");

            assertTrue(first <= windowMs + WAIT_UPPER_SLACK_MS,
                    "First waiter should be within a reasonable bound; first=" + first + "ms");
            assertTrue(second <= windowMs + WAIT_UPPER_SLACK_MS,
                    "Second waiter should be within a reasonable bound; second=" + second + "ms");

            // Третий — уже во второе новое окно: ждёт ≳ 1.6 * window (реально ближе к ~2.0 * window)
            assertTrue(third + EPSILON_MS >= (long) (windowMs * (1.0 + MIN_FRACTION_AFTER_RESET)),
                    "Third waiter should pass after the second window reset; third=" + third + "ms");
            assertTrue(third <= 2 * windowMs + WAIT_UPPER_SLACK_MS,
                    "Third waiter should be within a reasonable bound; third=" + third + "ms");
        } finally {
            shutdownQuietly(pool);
        }
    }

    @Test
    @DisplayName("Одиночный лимит: после второго acquire ждём почти полное окно; затем снова почти полное")
    void blocksThenProceedsAndNoPermitLeak() throws Exception {
        long windowMs = 220;
        int limit = 1;

        var limiter = new CrptApi.FixedWindowSemaphoreLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        // 1) Съели пермит
        limiter.acquire();

        // 2) Второй вызов должен ждать почти полное окно (≥ ~80%)
        ensureNonTrivialRemainder(limiter, windowMs);
        long t2 = nowNanos();
        limiter.acquire();
        long waited2Ms = elapsedMsSince(t2);
        assertTrue(waited2Ms + EPSILON_MS >= (long) (windowMs * NEAR_FULL_FRACTION),
                "Second acquire should wait for ~near-full window; waited=" + waited2Ms + "ms");
        assertTrue(waited2Ms <= windowMs + WAIT_UPPER_SLACK_MS,
                "Second acquire wait upper bound; waited=" + waited2Ms + "ms");

        // 3) Третий вызов снова должен ждать почти полное окно
        long t3 = nowNanos();
        limiter.acquire();
        long waited3Ms = elapsedMsSince(t3);
        assertTrue(waited3Ms + EPSILON_MS >= (long) (windowMs * NEAR_FULL_FRACTION),
                "Third acquire should also wait ~near-full window; waited=" + waited3Ms + "ms");
        assertTrue(waited3Ms <= windowMs + WAIT_UPPER_SLACK_MS,
                "Third acquire wait upper bound; waited=" + waited3Ms + "ms");
    }

    @Test
    @DisplayName("Ожидание прерываемо — InterruptedException; следующий acquire ждёт значимую долю окна")
    void interruptibleWaitDoesNotLeakPermits() throws Exception {
        long windowMs = 200;
        int limit = 1;

        var limiter = new CrptApi.FixedWindowSemaphoreLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        // Съели пермит текущего окна
        limiter.acquire();

        CountDownLatch started = new CountDownLatch(1);
        AtomicLong observedDelayMs = new AtomicLong(-1);

        Thread waiter = startInterruptibleWaiter(limiter, started, observedDelayMs);
        assertTrue(started.await(1, TimeUnit.SECONDS), "Waiting thread did not start");

        // Даём чуть подождать и прерываем
        sleepSilently(35);
        waiter.interrupt();
        waiter.join(1500);

        long interruptedWaitMs = observedDelayMs.get();
        assertTrue(interruptedWaitMs >= 0, "Should observe non-negative delay before interrupt");

        // Следующий acquire должен ждать непренебрежимую долю окна
        ensureNonTrivialRemainder(limiter, windowMs);
        long t = nowNanos();
        limiter.acquire();
        long waitedMs = elapsedMsSince(t);

        assertTrue(waitedMs + EPSILON_MS >= (long) (windowMs * MIN_FRACTION_AFTER_RESET),
                "Next acquire() should wait a meaningful fraction of window; waited=" + waitedMs + "ms");
        assertTrue(waitedMs <= windowMs + WAIT_UPPER_SLACK_MS,
                "Next acquire() wait should be within a reasonable bound; waited=" + waitedMs + "ms");
    }

    @Test
    @DisplayName("После простоя больше длины окна — проход практически мгновенный")
    void idleRolloverPassesImmediately() throws Exception {
        long windowMs = 180;
        int limit = 1;
        var limiter = new CrptApi.FixedWindowSemaphoreLimiter(TimeUnit.MILLISECONDS.toNanos(windowMs), limit);

        limiter.acquire(); // съели пермит
        Thread.sleep(windowMs + 40); // гарантированно «пережили» окно

        long t = System.nanoTime();
        limiter.acquire(); // не должно ждать ощутимо
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t);
        assertTrue(waitedMs <= EPSILON_MS + 5, "Should pass immediately after idle window");
    }
}
