package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class TestShotQueue {
	private final ShotQueue queue = new ShotQueue("Test shots");

	@Test
	void shotsAreHandledOneAtATimeInTheOrderTheyArrive() throws Exception {
		final List<String> events = new CopyOnWriteArrayList<>();
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		final CountDownLatch done = new CountDownLatch(2);

		// Two cameras' threads submit at once; the first shot's handling is slow
		final Thread first = new Thread(() -> queue.submit(() -> {
			threads.add(Thread.currentThread());
			events.add("first starts");
			sleep(100);
			events.add("first ends");
			done.countDown();
		}));
		first.start();
		first.join();
		final Thread second = new Thread(() -> queue.submit(() -> {
			threads.add(Thread.currentThread());
			events.add("second");
			done.countDown();
		}));
		second.start();

		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(List.of("first starts", "first ends", "second"), events);
		assertEquals(1, Set.copyOf(threads).size());
		assertEquals("Test shots", threads.get(0).getName());
	}

	@Test
	void aShotThatFailsDoesNotStopLaterOnes() throws Exception {
		final CountDownLatch handled = new CountDownLatch(1);
		final List<Boolean> onQueue = new CopyOnWriteArrayList<>();

		queue.submit(() -> {
			throw new IllegalStateException("Called endChange before beginChange");
		});
		queue.submit(() -> {
			onQueue.add(queue.isQueueThread());
			handled.countDown();
		});

		assertTrue(handled.await(5, TimeUnit.SECONDS));
		assertEquals(List.of(true), onQueue);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
