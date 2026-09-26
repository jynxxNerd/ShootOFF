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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.DiagnosticMessage;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.gui.exercise.HostedExercise;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.MirroredTarget;
import com.shootoff.gui.targets.TargetCommands;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.TrainingExerciseBase;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.TargetRef;
import com.shootoff.shots.ShotPipeline;
import com.shootoff.shots.ShotQueue;
import com.shootoff.shots.ShotTimer;
import com.shootoff.targets.Hit;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.RegionType;
import com.shootoff.targets.Target;
import com.shootoff.targets.TargetRegion;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.io.TargetIO.TargetComponents;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.TargetId;
import com.shootoff.targets.model.TargetSet;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Pair;

public class CanvasManager implements CameraView {
	private final Logger logger = LoggerFactory.getLogger(CanvasManager.class);
	private final Group canvasGroup;
	private final Configuration config;
	protected CameraManager cameraManager;

	private final VBox diagnosticsVBox = new VBox();
	private static final int DIAGNOSTIC_POOL_SIZE = 10;
	private static final int DIAGNOSTIC_CHIME_DELAY = 5000; // ms
	private final ScheduledExecutorService diagnosticExecutorService = Executors
			.newScheduledThreadPool(DIAGNOSTIC_POOL_SIZE);
	private final Map<Label, ScheduledFuture<Void>> diagnosticFutures = new HashMap<>();
	private final Image muteImage = new Image(CanvasManager.class.getResourceAsStream("/images/mute.png"));
	private final Image soundImage = new Image(CanvasManager.class.getResourceAsStream("/images/sound.png"));

	private final Resetter resetter;
	private final String cameraName;
	private final ObservableList<ShotEntry> shotEntries;
	private final ImageView background = new ImageView();
	private final List<DisplayShot> shots = Collections.synchronizedList(new ArrayList<DisplayShot>());
	private final List<Target> targets = new ArrayList<>();
	// The model of targets, in the same order
	private final TargetSet targetSet = new TargetSet();

	private ProgressIndicator progress;
	private Optional<ContextMenu> contextMenu = Optional.empty();
	private Optional<TargetView> selectedTarget = Optional.empty();
	private boolean showShots = true;

	// False for a canvas that mirrors another canvas that already records session events,
	// so each target is recorded once
	private boolean recordsSessionEvents = true;
	private final ShotPipeline<DisplayShot> shotPipeline;
	// The v1 hit the pipeline's latest hit test on each thread made, for that shot's region commands
	// and exercise (see toHit)
	private final ThreadLocal<Hit> lastHit = new ThreadLocal<>();

	private static final int MAX_FEED_FPS = 15;
	private static final int MINIMUM_FRAME_DELTA = 1000 / MAX_FEED_FPS; // ms
	private long lastFrameTime = 0;

	protected Optional<ProjectorArenaPane> arenaPane = Optional.empty();
	private Optional<Rect> projectionBounds = Optional.empty();

	public CanvasManager(Group canvasGroup, Resetter resetter, String cameraName,
			ObservableList<ShotEntry> shotEntries) {
		this.canvasGroup = canvasGroup;
		config = Configuration.getConfig();
		this.resetter = resetter;
		this.cameraName = cameraName;
		this.shotEntries = shotEntries;
		shotPipeline = new ShotPipeline<>(new PipelineSurface(), config);

		background.setOnMouseClicked((event) -> {
			toggleTargetSelection(Optional.empty());
		});

		if (Platform.isFxApplicationThread()) {
			progress = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
			progress.setPrefHeight(config.getDisplayHeight());
			progress.setPrefWidth(config.getDisplayWidth());
			canvasGroup.getChildren().add(progress);
			canvasGroup.getChildren().add(diagnosticsVBox);
			diagnosticsVBox.setAlignment(Pos.CENTER);
			diagnosticsVBox.setFillWidth(true);
			diagnosticsVBox.setPrefWidth(config.getDisplayWidth());
		}

		canvasGroup.setOnMouseClicked((event) -> {
			if (contextMenu.isPresent() && contextMenu.get().isShowing()) contextMenu.get().hide();

			if (config.inDebugMode() && event.getButton() == MouseButton.PRIMARY) {
				// Click to shoot
				final ShotColor shotColor;

				if (event.isShiftDown()) {
					shotColor = ShotColor.RED;
				} else if (event.isControlDown()) {
					shotColor = ShotColor.GREEN;
				} else {
					return;
				}

				// Skip the camera manager for injected shots made from the
				// arena tab otherwise they get scaled before the call to
				// addArenaShot when they go through the arena camera feed's
				// canvas manager
				if (this instanceof MirroredCanvasManager) {
					final long shotTimestamp = System.currentTimeMillis();

					addShot(new DisplayShot(new Shot(shotColor, event.getX(), event.getY(), shotTimestamp), config.getMarkerRadius()), false);
				} else {
					cameraManager.injectShot(shotColor, event.getX(), event.getY(), false);
				}
				return;
			} else if (contextMenu.isPresent() && event.getButton() == MouseButton.SECONDARY) {
				contextMenu.get().show(canvasGroup, event.getScreenX(), event.getScreenY());
			}
		});
	}

	@Override
	public void close() {
		diagnosticExecutorService.shutdownNow();
	}

	@Override
	public void setCameraManager(CameraManager cameraManager) {
		this.cameraManager = cameraManager;
	}

	public CameraManager getCameraManager() {
		return cameraManager;
	}

	public boolean addChild(Node c) {
		return getCanvasGroup().getChildren().add(c);
	}

	public boolean removeChild(Node c) {
		return getCanvasGroup().getChildren().remove(c);
	}

	public Label addDiagnosticMessage(final String message, final long chimeDelay, final Color backgroundColor) {
		final Label diagnosticLabel = new Label(message);
		diagnosticLabel.setStyle("-fx-background-color: " + colorToWebCode(backgroundColor));

		final ImageView muteView = new ImageView();
		muteView.setFitHeight(20);
		muteView.setFitWidth(muteView.getFitHeight());
		if (config.isChimeMuted(message)) {
			muteView.setImage(muteImage);
		} else {
			muteView.setImage(soundImage);
		}

		diagnosticLabel.setContentDisplay(ContentDisplay.RIGHT);
		diagnosticLabel.setGraphic(muteView);
		diagnosticLabel.setOnMouseClicked((event) -> {
			if (config.isChimeMuted(message)) {
				muteView.setImage(soundImage);
				config.unmuteMessageChime(message);
			} else {
				muteView.setImage(muteImage);
				config.muteMessageChime(message);
			}

			try {
				config.writeConfigurationFile();
			} catch (final Exception e) {
				logger.error("Failed persisting message's (" + message + ") chime mute settings.", e);
			}
		});

		Platform.runLater(() -> diagnosticsVBox.getChildren().add(diagnosticLabel));

		if (chimeDelay > 0 && !config.isChimeMuted(message) && !diagnosticExecutorService.isShutdown()) {
			@SuppressWarnings("unchecked")
			final ScheduledFuture<Void> chimeFuture = (ScheduledFuture<Void>) diagnosticExecutorService.schedule(
					() -> TrainingExerciseBase.playSound("sounds/chime.wav"), chimeDelay, TimeUnit.MILLISECONDS);
			diagnosticFutures.put(diagnosticLabel, chimeFuture);
		}

		return diagnosticLabel;
	}

	public Label addDiagnosticMessage(String message, Color backgroundColor) {
		return addDiagnosticMessage(message, DIAGNOSTIC_CHIME_DELAY, backgroundColor);
	}

	public void removeDiagnosticMessage(Label diagnosticLabel) {
		if (diagnosticFutures.containsKey(diagnosticLabel)) {
			diagnosticFutures.get(diagnosticLabel).cancel(false);
			diagnosticFutures.remove(diagnosticLabel);
		}

		Platform.runLater(() -> diagnosticsVBox.getChildren().remove(diagnosticLabel));
	}

	@Override
	public DiagnosticMessage addDiagnosticWarning(String message) {
		final Label diagnosticLabel = addDiagnosticMessage(message, Color.RED);
		return () -> removeDiagnosticMessage(diagnosticLabel);
	}

	public static String colorToWebCode(Color color) {
		return String.format("#%02X%02X%02X", (int) (color.getRed() * 255), (int) (color.getGreen() * 255),
				(int) (color.getBlue() * 255));
	}

	private void jdk8094135Warning() {
		Platform.runLater(() -> {
			final Alert cameraAlert = new Alert(AlertType.ERROR);
			cameraAlert.setTitle("Internal Error");
			cameraAlert.setHeaderText("Internal Error -- Likely Too Many false Shots");
			cameraAlert.setResizable(true);
			cameraAlert.setContentText("An internal error due to JDK bug 8094135 occured in Java that will cause all "
					+ "of your shots to be lost. This error is most likely to occur when you are getting a lot of false "
					+ "shots due to poor lighting conditions and/or a poor camera setup. Please put the camera in front "
					+ "of the shooter and turn off any bright lights in front of the camera that are the same height as "
					+ "the shooter. If problems persist you may need to restart ShootOFF.");
			cameraAlert.show();

			shots.clear();
			shotEntries.clear();
		});
	}

	public String getCameraName() {
		return cameraName;
	}

	public void setContextMenu(ContextMenu menu) {
		contextMenu = Optional.of(menu);
	}

	public void setBackgroundFit(double width, double height) {
		background.setFitWidth(width);
		background.setFitHeight(height);
	}

	@Override
	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {
		updateCanvasGroup();

		if (frame == null) {
			background.setX(0);
			background.setY(0);
			background.setImage(null);
			return;
		}

		// Prevent the webcam feed from being refreshed faster than some maximum
		// FPS otherwise we waste CPU cycles converting a frames to show the
		// user and these are cycles we could spend detecting shots. A lower
		// FPS (e.g. ~15) looks perfect fine to a person
		if (System.currentTimeMillis() - lastFrameTime < MINIMUM_FRAME_DELTA)
			return;
		else
			lastFrameTime = System.currentTimeMillis();

		Image img;
		if (projectionBounds.isPresent()) {
			final Rect translatedBounds = translateCameraToCanvas(projectionBounds.get());
			background.setX(translatedBounds.getMinX());
			background.setY(translatedBounds.getMinY());

			img = SwingFXUtils.toFXImage(
					resize(frame, (int) translatedBounds.getWidth(), (int) translatedBounds.getHeight()), null);
		} else {
			background.setX(0);
			background.setY(0);

			img = SwingFXUtils.toFXImage(resize(frame, config.getDisplayWidth(), config.getDisplayHeight()), null);
		}

		Platform.runLater(() -> background.setImage(img));
	}

	public void updateBackground(Image img) {
		updateCanvasGroup();
		background.setX(0);
		background.setY(0);
		Platform.runLater(() -> background.setImage(img));
	}

	private void updateCanvasGroup() {
		if (!canvasGroup.getChildren().contains(background)) {
			if (canvasGroup.getChildren().isEmpty()) {
				canvasGroup.getChildren().add(background);
			} else {
				// Remove the wait spinner and replace it
				// with the background
				Platform.runLater(() -> canvasGroup.getChildren().set(0, background));
			}
		}
	}

	private BufferedImage resize(BufferedImage source, int width, int height) {
		if (source.getWidth() == width && source.getHeight() == height) return source;

		final BufferedImage tmp = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g2 = tmp.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.drawImage(source, 0, 0, width, height, null);
		g2.dispose();

		return tmp;
	}

	public BufferedImage getBufferedImage() {
		final BufferedImage projectedScene = SwingFXUtils.fromFXImage(canvasGroup.getScene().snapshot(null), null);
		return projectedScene;
	}

	public Rect translateCameraToCanvas(Rect bounds) {
		final Rect translated = ArenaGeometry.cameraToCanvas(bounds, feedSize(), displaySize());

		logger.trace("translateCameraToCanvas {} {} {} {} - {} {} {} {}", bounds.getMinX(), bounds.getMinY(),
				bounds.getWidth(), bounds.getHeight(), translated.getMinX(), translated.getMinY(), translated.getWidth(),
				translated.getHeight());

		return translated;
	}

	public Rect translateCanvasToCamera(Rect bounds) {
		final Rect translated = ArenaGeometry.canvasToCamera(bounds, feedSize(), displaySize());

		logger.trace("translateCanvasToCamera {} {} {} {} - {} {} {} {}", bounds.getMinX(), bounds.getMinY(),
				bounds.getWidth(), bounds.getHeight(), translated.getMinX(), translated.getMinY(), translated.getWidth(),
				translated.getHeight());

		return translated;
	}

	private Size feedSize() {
		return new Size(cameraManager.getFeedWidth(), cameraManager.getFeedHeight());
	}

	private Size displaySize() {
		return new Size(config.getDisplayWidth(), config.getDisplayHeight());
	}
	
	/* Takes a point x,y and translates it from an arena canvas to a camera (feed) point.
	 * 
	 * This is hackish because it will break if any of the math for these calculations changes in other places.
	 */
	public Pair<Double, Double> translateCanvasToCameraPoint(double x, double y) {
		if (cameraManager == null)
		{
			logger.error("Called when cameraManager == null");
			return new Pair<Double, Double>(x,y);
		}
		
		if (!cameraManager.getProjectionBounds().isPresent() || !arenaPane.isPresent())
		{
			logger.error("Called when projectionBounds is not available");
			return new Pair<Double, Double>(x,y);
		}
		
		final Point camera = ArenaGeometry.arenaToCamera(x, y, cameraManager.getProjectionBounds().get(),
				new Size(arenaPane.get().getWidth(), arenaPane.get().getHeight()));

		return new Pair<Double, Double>(camera.getX(), camera.getY());
	}
	

	public Group getCanvasGroup() {
		return canvasGroup;
	}

	@Override
	public void clearShots() {
		final Runnable clearShotsAction = () -> {
			for (final DisplayShot shot : shots) {
				canvasGroup.getChildren().remove(shot.getMarker());
			}

			shots.clear();
			try {
				if (shotEntries != null) shotEntries.clear();
			} catch (final NullPointerException npe) {
				logger.error("JDK 8094135 exception", npe);
				jdk8094135Warning();
			}
			if (arenaPane.isPresent() && !(this instanceof MirroredCanvasManager)) arenaPane.get().getCanvasManager().clearShots();
		};

		if (Platform.isFxApplicationThread()) {
			clearShotsAction.run();
		} else {
			Platform.runLater(clearShotsAction);
		}
	}

	@Override
	public void reset() {
		// Reset animations
		for (final Target target : targets) {
			for (final TargetRegion region : target.getRegions()) {
				if (region.getType() == RegionType.IMAGE) ((ImageRegion) region).reset();
			}
		}

		if (arenaPane.isPresent() && !(this instanceof MirroredCanvasManager)) {
			arenaPane.get().getCanvasManager().reset();
		}

		clearShots();
	}

	public void setProjectorArena(ProjectorArenaPane arenaPane, Rect projectionBounds) {
		this.arenaPane = Optional.ofNullable(arenaPane);
		this.projectionBounds = Optional.ofNullable(projectionBounds);
	}

	public void setShowShots(boolean showShots) {
		if (this.showShots != showShots) {
			for (final DisplayShot shot : shots)
				shot.getMarker().setVisible(showShots);
		}

		this.showShots = showShots;
	}

	// A shot's row in the shot timer, after the latest row
	private void appendShotEntry(DisplayShot shot, boolean hadMalfunction, boolean hadReload) {
		final Optional<Shot> lastShot;

		if (shotEntries.isEmpty()) {
			lastShot = Optional.empty();
		} else {
			lastShot = Optional.of(shotEntries.get(shotEntries.size() - 1).getShot());
		}

		final ShotEntry shotEntry = new ShotEntry(shot, lastShot, config.getShotTimerRowColor(), hadMalfunction,
				hadReload);

		try {
			shotEntries.add(shotEntry);
		} catch (final NullPointerException npe) {
			logger.error("JDK 8094135 exception", npe);
			jdk8094135Warning();
		}
	}

	// For testing
	protected List<DisplayShot> getShots() {
		return shots;
	}

	@Override
	public void addShot(ScaledShot shot) {
		addShot(new DisplayShot(shot, config.getMarkerRadius()), false);
	}

	/**
	 * Handles a shot on this canvas, in its coordinates: see {@link ShotPipeline#addShot}.
	 */
	public void addShot(DisplayShot shot, boolean isMirroredShot) {
		shotPipeline.addShot(shot, isMirroredShot);
	}

	public void scaleShotToArenaBounds(ArenaShot shot) {
		if (!projectionBounds.isPresent()) {
			logger.error("scaleShotToArenaBounds called when projectionBounds not present");
			return;
		}
		
		logger.trace("scaleShotToArenaBounds pre x {} y {}", shot.getX(), shot.getY());

		final Point arena = ArenaGeometry.canvasToArena(shot.getX(), shot.getY(), projectionBounds.get(),
				new Size(arenaPane.get().getWidth(), arenaPane.get().getHeight()));
		shot.setArenaCoords(arena.getX(), arena.getY());

		logger.trace("scaleShotToArenaBounds post x {} y {}", shot.getX(), shot.getY());
	}

	/**
	 * Handles a shot a camera feed passed on to this (arena) canvas: see
	 * {@link ShotPipeline#addArenaShot}.
	 */
	public boolean addArenaShot(ArenaShot shot, Optional<String> videoString, boolean isMirroredShot) {
		return shotPipeline.addArenaShot(shot, videoString, isMirroredShot);
	}

	private void drawShot(DisplayShot shot) {
		final Runnable drawShotAction = () -> {
			canvasGroup.getChildren().add(shot.getMarker());
			shot.getMarker().setVisible(showShots);
		};

		if (Platform.isFxApplicationThread()) {
			drawShotAction.run();
		} else {
			Platform.runLater(drawShotAction);
		}
	}

	protected Optional<Hit> checkHit(DisplayShot shot, Optional<String> videoString, boolean isMirroredShot) {
		return shotPipeline.hitTest(shot, videoString, isMirroredShot).flatMap(hit -> toHit(shot, hit));
	}

	/**
	 * Hands a shot to the running exercise. On the shot queue's thread, a v1 exercise hears it on a
	 * thread of its own, as every shot had before the queue: a v1 exercise may block in shotListener
	 * (Shoot Don't Shoot speaks "Bad shoot!" there), and later shots must not wait for it. The shot's
	 * row is already in the shot timer. A v2 exercise's host passes the shot to the exercise's own
	 * thread in order, so it is called here. Called on any other thread (click-to-shoot on the JavaFX
	 * thread, a v1 exercise adding a shot itself), the exercise hears the shot there, as before.
	 */
	private static void notifyExercise(TrainingExercise exercise, Shot shot, Optional<Hit> hit) {
		if (exercise instanceof HostedExercise || !ShotQueue.shared().isQueueThread()) {
			exercise.shotListener(shot, hit);
		} else {
			new Thread(() -> exercise.shotListener(shot, hit), "Shot Listener").start();
		}
	}

	/**
	 * The v1 hit for a hit on this canvas's targets. It is the one {@link PipelineSurface#hitTest} made
	 * when the shot hit the target, so region commands and the exercise get the same hit, even after a
	 * command (e.g. <tt>reset</tt>) or another thread removed the target. Otherwise it is made now, and
	 * empty if the target is gone.
	 */
	private Optional<Hit> toHit(DisplayShot shot, com.shootoff.targets.model.Hit modelHit) {
		final Hit found = lastHit.get();
		if (found != null && found.getModelHit().orElse(null) == modelHit) {
			found.setShot(shot);
			return Optional.of(found);
		}

		return targetFor(modelHit.targetId()).map(target -> {
			final Hit hit = target.toHit(modelHit, shot.getX(), shot.getY());
			hit.setShot(shot);
			return hit;
		});
	}

	// The view of a target in this canvas's set; empty if another thread removed it meanwhile
	private Optional<TargetView> targetFor(TargetId id) {
		for (final Target target : new ArrayList<>(targets)) {
			final TargetView view = (TargetView) target;
			if (view.getPlacedTarget().getId().equals(id)) return Optional.of(view);
		}

		return Optional.empty();
	}

	private void executeRegionCommands(Hit hit, boolean isMirroredShot) {
		if (arenaPane.isPresent())
			TargetView.parseCommandTag(hit.getHitRegion(), new TargetCommands(arenaPane.get().getCanvasManager(), targets, resetter, hit, isMirroredShot));
		else
			TargetView.parseCommandTag(hit.getHitRegion(), new TargetCommands(this, targets, resetter, hit, isMirroredShot));

	}

	// This canvas, as the shot pipeline sees it
	private final class PipelineSurface implements ShotPipeline.Surface<DisplayShot> {
		@Override
		public String name() {
			return cameraName;
		}

		@Override
		public TargetSet targets() {
			return targetSet;
		}

		@Override
		public Optional<com.shootoff.targets.model.Hit> hitTest(double x, double y) {
			// The model checks visible targets topmost (last added) first, so shots register for the
			// top target when targets overlap
			final Optional<com.shootoff.targets.model.Hit> modelHit = HitTester.hit(targetSet, x, y);

			// The v1 hit is made now, while the target is on the canvas, as it always was
			final Optional<Hit> hit = modelHit.flatMap(h -> targetFor(h.targetId()).map(target -> target.toHit(h, x, y)));
			lastHit.set(hit.orElse(null));

			return hit.isPresent() ? modelHit : Optional.empty();
		}

		@Override
		public Optional<ShotTimer<DisplayShot>> shotTimer() {
			if (shotEntries == null) return Optional.empty();

			return Optional.of(CanvasManager.this::appendShotEntry);
		}

		@Override
		public void show(DisplayShot shot) {
			shots.add(shot);
			drawShot(shot);
		}

		@Override
		public int markerRadius(DisplayShot shot) {
			return (int) shot.getMarker().getRadiusX();
		}

		@Override
		public void runRegionCommands(DisplayShot shot, com.shootoff.targets.model.Hit hit, boolean mirrored) {
			toHit(shot, hit).ifPresent(v1Hit -> executeRegionCommands(v1Hit, mirrored));
		}

		@Override
		public boolean deliver(DisplayShot shot, Optional<com.shootoff.targets.model.Hit> hit, boolean arenaShot) {
			final Optional<TrainingExercise> currentExercise = config.getExercise();
			if (currentExercise.isEmpty()) return false;

			// If the canvas is mirrored, use the one without the camera manager for exercises because
			// that is the one for the arena window. If we use the arena tab canvas manager the targets
			// will be copies and will not be the versions of the targets added by exercises.
			if (!arenaShot && CanvasManager.this instanceof MirroredCanvasManager && cameraManager != null) return false;

			notifyExercise(currentExercise.get(), shot, hit.flatMap(h -> toHit(shot, h)));
			return true;
		}

		@Override
		public Optional<ShotPipeline.Arena<DisplayShot>> arena() {
			if (arenaPane.isEmpty() || CanvasManager.this instanceof MirroredCanvasManager) return Optional.empty();

			return Optional.of(new ArenaLink());
		}
	}

	// The projector arena, as this camera feed's canvas passes shots on to it
	private final class ArenaLink implements ShotPipeline.Arena<DisplayShot> {
		@Override
		public Optional<Rect> projection() {
			return projectionBounds;
		}

		@Override
		public Size size() {
			return new Size(arenaPane.get().getWidth(), arenaPane.get().getHeight());
		}

		@Override
		public DisplayShot toArenaShot(DisplayShot shot, Point arenaPoint) {
			final ArenaShot arenaShot = new ArenaShot(shot);
			arenaShot.setArenaCoords(arenaPoint.getX(), arenaPoint.getY());
			return arenaShot;
		}

		@Override
		public boolean addArenaShot(DisplayShot shot, Optional<String> videoString, boolean mirrored) {
			return arenaPane.get().getCanvasManager().addArenaShot((ArenaShot) shot, videoString, mirrored);
		}
	}

	protected Optional<TargetComponents> loadTarget(File targetFile, boolean playAnimations) {
		Optional<TargetComponents> targetComponents;

		if ('@' == targetFile.toString().charAt(0)) {
			if (!config.getPlugin().isPresent()) {
				throw new AssertionError("Loaded target from training exercise resources, but a plugin does not "
						+ "exist for the target.");
			}

			final ClassLoader loader = config.getPlugin().get().getLoader();

			final InputStream resourceTargetStream = loader
					.getResourceAsStream(targetFile.toString().substring(1).replace("\\", "/"));
			if (resourceTargetStream != null) {
				targetComponents = TargetIO.loadTarget(resourceTargetStream, playAnimations, loader);
			} else {
				targetComponents = Optional.empty();
				logger.error("Error adding target from stream created from resource {}",
						targetFile.toString().substring(1).replace("\\", "/"));
			}
		} else {
			targetComponents = TargetIO.loadTarget(targetFile, playAnimations);
		}

		return targetComponents;
	}

	public Optional<Target> addTarget(File targetFile, boolean playAnimations) {
		final Optional<TargetComponents> targetComponents = loadTarget(targetFile, playAnimations);

		if (targetComponents.isPresent()) {
			return Optional.of(addTarget(targetComponents.get().withTargetFile(targetFile), true));
		}

		return Optional.empty();
	}

	public Optional<Target> addTarget(File targetFile) {
		return addTarget(targetFile, true);
	}

	public Target addTarget(TargetComponents components, boolean userDeletable) {
		final TargetView newTarget;

		if (this instanceof MirroredCanvasManager) {
			newTarget = new MirroredTarget(components, config, this, userDeletable);
		} else {
			newTarget = new TargetView(components, this, userDeletable);
		}

		return addTarget(newTarget);
	}

	public Target addTarget(Target newTarget) {
		final Runnable addTargetAction = () -> canvasGroup.getChildren().add(((TargetView) newTarget).getTargetGroup());

		if (Platform.isFxApplicationThread()) {
			addTargetAction.run();
		} else {
			Platform.runLater(addTargetAction);
		}

		((TargetView) newTarget).joinTargetSet(targetSet);
		targets.add(newTarget);

		// Targets without a file (e.g. the manual calibration rectangle) aren't session targets
		if (recordsSessionEvents && config.getSessionRecorder().isPresent() && newTarget.getTargetFile() != null) {
			final SessionRecorder recorder = config.getSessionRecorder().get();
			final TargetRef ref = ((TargetView) newTarget).getTargetRef();
			recorder.recordTargetAdded(cameraName, ref);
			final Point2D position = newTarget.getPosition();
			recorder.recordTargetMoved(cameraName, ref, (int) position.getX(), (int) position.getY());
			final Dimension2D dimension = newTarget.getDimension();
			recorder.recordTargetResized(cameraName, ref, dimension.getWidth(), dimension.getHeight());
		}

		// If this is a mirrored canvas, only alert exercises of target updates
		// from the arena window, not the tab. There is no arena tab if we are in
		// headless mode, thus there is also no MirroredCanvasManager. Always
		// perform target update when the canvas isn't mirrored for that reason.
		if (!(this instanceof MirroredCanvasManager)
				|| ((this instanceof MirroredCanvasManager) && arenaPane.isPresent())) {
			final Optional<TrainingExercise> enabledExercise = config.getExercise();
			if (enabledExercise.isPresent())
				enabledExercise.get().targetUpdate(newTarget, TrainingExercise.TargetChange.ADDED);
		}

		return newTarget;
	}

	public void removeTarget(Target target) {
		final Runnable removeTargetAction = () -> canvasGroup.getChildren()
				.remove(((TargetView) target).getTargetGroup());

		if (Platform.isFxApplicationThread()) {
			removeTargetAction.run();
		} else {
			Platform.runLater(removeTargetAction);
		}

		// A target that isn't registered on this canvas (never added, or already removed by an
		// earlier call) has no removal to record: its view's model may already sit in a fresh
		// private TargetSet, where a TargetRef would misreport a valid index
		if (recordsSessionEvents && config.getSessionRecorder().isPresent() && targets.contains(target)) {
			config.getSessionRecorder().get().recordTargetRemoved(cameraName, ((TargetView) target).getTargetRef());
		}

		targets.remove(target);
		((TargetView) target).leaveTargetSet();

		// If this is a mirrored canvas, only alert exercises of target updates
		// from the arena window, not the tab. There is no arena tab if we are in
		// headless mode, thus there is also no MirroredCanvasManager. Always
		// perform target update when the canvas isn't mirrored for that reason.
		if (!(this instanceof MirroredCanvasManager)
				|| ((this instanceof MirroredCanvasManager) && arenaPane.isPresent())) {
			final Optional<TrainingExercise> enabledExercise = config.getExercise();
			if (enabledExercise.isPresent())
				enabledExercise.get().targetUpdate(target, TrainingExercise.TargetChange.REMOVED);
		}
	}

	public void clearTargets() {
		for (final Target t : new ArrayList<>(targets)) {
			removeTarget(t);
		}
	}

	public void setRecordsSessionEvents(boolean recordsSessionEvents) {
		this.recordsSessionEvents = recordsSessionEvents;
	}

	public boolean recordsSessionEvents() {
		return recordsSessionEvents;
	}

	public List<Target> getTargets() {
		return targets;
	}

	/**
	 * @return the model of this canvas's targets, in the same order as {@link #getTargets()}
	 */
	public TargetSet getTargetSet() {
		return targetSet;
	}

	public void toggleTargetSelection(Optional<TargetView> newSelection) {
		if (selectedTarget.isPresent()) selectedTarget.get().toggleSelected();

		if (newSelection.isPresent()) {
			newSelection.get().toggleSelected();
			selectedTarget = newSelection;
		} else {
			selectedTarget = Optional.empty();
			canvasGroup.requestFocus();
		}
	}
}