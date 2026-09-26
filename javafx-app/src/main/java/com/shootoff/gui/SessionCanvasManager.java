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

package com.shootoff.gui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.config.Configuration;
import com.shootoff.gui.controller.VideoPlayerController;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.session.Event;
import com.shootoff.session.ExerciseFeedMessageEvent;
import com.shootoff.session.ShotEvent;
import com.shootoff.session.TargetAddedEvent;
import com.shootoff.session.TargetMovedEvent;
import com.shootoff.session.TargetRemovedEvent;
import com.shootoff.session.TargetResizedEvent;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.RegionType;
import com.shootoff.targets.Target;
import com.shootoff.targets.TargetRegion;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.io.TargetIO.TargetComponents;

import javafx.fxml.FXMLLoader;
import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * A canvas to display events that were recorded by the session recorder to a
 * user. This class is where events from a session are actually processed to
 * display their outcomes to the user.
 * 
 * @author phrack
 */
public class SessionCanvasManager {
	private static final Logger logger = LoggerFactory.getLogger(SessionCanvasManager.class);

	private final Group canvas;
	private final Label exerciseLabel = new Label();
	private final Map<Event, TargetView> eventToContainer = new HashMap<>();
	private final Map<Event, Point2D> eventToPosition = new HashMap<>();
	private final Map<Event, String> eventToExerciseMessage = new HashMap<>();
	private final Map<Event, Dimension2D> eventToDimension = new HashMap<>();
	private final List<TargetView> targetViews = new ArrayList<>();
	private final List<Target> targets = new ArrayList<>();
	private final Configuration config;

	// Events that could not be applied (e.g. recorded with target index -1 by older versions);
	// undoing them is skipped too so do and undo stay in step
	private final Set<Event> skippedEvents = new HashSet<>();
	private final Set<Event> skippedAnimations = new HashSet<>();
	private boolean warnedAboutSkippedEvents = false;
	private final Map<ShotEvent, DisplayShot> markers = new HashMap<>();

	public SessionCanvasManager(final Group canvas, final Configuration config) {
		this.canvas = canvas;
		this.config = config;
		canvas.getChildren().add(exerciseLabel);
	}

	/**
	 * @return the marker this viewer shows for a shot event, made the first time it is needed
	 */
	public DisplayShot getMarker(ShotEvent event) {
		return markers.computeIfAbsent(event, e -> new DisplayShot(e.getShot(), e.getMarkerRadius()));
	}

	public void doEvent(final Event e) {
		// Re-evaluate from scratch if this event is being redone
		skippedEvents.remove(e);
		skippedAnimations.remove(e);

		switch (e.getType()) {
		case SHOT:
			if (!(e instanceof ShotEvent)) {
				throw new AssertionError("Expected type ShotEvent but got type " + e.getClass().getName());
			}

			final ShotEvent se = (ShotEvent) e;
			addToCanvas(getMarker(se).getMarker());

			if (se.isMalfunction()) {
				getMarker(se).getMarker().setFill(Color.ORANGE);
			} else if (se.isReload()) {
				getMarker(se).getMarker().setFill(Color.LIGHTSKYBLUE);
			}

			getMarker(se).getMarker().setVisible(true);

			if (se.getVideoString().isPresent()) {
				getMarker(se).getMarker().setOnMouseClicked((event) -> {
					if (event.getClickCount() < 2) return;

					final FXMLLoader loader = new FXMLLoader(
							getClass().getClassLoader().getResource("com/shootoff/gui/VideoPlayer.fxml"));
					try {
						loader.load();
					} catch (final IOException ioe) {
						ioe.printStackTrace();
					}

					final Stage videoPlayerStage = new Stage();

					final VideoPlayerController controller = (VideoPlayerController) loader.getController();
					controller.init(se.getVideos());

					videoPlayerStage.setTitle("Video Player");
					videoPlayerStage.setScene(new Scene(loader.getRoot()));
					videoPlayerStage.show();

					config.registerVideoPlayer(controller);
					controller.getStage().setOnCloseRequest((closeEvent) -> {
						config.unregisterVideoPlayer(controller);
					});
				});
			}

			if (se.getTargetIndex().isPresent() && se.getHitRegionIndex().isPresent()) {
				if (canAnimate(se)) {
					animateTarget(se, false);
				} else {
					skippedAnimations.add(e);
					logSkipped(e, "shot animation refers to a target that is not shown");
				}
			}

			break;

		case TARGET_ADDED:
			if (!(e instanceof TargetAddedEvent)) {
				throw new AssertionError("Expected type TargetAddedEvent but got type " + e.getClass().getName());
			}

			addTarget((TargetAddedEvent) e);
			break;

		case TARGET_REMOVED:
			if (!(e instanceof TargetRemovedEvent)) {
				throw new AssertionError("Expected type TargetRemovedEvent but got type " + e.getClass().getName());
			}

			final TargetRemovedEvent tre = (TargetRemovedEvent) e;
			if (!isValidTargetIndex(tre.getTargetIndex())) {
				skip(e, "target index " + tre.getTargetIndex() + " is not shown");
				break;
			}
			eventToContainer.put(e, targetViews.get(tre.getTargetIndex()));
			canvas.getChildren().remove(targetViews.get(tre.getTargetIndex()).getTargetGroup());
			targetViews.remove(tre.getTargetIndex());
			targets.remove(tre.getTargetIndex());
			break;

		case TARGET_RESIZED:
			if (!(e instanceof TargetResizedEvent)) {
				throw new AssertionError("Expected type TargetResizedEvent but got type " + e.getClass().getName());
			}

			final TargetResizedEvent trre = (TargetResizedEvent) e;
			if (!isValidTargetIndex(trre.getTargetIndex())) {
				skip(e, "target index " + trre.getTargetIndex() + " is not shown");
				break;
			}
			eventToDimension.put(e, targetViews.get(trre.getTargetIndex()).getDimension());
			targetViews.get(trre.getTargetIndex()).setDimensions(trre.getNewWidth(), trre.getNewHeight());
			break;

		case TARGET_MOVED:
			if (!(e instanceof TargetMovedEvent)) {
				throw new AssertionError("Expected type TargetMovedEvent but got type " + e.getClass().getName());
			}

			final TargetMovedEvent tme = (TargetMovedEvent) e;
			if (!isValidTargetIndex(tme.getTargetIndex())) {
				skip(e, "target index " + tme.getTargetIndex() + " is not shown");
				break;
			}
			eventToPosition.put(e, targetViews.get(tme.getTargetIndex()).getPosition());
			targetViews.get(tme.getTargetIndex()).setPosition(tme.getNewX(), tme.getNewY());
			break;

		case EXERCISE_FEED_MESSAGE:
			if (!(e instanceof ExerciseFeedMessageEvent)) {
				throw new AssertionError(
						"Expected type ExerciseFeedMessageEvent but got type " + e.getClass().getName());
			}

			final ExerciseFeedMessageEvent pfme = (ExerciseFeedMessageEvent) e;
			eventToExerciseMessage.put(e, exerciseLabel.getText());
			exerciseLabel.setText(pfme.getMessage());
			break;
		}
	}

	public void undoEvent(Event e) {
		if (skippedEvents.remove(e)) return;

		switch (e.getType()) {
		case SHOT:
			if (!(e instanceof ShotEvent)) {
				throw new AssertionError("Expected type ShotEvent but got type " + e.getClass().getName());
			}

			final ShotEvent se = (ShotEvent) e;
			canvas.getChildren().remove(getMarker(se).getMarker());

			if (se.getTargetIndex().isPresent() && se.getHitRegionIndex().isPresent()
					&& !skippedAnimations.remove(e) && canAnimate(se)) {
				animateTarget(se, true);
			}

			break;

		case TARGET_ADDED:
			final TargetView added = eventToContainer.get(e);
			if (added == null) break;
			canvas.getChildren().remove(added.getTargetGroup());
			targetViews.remove(added);
			targets.remove(added);
			break;

		case TARGET_REMOVED:
			if (!(e instanceof TargetRemovedEvent)) {
				throw new AssertionError("Expected type TargetRemovedEvent but got type " + e.getClass().getName());
			}

			final TargetRemovedEvent tre = (TargetRemovedEvent) e;
			final TargetView oldTarget = eventToContainer.get(e);
			if (oldTarget == null) break;
			addToCanvas(oldTarget.getTargetGroup());
			final int restoreIndex = Math.min(tre.getTargetIndex(), targetViews.size());
			targetViews.add(restoreIndex, oldTarget);
			targets.add(Math.min(tre.getTargetIndex(), targets.size()), oldTarget);
			break;

		case TARGET_RESIZED:
			if (!(e instanceof TargetResizedEvent)) {
				throw new AssertionError("Expected type TargetResizedEvent but got type " + e.getClass().getName());
			}

			final TargetResizedEvent trre = (TargetResizedEvent) e;
			final Dimension2D oldDimension = eventToDimension.get(e);
			if (oldDimension == null || !isValidTargetIndex(trre.getTargetIndex())) break;
			targetViews.get(trre.getTargetIndex()).setDimensions(oldDimension.getWidth(), oldDimension.getHeight());
			break;

		case TARGET_MOVED:
			if (!(e instanceof TargetMovedEvent)) {
				throw new AssertionError("Expected type TargetMovedEvent but got type " + e.getClass().getName());
			}

			final TargetMovedEvent tme = (TargetMovedEvent) e;
			final Point2D oldPosition = eventToPosition.get(e);
			if (oldPosition == null || !isValidTargetIndex(tme.getTargetIndex())) break;
			targetViews.get(tme.getTargetIndex()).setPosition(oldPosition.getX(), oldPosition.getY());
			break;

		case EXERCISE_FEED_MESSAGE:
			exerciseLabel.setText(eventToExerciseMessage.get(e));
			break;
		}
	}

	private void animateTarget(ShotEvent se, boolean undo) {
		final TargetView target = targetViews.get(se.getTargetIndex().get());
		final TargetRegion region = (TargetRegion) target.getTargetGroup().getChildren()
				.get(se.getHitRegionIndex().get());

		if (!region.tagExists("command")) return;

		TargetView.parseCommandTag(region, (commands, commandName, args) -> {
			if (!undo) {
				switch (commandName) {
				case "animate":
					target.animate(region, args);
					break;

				case "reverse":
					target.reverseAnimation(region);
					break;
				}
			} else {
				// If we are undoing a reverse animation we should just play it
				// like normal
				if (commands.contains("reverse")) {
					switch (commandName) {
					case "animate":
						target.animate(region, args);
						break;

					case "reverse":
						target.reverseAnimation(region);
						break;
					}
				} else {
					// If we are undoing a non-reverse animation we need to
					// reset the animated region
					if ("animate".equals(commandName)) {
						if (region.getType() == RegionType.IMAGE) {
							((ImageRegion) region).reset();
						} else {
							final Optional<TargetRegion> t = TargetView
									.getTargetRegionByName(new ArrayList<Target>(targetViews), region, args.get(0));
							if (t.isPresent()) ((ImageRegion) t.get()).reset();
						}
					}
				}
			}
		});
	}

	private boolean isValidTargetIndex(int index) {
		return index >= 0 && index < targetViews.size();
	}

	private boolean canAnimate(ShotEvent se) {
		if (!isValidTargetIndex(se.getTargetIndex().get())) return false;

		final int regionIndex = se.getHitRegionIndex().get();
		return regionIndex >= 0
				&& regionIndex < targetViews.get(se.getTargetIndex().get()).getTargetGroup().getChildren().size();
	}

	private void skip(Event e, String reason) {
		skippedEvents.add(e);
		logSkipped(e, reason);
	}

	private void logSkipped(Event e, String reason) {
		if (!warnedAboutSkippedEvents) {
			warnedAboutSkippedEvents = true;
			logger.warn("Some session events could not be applied and were skipped");
		}

		logger.debug("Skipped {} event at {} ms: {}", e.getType(), e.getTimestamp(), reason);
	}

	private void addToCanvas(Node node) {
		if (!canvas.getChildren().contains(node)) canvas.getChildren().add(node);
	}

	private void addTarget(final TargetAddedEvent e) {
		final Optional<TargetComponents> targetComponents = TargetIO.loadTarget(
				new File(System.getProperty("shootoff.home") + File.separator + "targets/" + e.getTargetName()));

		final TargetComponents components;
		if (targetComponents.isPresent()) {
			components = targetComponents.get();
		} else {
			// An empty stand-in keeps this target's slot so later target indexes in the session
			// still line up
			logSkipped(e, "target " + e.getTargetName() + " could not be loaded; using an empty stand-in");
			components = TargetComponents.empty(null);
		}

		addToCanvas(components.getTargetGroup());
		final TargetView targetContainer = new TargetView(components, targets);
		eventToContainer.put(e, targetContainer);
		targetViews.add(targetContainer);
		targets.add(targetContainer);
	}
}