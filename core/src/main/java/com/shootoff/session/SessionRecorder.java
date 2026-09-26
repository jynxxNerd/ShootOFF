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

package com.shootoff.session;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.shootoff.camera.Shot;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.TargetId;

public class SessionRecorder {
	private final long startTime;
	private final String sessionName;
	private final Map<String, List<Event>> events = new HashMap<>();
	private final Map<String, Set<TargetId>> seenTargets = new HashMap<>();
	private final AtomicBoolean ignoreTargetCheck = new AtomicBoolean(false);

	public SessionRecorder() {
		final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");
		sessionName = dateFormat.format(new Date());
		startTime = System.currentTimeMillis();
	}

	public void addEvents(Map<String, List<Event>> events) {
		this.events.putAll(events);
	}

	public Map<String, List<Event>> getEvents() {
		return events;
	}

	public String getSessionName() {
		return sessionName;
	}

	public List<Event> getCameraEvents(String cameraName) {
		if (events.containsKey(cameraName)) {
			return events.get(cameraName);
		} else {
			final List<Event> eventList = new ArrayList<>();
			events.put(cameraName, eventList);
			return eventList;
		}
	}

	// This method ensures we have an add event for a target that is being used,
	// if not the user started recording after adding the target so we should
	// artificially add the target add event then ensure it gets moved and
	// resized to wherever it already is and to however big it already is.
	private void checkTarget(String cameraName, TargetRef target) {
		final Optional<PlacedTarget> placed = target.target();
		if (placed.isEmpty() || placed.get().getDefinition().file().isEmpty()) return;

		if (!seenTargets.computeIfAbsent(cameraName, name -> new HashSet<>()).contains(target.id())) {
			ignoreTargetCheck.set(true);
			recordTargetAdded(cameraName, target);
			final Point p = placed.get().getPosition();
			recordTargetMoved(cameraName, target, (int) p.getX(), (int) p.getY());
			final Size d = placed.get().getSize();
			recordTargetResized(cameraName, target, d.getWidth(), d.getHeight());
			ignoreTargetCheck.set(false);
		}
	}

	/**
	 * @param markerRadius
	 *            the radius of the shot's marker, which session files store
	 */
	public void recordShot(String cameraName, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload,
			Optional<TargetRef> target, Optional<Integer> hitRegionIndex, Optional<String> videoString) {
		Optional<Integer> targetIndex = Optional.empty();
		if (target.isPresent()) {
			targetIndex = Optional.of(target.get().index());
			if (!ignoreTargetCheck.get()) checkTarget(cameraName, target.get());
		}

		final long timestamp = System.currentTimeMillis() - startTime;

		getCameraEvents(cameraName).add(new ShotEvent(cameraName, timestamp, shot, markerRadius, isMalfunction,
				isReload, targetIndex, hitRegionIndex, videoString));
	}

	public void recordTargetAdded(String cameraName, TargetRef target) {
		final Optional<File> file = target.file();
		if (file.isEmpty()) return;

		seenTargets.computeIfAbsent(cameraName, name -> new HashSet<>()).add(target.id());

		String targetName;
		if (file.get().isAbsolute()) {
			targetName = file.get().getPath()
					.replace(System.getProperty("shootoff.home") + File.separator + "targets" + File.separator, "");
		} else {
			targetName = file.get().getPath().replace("targets" + File.separator, "");
		}

		getCameraEvents(cameraName)
				.add(new TargetAddedEvent(cameraName, System.currentTimeMillis() - startTime, targetName));
	}

	public void recordTargetRemoved(String cameraName, TargetRef target) {
		// A target that isn't in its set has no index to replay against, and one
		// without a file (e.g. the manual calibration rectangle) isn't a session target
		if (target.index() < 0 || target.file().isEmpty()) return;

		if (!ignoreTargetCheck.get()) checkTarget(cameraName, target);

		getCameraEvents(cameraName)
				.add(new TargetRemovedEvent(cameraName, System.currentTimeMillis() - startTime, target.index()));
	}

	private void collapseTargetEvents(String cameraName, EventType type, int targetIndex) {
		final ListIterator<Event> it = getCameraEvents(cameraName).listIterator(getCameraEvents(cameraName).size());

		while (it.hasPrevious()) {
			final Event e = it.previous();

			if (e.getType() != EventType.TARGET_RESIZED && e.getType() != EventType.TARGET_MOVED) {
				break;
			}

			if (e.getType() == type) {
				if (type == EventType.TARGET_RESIZED && ((TargetResizedEvent) e).getTargetIndex() == targetIndex) {
					it.remove();
				} else if (type == EventType.TARGET_MOVED && ((TargetMovedEvent) e).getTargetIndex() == targetIndex) {
					it.remove();
				}
			}
		}
	}

	public void recordTargetResized(String cameraName, TargetRef target, double newWidth, double newHeight) {
		// A target that isn't in its set has no index to replay against, and one
		// without a file (e.g. the manual calibration rectangle) isn't a session target
		if (target.index() < 0 || target.file().isEmpty()) return;

		if (!ignoreTargetCheck.get()) checkTarget(cameraName, target);

		// Remove all resize events immediately before this one
		collapseTargetEvents(cameraName, EventType.TARGET_RESIZED, target.index());

		getCameraEvents(cameraName).add(new TargetResizedEvent(cameraName, System.currentTimeMillis() - startTime,
				target.index(), newWidth, newHeight));
	}

	public void recordTargetMoved(String cameraName, TargetRef target, int newX, int newY) {
		// A target that isn't in its set has no index to replay against, and one
		// without a file (e.g. the manual calibration rectangle) isn't a session target
		if (target.index() < 0 || target.file().isEmpty()) return;

		if (!ignoreTargetCheck.get()) checkTarget(cameraName, target);

		// Remove all move events immediately before this one
		collapseTargetEvents(cameraName, EventType.TARGET_MOVED, target.index());

		getCameraEvents(cameraName).add(new TargetMovedEvent(cameraName, System.currentTimeMillis() - startTime,
				target.index(), newX, newY));
	}

	public void recordExerciseFeedMessage(String message) {
		// Add an event for this message to each camera
		for (final String cameraName : seenTargets.keySet()) {
			getCameraEvents(cameraName)
					.add(new ExerciseFeedMessageEvent(cameraName, System.currentTimeMillis() - startTime, message));
		}
	}
}
