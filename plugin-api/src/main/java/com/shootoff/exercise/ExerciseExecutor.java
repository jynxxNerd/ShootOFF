/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.exercise;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thread a running exercise lives on. Hosts run every exercise callback here, one at a time and
 * in the order they were submitted, so exercises need no locking. A callback that throws is logged,
 * and later callbacks and repeating tasks carry on.
 */
public final class ExerciseExecutor {
	private static final Logger logger = LoggerFactory.getLogger(ExerciseExecutor.class);
	private static final Cancellable NO_OP = () -> {};

	private final String exerciseName;
	private final ScheduledThreadPoolExecutor executor;
	private final Object lock = new Object();
	private volatile Thread thread;
	// Guarded by lock: once true, no further submission is accepted and shutdown has begun
	private boolean stopping;

	public ExerciseExecutor(String exerciseName) {
		this.exerciseName = exerciseName;
		executor = new ScheduledThreadPoolExecutor(1, runnable -> {
			final Thread t = new Thread(runnable, "Exercise: " + exerciseName);
			t.setDaemon(true);
			thread = t;
			return t;
		});
		executor.setRemoveOnCancelPolicy(true);
		// Shutting down cancels every delayed and repeating task; callbacks already due still run
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
	}

	/**
	 * Runs <tt>callback</tt> on the exercise thread after everything submitted before it. Ignored once
	 * the executor has shut down.
	 */
	public void execute(Runnable callback) {
		schedule(callback, Duration.ZERO);
	}

	public Cancellable schedule(Runnable task, Duration delay) {
		synchronized (lock) {
			if (stopping) return NO_OP;
			try {
				final ScheduledFuture<?> future = executor.schedule(guarded(task), delay.toNanos(),
						TimeUnit.NANOSECONDS);
				return () -> future.cancel(false);
			} catch (final RejectedExecutionException e) {
				logger.debug("Ignoring a task for {}, which has stopped", exerciseName);
				return NO_OP;
			}
		}
	}

	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		synchronized (lock) {
			if (stopping) return NO_OP;
			try {
				final ScheduledFuture<?> future = executor.scheduleAtFixedRate(guarded(task), initialDelay.toNanos(),
						period.toNanos(), TimeUnit.NANOSECONDS);
				return () -> future.cancel(false);
			} catch (final RejectedExecutionException e) {
				logger.debug("Ignoring a repeating task for {}, which has stopped", exerciseName);
				return NO_OP;
			}
		}
	}

	public boolean isExerciseThread() {
		return Thread.currentThread() == thread;
	}

	public boolean isShutdown() {
		return executor.isShutdown();
	}

	/**
	 * Stops the exercise thread: callbacks already submitted run, then <tt>last</tt> (for example
	 * the exercise's <tt>stop()</tt>), and every scheduled task is cancelled. Nothing submitted after
	 * <tt>shutdown</tt> begins runs. Waits up to <tt>timeout</tt> for that, and interrupts the thread
	 * if it takes longer. Called on the exercise thread itself, it can't wait: <tt>last</tt> then runs
	 * when the current callback returns.
	 * <p>
	 * However many threads call this concurrently, <tt>last</tt> runs exactly once, on the exercise
	 * thread.
	 *
	 * @return <tt>true</tt> if the thread finished within <tt>timeout</tt>
	 */
	public boolean shutdown(Runnable last, Duration timeout) {
		synchronized (lock) {
			if (!stopping) {
				stopping = true;
				// Submitting last and shutting down together, under the same lock submissions check,
				// keeps a concurrent execute()/schedule() from ever queuing behind last
				executor.schedule(guarded(last), 0, TimeUnit.NANOSECONDS);
				executor.shutdown();
			}
		}

		if (isExerciseThread()) return false;

		try {
			if (executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) return true;
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		logger.warn("{} didn't stop within {} ms; interrupting it", exerciseName, timeout.toMillis());
		executor.shutdownNow();
		return false;
	}

	private Runnable guarded(Runnable task) {
		return () -> {
			try {
				task.run();
			} catch (final RuntimeException | Error e) {
				logger.error("{} threw an exception", exerciseName, e);
			}
		};
	}
}
