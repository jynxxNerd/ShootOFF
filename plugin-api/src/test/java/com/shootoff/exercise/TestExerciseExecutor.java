package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
		// Long enough that a loaded machine can't make it already due before shutdown purges it
		executor.schedule(() -> ran.add("scheduled"), Duration.ofSeconds(10));
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

	@Test
	void submissionRacingWithShutdownNeverRunsAfterLast() throws Exception {
		final List<String> ran = new CopyOnWriteArrayList<>();
		final CountDownLatch go = new CountDownLatch(1);
		final AtomicBoolean stop = new AtomicBoolean();

		// Hammers execute() the moment it's released, so some calls land exactly around the
		// shutdown() call below instead of relying on a sleep to line them up
		final Thread racer = new Thread(() -> {
			try {
				go.await();
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			while (!stop.get()) {
				executor.execute(() -> ran.add("race"));
			}
		});
		racer.start();

		go.countDown();
		assertTrue(executor.shutdown(() -> ran.add("last"), Duration.ofSeconds(5)));
		stop.set(true);
		racer.join(5000);

		final int lastIndex = ran.indexOf("last");
		assertTrue(lastIndex >= 0, "last never ran");
		assertEquals(lastIndex, ran.size() - 1, "something ran after last: " + ran);
	}

	@Test
	void concurrentShutdownCallsRunLastExactlyOnce() throws Exception {
		final int callers = 10;
		final AtomicInteger lastRuns = new AtomicInteger();
		final CountDownLatch ready = new CountDownLatch(callers);
		final CountDownLatch go = new CountDownLatch(1);
		final List<Thread> threads = new ArrayList<>();

		for (int i = 0; i < callers; i++) {
			final Thread t = new Thread(() -> {
				ready.countDown();
				try {
					go.await();
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				executor.shutdown(lastRuns::incrementAndGet, Duration.ofSeconds(5));
			});
			threads.add(t);
			t.start();
		}

		assertTrue(ready.await(5, TimeUnit.SECONDS));
		go.countDown();
		for (final Thread t : threads) t.join(5000);

		assertEquals(1, lastRuns.get());
	}

	@Test
	void repeatingTaskThatCancelsItselfStops() throws Exception {
		final AtomicInteger runs = new AtomicInteger();
		final AtomicReference<Cancellable> self = new AtomicReference<>();
		final CountDownLatch cancelledFromWithin = new CountDownLatch(1);

		// A generous initial delay so self is set before the first run, without a sleep
		final Cancellable repeating = executor.scheduleRepeating(() -> {
			if (runs.incrementAndGet() == 3) {
				self.get().cancel();
				cancelledFromWithin.countDown();
			}
		}, Duration.ofMillis(200), Duration.ofMillis(20));
		self.set(repeating);

		assertTrue(cancelledFromWithin.await(5, TimeUnit.SECONDS));
		sync();
		final int afterCancel = runs.get();
		Thread.sleep(100);

		assertEquals(afterCancel, runs.get());
	}
}
