package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestExerciseExecutor {
	private final ExerciseExecutor executor = new ExerciseExecutor("Test drill");

	@AfterEach
	void tearDown() {
		executor.shutdown(() -> {}, Duration.ofSeconds(1));
	}

	// Runs on the exercise thread after everything submitted so far
	private void sync() throws InterruptedException {
		final CountDownLatch done = new CountDownLatch(1);
		executor.execute(done::countDown);
		assertTrue(done.await(5, TimeUnit.SECONDS));
	}

	@Test
	void callbacksRunInOrderOnOneNamedThread() throws Exception {
		final List<Integer> order = new CopyOnWriteArrayList<>();
		final List<String> threads = new CopyOnWriteArrayList<>();

		for (int i = 0; i < 20; i++) {
			final int n = i;
			// Each from a new thread: the order of submission is kept, and all run on one thread
			final Thread submitter = new Thread(() -> executor.execute(() -> {
				order.add(n);
				threads.add(Thread.currentThread().getName());
			}));
			submitter.start();
			submitter.join();
		}
		sync();

		assertEquals(IntStream.range(0, 20).boxed().toList(), order);
		assertEquals(Set.of("Exercise: Test drill"), Set.copyOf(threads));
	}

	@Test
	void scheduledTaskRunsAfterItsDelayOnTheExerciseThread() throws Exception {
		final long start = System.nanoTime();
		final CompletableFuture<Boolean> onExerciseThread = new CompletableFuture<>();

		executor.schedule(() -> onExerciseThread.complete(executor.isExerciseThread()), Duration.ofMillis(100));

		assertTrue(onExerciseThread.get(5, TimeUnit.SECONDS));
		assertTrue(System.nanoTime() - start >= Duration.ofMillis(100).toNanos());
		assertFalse(executor.isExerciseThread());
	}

	@Test
	void cancelledTaskNeverRuns() throws Exception {
		final AtomicBoolean ran = new AtomicBoolean();

		executor.schedule(() -> ran.set(true), Duration.ofMillis(100)).cancel();
		Thread.sleep(300);

		assertFalse(ran.get());
	}

	@Test
	void repeatingTaskRepeatsUntilCancelled() throws Exception {
		final AtomicInteger runs = new AtomicInteger();
		final CountDownLatch three = new CountDownLatch(3);

		final Cancellable repeating = executor.scheduleRepeating(() -> {
			runs.incrementAndGet();
			three.countDown();
		}, Duration.ZERO, Duration.ofMillis(20));
		assertTrue(three.await(5, TimeUnit.SECONDS));

		repeating.cancel();
		sync();
		final int afterCancel = runs.get();
		Thread.sleep(100);

		assertEquals(afterCancel, runs.get());
	}

	@Test
	void aThrowingCallbackDoesNotStopLaterOnesOrItsRepeats() throws Exception {
		executor.execute(() -> {
			throw new IllegalStateException("A bug in an exercise");
		});

		final CountDownLatch repeats = new CountDownLatch(3);
		executor.scheduleRepeating(() -> {
			repeats.countDown();
			throw new IllegalStateException("A bug in a repeating task");
		}, Duration.ZERO, Duration.ofMillis(10));

		assertTrue(repeats.await(5, TimeUnit.SECONDS));
		sync();
	}

	@Test
	void shutdownRunsQueuedCallbacksThenTheLastOneAndCancelsScheduledTasks() throws Exception {
		final List<String> ran = new CopyOnWriteArrayList<>();
		final CountDownLatch release = new CountDownLatch(1);

		// Keep the exercise thread busy so the next callback queues behind it
		executor.execute(() -> {
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		executor.execute(() -> ran.add("queued"));
		executor.schedule(() -> ran.add("scheduled"), Duration.ofMillis(200));
		new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			release.countDown();
		}).start();

		assertTrue(executor.shutdown(() -> ran.add("last"), Duration.ofSeconds(5)));
		Thread.sleep(300);

		assertEquals(List.of("queued", "last"), ran);
		assertTrue(executor.isShutdown());
	}

	@Test
	void callsAfterShutdownAreIgnored() throws Exception {
		assertTrue(executor.shutdown(() -> {}, Duration.ofSeconds(1)));
		final AtomicBoolean ran = new AtomicBoolean();

		executor.execute(() -> ran.set(true));
		executor.schedule(() -> ran.set(true), Duration.ZERO);
		executor.scheduleRepeating(() -> ran.set(true), Duration.ZERO, Duration.ofMillis(10)).cancel();
		Thread.sleep(100);

		assertFalse(ran.get());
	}

	@Test
	void shutdownFromTheExerciseThreadDoesNotWait() throws Exception {
		final CompletableFuture<Boolean> result = new CompletableFuture<>();
		final CountDownLatch last = new CountDownLatch(1);

		executor.execute(() -> result.complete(executor.shutdown(last::countDown, Duration.ofSeconds(30))));

		// It returned at once instead of waiting for itself, and the last callback still ran after it
		assertFalse(result.get(5, TimeUnit.SECONDS));
		assertTrue(last.await(5, TimeUnit.SECONDS));
	}
}
