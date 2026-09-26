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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.shootoff.calibration.CalibrationFlow;
import com.shootoff.calibration.CalibrationFlow.Message;
import com.shootoff.camera.CameraCalibrationListener;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.perspective.PerspectiveManager;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.Configuration;
import com.shootoff.gui.exercise.HostedExercise;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.targets.CameraViews;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.io.TargetIO.TargetComponents;
import com.shootoff.targets.model.ResourceResolver;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.util.TimerPool;

import javafx.application.Platform;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.stage.WindowEvent;

/**
 * Calibrates the projector arena in the JavaFX app: the calibration flow itself is core's {@link
 * CalibrationFlow}; this draws it (the pattern, the messages on the calibrating camera's feed and the
 * manual box) and passes the user's actions to it.
 */
public class CalibrationManager implements CameraCalibrationListener {
	private static final int DEFAULT_BOX_DIM = 75;
	private static final int DEFAULT_BOX_POS = 150;

	private final CalibrationConfigurator calibrationConfigurator;
	private final CanvasManager calibratingCanvasManager;
	private final CameraViews cameraViews;
	private final Configuration config;
	private final ExerciseListener exerciseListener;
	private final List<CalibrationListener> calibrationListeners = new ArrayList<>();
	private final ProjectorArenaPane arenaPane;
	private final CalibrationFlow flow;

	private Optional<TargetView> calibrationTarget = Optional.empty();
	private Optional<CameraView> originalView = Optional.empty();
	private final Map<Message, Label> messages = new EnumMap<>(Message.class);

	public CalibrationManager(CalibrationConfigurator calibrationConfigurator, CameraManager calibratingCameraManager,
			ProjectorArenaPane arenaPane, CameraViews cameraViews, AutocalibrationListener autocalibrationListener,
			ExerciseListener exerciseListener) {
		this.calibrationConfigurator = calibrationConfigurator;
		calibratingCanvasManager = (CanvasManager) calibratingCameraManager.getCameraView();
		calibrationListeners.add(arenaPane);
		this.arenaPane = arenaPane;
		this.cameraViews = cameraViews;
		config = Configuration.getConfig();
		this.exerciseListener = exerciseListener;

		flow = new CalibrationFlow(calibratingCameraManager, new FxView(), this::stopProjectorExercise, config,
				TimerPool::schedule, Optional.ofNullable(autocalibrationListener)
						.map(listener -> (Runnable) listener::autocalibrationTimedOut));

		arenaPane.setFeedCanvasManager(calibratingCanvasManager);
		calibratingCameraManager.setCalibrationManager(this);
		calibratingCameraManager.setOnCloseListener(() -> Platform
				.runLater(() -> arenaPane.fireEvent(new WindowEvent(null, WindowEvent.WINDOW_CLOSE_REQUEST))));
	}

	public void addCalibrationListener(CalibrationListener calibrationListener) {
		calibrationListeners.add(calibrationListener);
	}

	public void enableCalibration() {
		flow.start();
	}

	public void stopCalibration() {
		flow.stop();
	}

	@Override
	public void calibrate(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas,
			long delay) {
		flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas);
	}

	public void configureArenaCamera(CalibrationOption option) {
		flow.configureArenaCamera(option);
	}

	public void arenaClosing() {
		flow.arenaClosing();
	}

	public void setFullScreenStatus(boolean fullScreen) {
		flow.setFullScreen(fullScreen);
	}

	public boolean isCalibrating() {
		return flow.isCalibrating();
	}

	@Override
	public void setArenaBackground(String resourceFilename) {
		if (resourceFilename != null) {
			final InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourceFilename);
			final LocatedImage img = new LocatedImage(is, resourceFilename);
			arenaPane.setArenaBackground(img);
		} else {
			arenaPane.setArenaBackground(null);
		}
	}

	// Projector exercises can alter what is on the arena, thereby interfering with calibration
	private Optional<Runnable> stopProjectorExercise() {
		final Optional<TrainingExercise> exercise = config.getExercise();
		if (exercise.isEmpty() || !HostedExercise.isProjectorExercise(exercise.get())) return Optional.empty();

		exerciseListener.setExercise(null);
		return Optional.of(() -> exerciseListener.setProjectorExercise(exercise.get()));
	}

	private void createCalibrationTarget(double x, double y, double width, double height) {
		// Purple (Color.PURPLE) at the default target opacity, as before
		final TargetDefinition definition = new TargetDefinition(Optional.empty(), Map.of(), List.of(
				new com.shootoff.targets.model.RectangleRegion(0, x, y, width, height, "#800080", Map.of())));

		final TargetComponents components;
		try {
			components = TargetIO.buildTarget(definition, ResourceResolver.files(), false);
		} catch (final IOException e) {
			// A rectangle reads no files
			throw new UncheckedIOException(e);
		}

		calibrationTarget = Optional.of((TargetView) calibratingCanvasManager.addTarget(components, false));
		calibrationTarget.get().setKeepInBounds(true);
	}

	// The flow, drawn in the JavaFX app
	private final class FxView implements CalibrationFlow.View {
		@Override
		public boolean isArenaFullScreen() {
			return arenaPane.isFullScreen();
		}

		@Override
		public void setArenaShotsVisible(boolean visible) {
			arenaPane.getCanvasManager().setShowShots(visible);
		}

		@Override
		public void setCalibrating(boolean calibrating) {
			calibrationConfigurator.toggleCalibrating(calibrating);
		}

		@Override
		public void calibrationStarted() {
			for (final CalibrationListener c : calibrationListeners)
				c.startCalibration();
			arenaPane.setCalibrationMessageVisible(false);
		}

		@Override
		public void saveArenaBackground() {
			arenaPane.saveCurrentBackground();
		}

		@Override
		public void restoreArenaBackground() {
			arenaPane.restoreCurrentBackground();
		}

		@Override
		public void showPattern() {
			setArenaBackground("pattern.png");
		}

		@Override
		public void showMessage(Message message) {
			final Label label = switch (message) {
			case FULL_SCREEN_REQUEST -> calibratingCanvasManager
					.addDiagnosticMessage("Please move the arena to your projector and hit F11", Color.YELLOW);
			case AUTO_CALIBRATING -> calibratingCanvasManager.addDiagnosticMessage("Attempting autocalibration", 11000,
					Color.CYAN);
			case MANUAL_REQUEST -> calibratingCanvasManager
					.addDiagnosticMessage("Please manually calibrate the projection region", 20000, Color.ORANGE);
			};

			synchronized (messages) {
				messages.put(message, label);
			}
		}

		@Override
		public void hideMessage(Message message) {
			final Label label;
			synchronized (messages) {
				label = messages.remove(message);
			}

			if (label != null) calibratingCanvasManager.removeDiagnosticMessage(label);
		}

		@Override
		public void showCalibratingFeed() {
			originalView = Optional.of(cameraViews.getSelectedCameraView());
			cameraViews.selectCameraView(calibratingCanvasManager);
		}

		@Override
		public void restoreSelectedView() {
			if (originalView.isPresent()) {
				cameraViews.selectCameraView(originalView.get());
			}
		}

		@Override
		public void showManualBox() {
			if (!calibrationTarget.isPresent()) {
				createCalibrationTarget(DEFAULT_BOX_DIM, DEFAULT_BOX_DIM, DEFAULT_BOX_POS, DEFAULT_BOX_POS);
			} else {
				calibratingCanvasManager.addTarget(calibrationTarget.get());
			}
		}

		@Override
		public Optional<Rect> manualBox() {
			return calibrationTarget.map(target -> FxGeometry.toRect(target.getTargetGroup().getBoundsInParent()));
		}

		@Override
		public void removeManualBox() {
			if (calibrationTarget.isPresent()) {
				calibratingCanvasManager.removeTarget(calibrationTarget.get());
				calibrationTarget = Optional.empty();
			}
		}

		@Override
		public void projectionCalibrated(Rect canvasBounds) {
			calibratingCanvasManager.setProjectorArena(arenaPane, canvasBounds);
		}

		@Override
		public CalibrationOption calibratedFeedBehavior() {
			return calibrationConfigurator.getCalibratedFeedBehavior();
		}

		@Override
		public Size arenaResolution() {
			return FxGeometry.toSize(arenaPane.getArenaStageResolution());
		}

		@Override
		public void calibrated(Optional<PerspectiveManager> perspectiveManager) {
			for (final CalibrationListener c : calibrationListeners)
				c.calibrated(perspectiveManager);
		}

		@Override
		public void runOnUiThread(Runnable action) {
			Platform.runLater(action);
		}
	}
}
