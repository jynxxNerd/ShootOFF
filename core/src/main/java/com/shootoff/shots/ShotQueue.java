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

package com.shootoff.shots;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thread detected shots are handled on, in the order they were detected. Handling a shot
 * (shot processors, its shot timer row, hit tests, region commands, the exercise's callback) runs here
 * instead of on the camera's thread, so slow handling never delays detection. It runs one shot at a
 * time, so two shots from the same camera frame can't change the shot timer at once.
 */
public final class ShotQueue {
	private static final Logger logger = LoggerFactory.getLogger(ShotQueue.class);
	private static final ShotQueue SHARED = new ShotQueue("Shot Notifier");

	private final ExecutorService executor;
	private volatile Thread thread;

	public ShotQueue(String threadName) {
		executor = Executors.newSingleThreadExecutor(runnable -> {
			final Thread t = new Thread(runnable, threadName);
			t.setDaemon(true);
			thread = t;
			return t;
		});
	}

	/**
	 * @return the queue every camera's shots go through
	 */
	public static ShotQueue shared() {
		return SHARED;
	}

	/**
	 * Handles <tt>work</tt> after everything submitted before it. If it throws, the exception is
	 * logged and later work still runs.
	 */
	public void submit(Runnable work) {
		executor.execute(() -> {
			try {
				work.run();
			} catch (final RuntimeException e) {
				logger.error("Handling a shot failed", e);
			}
		});
	}

	/**
	 * @return <tt>true</tt> when called while handling a shot
	 */
	public boolean isQueueThread() {
		return Thread.currentThread() == thread;
	}
}
