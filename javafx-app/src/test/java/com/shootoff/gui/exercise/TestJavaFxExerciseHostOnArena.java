package com.shootoff.gui.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.Shot;
import com.shootoff.config.Configuration;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.geom.Point;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.MirroredCanvasManager;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExerciseBase;
import com.shootoff.plugins.TrainingExerciseView;
import com.shootoff.plugins.engine.ExerciseLoaders;
import com.shootoff.plugins.engine.Plugin;
import com.shootoff.plugins.engine.PluginJars;
import com.shootoff.targets.Target;
import com.shootoff.targets.model.Hit;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * A v2 projector exercise on the real arena: the projector window's canvas mirrored by the arena
 * tab's, wired as ProjectorSlide wires them, with a target from the exercise's own jar.
 */
class TestJavaFxExerciseHostOnArena {
	private static final String DRILL_CLASS = "com.example.v2.ArenaDrill";
	private static final String DRILL_SOURCE = String.join("\n",
			"package com.example.v2;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.exercise.Exercise;",
			"import com.shootoff.exercise.ExerciseHost;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.targets.model.Hit;",
			"public class ArenaDrill implements Exercise {",
			"  @Override public ExerciseMetadata metadata() {",
			"    return new ExerciseMetadata(\"Arena Drill\", \"1.0\", \"ShootOFF tests\", \"Has a target in its jar\", true);",
			"  }",
			"  @Override public void start(ExerciseHost host) {}",
			"  @Override public void onShot(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void onReset() {}",
			"  @Override public void stop() {}",
			"}");
	private static final String JAR_TARGET = "@targets/box.target";

	@TempDir Path temp;
	private Configuration config;
	private CamerasSupervisor cameras;
	private Plugin plugin;
	private ProjectorArenaPane projectorPane;
	private MirroredCanvasManager projector;
	private MirroredCanvasManager tab;
	private Pane container;
	private VBox buttons;
	private TableView<ShotEntry> table;
	private JavaFxExerciseHost host;

	private static final class Drill implements Exercise {
		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Arena Drill", "1.0", "ShootOFF tests", "Has a target in its jar", true);
		}

		@Override
		public void start(ExerciseHost host) {}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {}

		@Override
		public void onReset() {}

		@Override
		public void stop() {}
	}

	private final class View implements TrainingExerciseView {
		@Override
		public Pane getTrainingExerciseContainer() {
			return container;
		}

		@Override
		public TableView<ShotEntry> getShotEntryTable() {
			return table;
		}

		@Override
		public VBox getButtonsPane() {
			return buttons;
		}

		@Override
		public Optional<CameraView> getArenaView() {
			return Optional.empty();
		}

		@Override
		public List<Target> getTargets() {
			return projector.getTargets();
		}
	}

	private static <T> T onFx(Callable<T> action) throws Exception {
		final CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.call());
			} catch (final Throwable t) {
				result.completeExceptionally(t);
			}
		});
		return result.get(5, TimeUnit.SECONDS);
	}

	// Waits for the scene changes queued so far
	private static void fxSync() throws Exception {
		onFx(() -> null);
	}

	// ShootOFF's classes, as a plugin author compiles against them
	private static String classpath() {
		return String.join(File.pathSeparator, System.getProperty("java.class.path"), location(TrainingExerciseBase.class),
				location(Exercise.class), location(Shot.class));
	}

	private static String location(Class<?> type) {
		return new File(type.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath();
	}

	private JavaFxExerciseHost newHost() {
		// As ShootOFFController hosts a plugin's projector exercise
		return new JavaFxExerciseHost(new Drill(), new ExerciseHostContext(config, cameras, new View(), projector,
				Optional.of(projectorPane), List.of(tab), plugin.getLoader(), new SoundOutput() {
					@Override
					public void play(String name, InputStream sound, Runnable whenDone) {
						whenDone.run();
					}

					@Override
					public void say(String text) {}
				}));
	}

	@BeforeEach
	void setUp() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		config = new Configuration(new String[0]);
		cameras = new CamerasSupervisor(config);

		// The exercise's jar, with a target in it (PluginJars jars everything in its classes folder)
		final Path classes = Files.createDirectories(temp.resolve("drill.jar.build").resolve("classes"));
		Files.createDirectories(classes.resolve("targets"));
		Files.writeString(classes.resolve("targets/box.target"),
				"<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"red\" /></target>");
		final Path jar = PluginJars.build(temp, "drill.jar", Optional.of(PluginJars.descriptor(2, DRILL_CLASS)),
				Map.of(DRILL_CLASS, DRILL_SOURCE), classpath());
		plugin = new Plugin(jar, ExerciseLoaders.all());
		config.setPlugin(plugin);

		onFx(() -> {
			container = new VBox();
			buttons = new VBox(new Button("Reset"));
			table = new TableView<>(FXCollections.observableArrayList());

			// As ProjectorSlide mirrors the projector window's arena on the arena tab
			final ObservableList<ShotEntry> shotTimer = FXCollections.observableArrayList();
			final Stage arenaStage = new Stage();
			projectorPane = new ProjectorArenaPane(arenaStage, null, container, null, shotTimer);
			final ProjectorArenaPane tabPane = new ProjectorArenaPane(arenaStage, null, container, null, shotTimer);
			projectorPane.setArenaPaneMirror(tabPane);
			projector = (MirroredCanvasManager) projectorPane.getCanvasManager();
			tab = (MirroredCanvasManager) tabPane.getCanvasManager();
			projector.setMirroredManager(tab);
			tab.setMirroredManager(projector);
			tab.setRecordsSessionEvents(false);
			return null;
		});

		host = newHost();
		host.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		host.stop();
		config.setPlugin(null);
		plugin.getLoader().close();
	}

	private static TargetView only(CanvasManager canvas) {
		assertEquals(1, canvas.getTargets().size(), "targets on " + canvas.getCameraName());
		return (TargetView) canvas.getTargets().get(0);
	}

	@Test
	void jarTargetIsTheProjectorCopyAndHidesOnTheProjector() throws Exception {
		final TargetHandle box = host.addTarget(JAR_TARGET, 50, 60).get();
		fxSync();

		final TargetView projectorCopy = only(projector);
		final TargetView tabCopy = only(tab);

		box.move(70, 80);
		box.setVisible(false);
		fxSync();

		assertEquals(new Point(70, 80), box.position());
		assertEquals(70, onFx(() -> projectorCopy.getTargetGroup().getLayoutX()), 0);
		assertEquals(70, onFx(() -> tabCopy.getTargetGroup().getLayoutX()), 0);
		// The drill hides its target between rounds: it must go dark in the projector window
		assertFalse(projectorCopy.isVisible());
		assertFalse(onFx(() -> projectorCopy.getTargetGroup().isVisible()));
		// ... and on the arena tab that mirrors it
		assertFalse(tabCopy.isVisible());

		// The exercise's handle, and what targets() hands out, are the projector canvas's copy
		assertEquals(projector.getTargetSet().getTargets().get(0).getId(), box.id());
		assertEquals(List.of(box), host.targets());

		box.setVisible(true);
		fxSync();
		assertTrue(onFx(() -> projectorCopy.getTargetGroup().isVisible()));
		assertTrue(tabCopy.isVisible());

		box.remove();
		fxSync();
		assertEquals(List.of(), projector.getTargets());
		assertEquals(List.of(), tab.getTargets());
		assertFalse(onFx(() -> projector.getCanvasGroup().getChildren().contains(projectorCopy.getTargetGroup())));
		assertFalse(onFx(() -> tab.getCanvasGroup().getChildren().contains(tabCopy.getTargetGroup())));
	}

	// The owner's POI configuration (like calibration) stops the drill and starts it again
	@Test
	void stopRemovesBothCopiesAndARestartedDrillHasOneCopyPerCanvas() throws Exception {
		host.addTarget(JAR_TARGET, 50, 60).get().setVisible(false);
		fxSync();
		final TargetView firstProjectorCopy = only(projector);
		final TargetView firstTabCopy = only(tab);

		host.stop();
		fxSync();
		assertEquals(List.of(), projector.getTargets());
		assertEquals(List.of(), tab.getTargets());
		assertFalse(onFx(() -> projector.getCanvasGroup().getChildren().contains(firstProjectorCopy.getTargetGroup())));
		assertFalse(onFx(() -> tab.getCanvasGroup().getChildren().contains(firstTabCopy.getTargetGroup())));

		host = newHost();
		host.start();
		final TargetHandle box = host.addTarget(JAR_TARGET, 50, 60).get();
		fxSync();

		final TargetView projectorCopy = only(projector);
		only(tab);
		assertSame(projectorCopy, projector.getTargets().get(0));
		assertEquals(projector.getTargetSet().getTargets().get(0).getId(), box.id());
	}
}
