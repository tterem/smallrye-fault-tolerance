package io.smallrye.faulttolerance.core.bulkhead;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.faulttolerance.exceptions.BulkheadException;
import org.junit.Test;

import io.smallrye.faulttolerance.core.Cancellator;
import io.smallrye.faulttolerance.core.FutureInvocationContext;
import io.smallrye.faulttolerance.core.util.FutureTestThread;
import io.smallrye.faulttolerance.core.util.barrier.Barrier;

/**
 * @author Michal Szynkiewicz, michal.l.szynkiewicz@gmail.com
 */
public class FutureBulkheadTest {
    @Test
    public void shouldLetSingleThrough() throws Exception {
        FutureTestInvocation<String> invocation = FutureTestInvocation
                .immediatelyReturning(() -> completedFuture("shouldLetSingleThrough"));
        BlockingQueue<Runnable> queue = queue(2);
        ExecutorService executor = executor(2, queue);
        FutureBulkhead<String> bulkhead = new FutureBulkhead<>(invocation, "shouldLetSingleThrough", executor, queue, null);
        Future<String> result = bulkhead.apply(new FutureInvocationContext<>(null, null));
        assertThat(result.get()).isEqualTo("shouldLetSingleThrough");
    }

    @Test
    public void shouldLetMaxThrough() throws Exception { // max threads + max queue
        Barrier delayBarrier = Barrier.noninterruptible();
        FutureTestInvocation<String> invocation = FutureTestInvocation.delayed(delayBarrier,
                () -> completedFuture("shouldLetMaxThrough"));
        BlockingQueue<Runnable> queue = queue(3);
        ExecutorService executor = executor(2, queue);
        FutureBulkhead<String> bulkhead = new FutureBulkhead<>(invocation, "shouldLetSingleThrough", executor, queue, null);

        List<FutureTestThread<String>> threads = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));
        }
        delayBarrier.open();
        for (int i = 0; i < 5; i++) {
            assertThat(threads.get(i).await().get()).isEqualTo("shouldLetMaxThrough");
        }
    }

    @Test
    public void shouldRejectMaxPlus1() throws Exception {
        Barrier delayBarrier = Barrier.noninterruptible();
        Barrier startBarrier = Barrier.noninterruptible();

        FutureTestInvocation<String> invocation = FutureTestInvocation.delayed(startBarrier, delayBarrier,
                () -> completedFuture("shouldRejectMaxPlus1"));
        BlockingQueue<Runnable> queue = queue(3);
        ExecutorService executor = executor(2, queue);
        FutureBulkhead<String> bulkhead = new FutureBulkhead<>(invocation, "shouldRejectMaxPlus1", executor, queue, null);

        List<FutureTestThread<String>> threads = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));
        }

        assertThatThrownBy(() -> bulkhead.apply(new FutureInvocationContext<>(null, null)))
                .isInstanceOf(BulkheadException.class);

        delayBarrier.open();
        for (int i = 0; i < 5; i++) {
            assertThat(threads.get(i).await().get()).isEqualTo("shouldRejectMaxPlus1");
        }
    }

    @Test
    public void shouldLetMaxPlus1After1Left() throws Exception {
        Barrier delayBarrier = Barrier.noninterruptible();
        Semaphore letOneInSemaphore = new Semaphore(1);
        Semaphore finishedThreadsCount = new Semaphore(0);

        FutureTestInvocation<String> invocation = FutureTestInvocation.delayed(delayBarrier, () -> {
            letOneInSemaphore.acquire();
            finishedThreadsCount.release();
            return CompletableFuture.completedFuture("shouldLetMaxPlus1After1Left");
        });

        BlockingQueue<Runnable> queue = queue(3);
        ExecutorService executor = executor(2, queue);
        FutureBulkhead<String> bulkhead = new FutureBulkhead<>(invocation, "shouldLetMaxPlus1After1Left", executor, queue,
                null);

        List<FutureTestThread<String>> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));
        }

        delayBarrier.open();
        finishedThreadsCount.acquire();

        FutureTestThread<String> finishedThread = getSingleFinishedThread(threads, 100L);
        assertThat(finishedThread.await().get()).isEqualTo("shouldLetMaxPlus1After1Left");
        threads.remove(finishedThread);

        threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));

        letOneInSemaphore.release(5);
        for (FutureTestThread<String> thread : threads) {
            finishedThreadsCount.acquire();
            assertThat(thread.await().get()).isEqualTo("shouldLetMaxPlus1After1Left");
        }
    }

    @Test
    public void shouldLetMaxPlus1After1Failed() throws Exception {
        RuntimeException error = new RuntimeException("forced");

        Semaphore letOneInSemaphore = new Semaphore(0);
        Semaphore finishedThreadsCount = new Semaphore(0);

        FutureTestInvocation<String> invocation = FutureTestInvocation.immediatelyReturning(() -> {
            letOneInSemaphore.acquire();
            finishedThreadsCount.release();
            throw error;
        });

        BlockingQueue<Runnable> queue = queue(3);
        ExecutorService executor = executor(2, queue);
        FutureBulkhead<String> bulkhead = new FutureBulkhead<>(invocation, "shouldLetMaxPlus1After1Left", executor, queue,
                null);

        List<FutureTestThread<String>> threads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));
        }

        letOneInSemaphore.release();
        finishedThreadsCount.acquire();

        FutureTestThread<String> finishedThread = getSingleFinishedThread(threads, 100L);
        assertThatThrownBy(finishedThread::await).isEqualTo(error);
        threads.remove(finishedThread);

        threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));

        letOneInSemaphore.release(5);
        for (FutureTestThread<String> thread : threads) {
            finishedThreadsCount.acquire();
            assertThatThrownBy(thread::await).isEqualTo(error);
        }
    }

    /*
     * put five elements into the queue,
     * check another one cannot be inserted
     * cancel one,
     * insert another one
     * run the tasks and check results
     */
    @Test
    public void shouldLetMaxPlus1After1Canceled() throws Exception {
        Barrier delayBarrier = Barrier.interruptible();
        CountDownLatch invocationsStarted = new CountDownLatch(2);

        FutureTestInvocation<String> invocation = FutureTestInvocation.immediatelyReturning(() -> {
            invocationsStarted.countDown();
            delayBarrier.await();
            return completedFuture("shouldLetMaxPlus1After1Canceled");
        });

        BlockingQueue<Runnable> queue = queue(3);
        ExecutorService executor = executor(2, queue);
        FutureBulkhead<String> bulkhead = new FutureBulkhead<>(invocation, "shouldLetMaxPlus1After1Canceled", executor, queue,
                null);

        List<FutureTestThread<String>> threads = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));
        }
        invocationsStarted.await();
        Cancellator cancellator = new Cancellator();
        FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(cancellator, null));

        waitUntilQueueSize(bulkhead, 3, 1000);

        FutureTestThread<String> failedThread = FutureTestThread.runOnTestThread(bulkhead,
                new FutureInvocationContext<>(null, null));
        assertThatThrownBy(failedThread::await).isInstanceOf(BulkheadException.class);

        cancellator.cancel(false);

        threads.add(FutureTestThread.runOnTestThread(bulkhead, new FutureInvocationContext<>(null, null)));

        delayBarrier.open();

        for (FutureTestThread<String> thread : threads) {
            assertThat(thread.await().get()).isEqualTo("shouldLetMaxPlus1After1Canceled");
        }
    }

    private void waitUntilQueueSize(FutureBulkhead<String> bulkhead, int size, long timeout) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeout) {
            Thread.sleep(50);
            if (bulkhead.getQueueSize() == size) {
                return;
            }
        }
        fail("queue not filled in in " + timeout + " [ms]");

    }

    private <V> FutureTestThread<V> getSingleFinishedThread(List<FutureTestThread<V>> threads,
            long timeout) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeout) {
            Thread.sleep(50);
            for (FutureTestThread<V> thread : threads) {
                if (thread.isDone()) {
                    return thread;
                }
            }
        }
        fail("No thread finished in " + timeout + " ms");
        return null;
    }

    private ThreadPoolExecutor executor(int size, BlockingQueue<Runnable> queue) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS, queue);
    }

    private LinkedBlockingQueue<Runnable> queue(int capacity) {
        return new LinkedBlockingQueue<>(capacity);
    }
}
