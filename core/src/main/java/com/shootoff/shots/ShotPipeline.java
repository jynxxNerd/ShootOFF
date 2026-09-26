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

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.Shot;
import com.shootoff.camera.processors.MalfunctionsProcessor;
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.recorders.ShotRecorder;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.TargetRef;
import com.shootoff.sound.SoundPlayer;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetSet;

/**
 * Everything between a detected shot and the running exercise, for one surface (a camera feed, the
 * arena, or a copy of the arena that mirrors it):
 * <ol>
 * <li>the shot processors (malfunctions, the virtual magazine); a rejected shot is recorded as such and
 * goes no further</li>
 * <li>the laser sound</li>
 * <li>the shot timer row</li>
 * <li>the marker</li>
 * <li>a shot inside the arena's calibrated projection goes on to the arena, in arena coordinates;
 * any other shot is hit-tested against this surface's targets</li>
 * <li>session recording, the hit region's commands, and delivery to the running exercise</li>
 * </ol>
 * A <i>mirrored</i> shot is a copy of a shot another surface handles: it skips the processors, the
 * sound and session recording, and its region commands know it is a copy.
 * <p>
 * The user interface supplies a {@link Surface}, and hands it detected shots on the {@link ShotQueue}.
 *
 * @param <S>
 *            the user interface's shot type (e.g. one that carries its marker)
 */
public final class ShotPipeline<S extends Shot> {
	private static final Logger logger = LoggerFactory.getLogger(ShotPipeline.class);

	/**
	 * What a user interface does with shots on one of its surfaces.
	 */
	public interface Surface<S extends Shot> {
		/**
		 * @return the name this surface's session events are recorded under: the camera's name, or
		 *         "arena"
		 */
		String name();

		/**
		 * @return the surface's targets, which session events refer to
		 */
		TargetSet targets();

		/**
		 * @return the hit on the topmost visible target region at (<tt>x</tt>, <tt>y</tt>) that the
		 *         surface shows
		 */
		Optional<Hit> hitTest(double x, double y);

		/**
		 * @return the shot timer this surface adds rows to, if it has one
		 */
		Optional<ShotTimer<S>> shotTimer();

		/**
		 * Keeps the shot and draws its marker.
		 */
		void show(S shot);

		/**
		 * @return the marker radius session files store for the shot
		 */
		int markerRadius(S shot);

		/**
		 * Carries out the commands of the hit region, which has a command tag.
		 *
		 * @param mirrored
		 *            <tt>true</tt> for a copy of a shot another surface handles
		 */
		void runRegionCommands(S shot, Hit hit, boolean mirrored);

		/**
		 * Hands the shot to the running exercise, if there is one and this surface feeds it.
		 *
		 * @param arenaShot
		 *            <tt>true</tt> for a shot in arena coordinates
		 * @return <tt>true</tt> if an exercise took the shot
		 */
		boolean deliver(S shot, Optional<Hit> hit, boolean arenaShot);

		/**
		 * @return the arena that shots inside its calibrated projection go on to; empty for the arena
		 *         itself, its copies, and a camera feed while there is no arena
		 */
		Optional<Arena<S>> arena();
	}

	/**
	 * The arena, as a camera feed's surface sees it.
	 */
	public interface Arena<S extends Shot> {
		/**
		 * @return the arena's calibrated projection, in the camera feed surface's coordinates; empty
		 *         until the arena is calibrated
		 */
		Optional<Rect> projection();

		/**
		 * @return the arena's current size
		 */
		Size size();

		/**
		 * @return a copy of <tt>shot</tt> at <tt>arenaPoint</tt>, in arena coordinates
		 */
		S toArenaShot(S shot, Point arenaPoint);

		/**
		 * Handles a shot in arena coordinates, normally with {@link ShotPipeline#addArenaShot} on the
		 * arena's surfaces.
		 *
		 * @return <tt>true</tt> if an exercise took the shot
		 */
		boolean addArenaShot(S shot, Optional<String> videoString, boolean mirrored);
	}

	private final Surface<S> surface;
	private final Settings settings;

	// Guarded by this: why the shot before the next row was rejected, shown on that row
	private boolean hadMalfunction = false;
	private boolean hadReload = false;

	public ShotPipeline(Surface<S> surface, Settings settings) {
		this.surface = surface;
		this.settings = settings;
	}

	/**
	 * Handles a shot on this surface, in its coordinates.
	 */
	public void addShot(S shot, boolean mirrored) {
		if (!mirrored) {
			final Optional<ShotProcessor> rejectingProcessor = processShot(shot);
			if (rejectingProcessor.isPresent()) {
				recordRejectedShot(shot, rejectingProcessor.get());
				return;
			} else {
				notifyShot(shot);
			}

			if (settings.useRedLaserSound()
					&& (ShotColor.RED.equals(shot.getColor()) || ShotColor.INFRARED.equals(shot.getColor()))) {
				SoundPlayer.play(settings.getRedLaserSound());
			} else if (settings.useGreenLaserSound() && ShotColor.GREEN.equals(shot.getColor())) {
				SoundPlayer.play(settings.getGreenLaserSound());
			}
		}

		final Optional<ShotTimer<S>> shotTimer = surface.shotTimer();
		if (shotTimer.isPresent()) {
			final boolean malfunction;
			final boolean reload;
			synchronized (this) {
				malfunction = hadMalfunction;
				reload = hadReload;
				hadMalfunction = false;
				hadReload = false;
			}

			// Defense in depth: a row that fails to append (e.g. a user interface's row list
			// changed by two threads at once) must not lose the rest of the shot below
			try {
				shotTimer.get().appendShotRow(shot, malfunction, reload);
			} catch (final RuntimeException e) {
				logger.error("Appending the shot timer row failed; continuing with the shot", e);
			}
		}

		surface.show(shot);

		final Optional<String> videoString = createVideoString(shot);

		final Optional<Arena<S>> arena = surface.arena();
		if (arena.isPresent()) {
			final Optional<Rect> projection = arena.get().projection();

			if (projection.isPresent() && projection.get().contains(shot.getX(), shot.getY())) {
				final Point arenaPoint = ArenaGeometry.canvasToArena(shot.getX(), shot.getY(), projection.get(),
						arena.get().size());
				arena.get().addArenaShot(arena.get().toArenaShot(shot, arenaPoint), videoString, mirrored);

				// The arena handled the shot
				return;
			}
		}

		final Optional<Hit> hit = hitTest(shot, videoString, mirrored);
		runRegionCommands(shot, hit, mirrored);
		surface.deliver(shot, hit, false);
	}

	/**
	 * Handles a shot another surface passed on, in this surface's (the arena's) coordinates.
	 *
	 * @return <tt>true</tt> if an exercise took the shot
	 */
	public boolean addArenaShot(S shot, Optional<String> videoString, boolean mirrored) {
		surface.show(shot);

		final Optional<Hit> hit = hitTest(shot, videoString, mirrored);
		runRegionCommands(shot, hit, mirrored);

		if (!mirrored) return surface.deliver(shot, hit, true);

		return false;
	}

	/**
	 * Hit-tests a shot against this surface's targets and records it in the session being recorded,
	 * unless it is mirrored.
	 */
	public Optional<Hit> hitTest(S shot, Optional<String> videoString, boolean mirrored) {
		final Optional<Hit> hit = surface.hitTest(shot.getX(), shot.getY());

		if (hit.isPresent()) {
			if (settings.inDebugMode()) {
				final Region region = hit.get().region();
				logger.debug("Processing Shot: Found Hit Region For Shot ({}, {}), Type ({}), Tags ({})", shot.getX(),
						shot.getY(), region.getClass().getSimpleName(), region.tags());
			}
		} else {
			logger.debug("Processing Shot: Did Not Find Hit For Shot ({}, {})", shot.getX(), shot.getY());
		}

		final Optional<SessionRecorder> recorder = settings.getSessionRecorder();
		if (!mirrored && recorder.isPresent()) {
			recorder.get().recordShot(surface.name(), shot, surface.markerRadius(shot), false, false,
					hit.map(h -> new TargetRef(surface.targets(), h.targetId())), hit.map(h -> h.region().index()),
					videoString);
		}

		return hit;
	}

	private void runRegionCommands(S shot, Optional<Hit> hit, boolean mirrored) {
		if (hit.isPresent() && hit.get().region().tags().containsKey(Region.TAG_COMMAND)) {
			surface.runRegionCommands(shot, hit.get(), mirrored);
		}
	}

	private Optional<ShotProcessor> processShot(Shot shot) {
		for (final ShotProcessor processor : settings.getShotProcessors()) {
			if (!processor.processShot(shot)) {
				synchronized (this) {
					if (processor instanceof MalfunctionsProcessor) {
						hadMalfunction = true;
					} else if (processor instanceof VirtualMagazineProcessor) {
						hadReload = true;
					}
				}

				logger.debug("Processing Shot: Shot Rejected By {}", processor.getClass().getName());
				return Optional.of(processor);
			}
		}

		return Optional.empty();
	}

	private void recordRejectedShot(S shot, ShotProcessor rejectingProcessor) {
		final Optional<SessionRecorder> recorder = settings.getSessionRecorder();
		if (recorder.isEmpty()) return;

		notifyShot(shot);

		final Optional<String> videoString = createVideoString(shot);

		if (rejectingProcessor instanceof MalfunctionsProcessor) {
			recorder.get().recordShot(surface.name(), shot, surface.markerRadius(shot), true, false, Optional.empty(),
					Optional.empty(), videoString);
		} else if (rejectingProcessor instanceof VirtualMagazineProcessor) {
			recorder.get().recordShot(surface.name(), shot, surface.markerRadius(shot), false, true, Optional.empty(),
					Optional.empty(), videoString);
		}
	}

	// The cameras that save video with each recorded shot
	private void notifyShot(Shot shot) {
		if (settings.getSessionRecorder().isPresent()) {
			for (final CameraManager cm : settings.getRecordingManagers())
				cm.notifyShot(shot);
		}
	}

	// Which videos show the shot, as session files store it
	private Optional<String> createVideoString(Shot shot) {
		if (settings.getSessionRecorder().isPresent() && !settings.getRecordingManagers().isEmpty()) {
			final StringBuilder sb = new StringBuilder();

			for (final CameraManager cm : settings.getRecordingManagers()) {
				final ShotRecorder r = cm.getRevelantRecorder(shot);

				// No recorder when forking the shot video failed
				if (r == null) continue;

				if (sb.length() > 0) {
					sb.append(",");
				}

				sb.append(r.getCameraName().replaceAll(":", "-"));
				sb.append(":");
				sb.append(r.getRelativeVideoFile().getPath());
			}

			return sb.length() == 0 ? Optional.empty() : Optional.of(sb.toString());
		}

		return Optional.empty();
	}
}
