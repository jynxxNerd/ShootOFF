package com.shootoff.exercise.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.host.ExerciseHostSupport.FileTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.JarTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.MissingTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.Stopped;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.shots.TimerRow;
import com.shootoff.targets.model.Hit;

class TestExerciseHostSupport {
	@TempDir Path home;
	@TempDir Path jar;
	private String previousHome;
	private URLClassLoader resources;
	private final List<String> events = new CopyOnWriteArrayList<>();
	private final List<String> threads = new CopyOnWriteArrayList<>();
	private final AtomicInteger stops = new AtomicInteger();
	private ExerciseHostSupport<String> support;

	private final class Drill implements Exercise {
		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Support drill", "1.0", "ShootOFF tests", "Records its callbacks");
		}

		@Override
		public void start(ExerciseHost host) {
			record("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			record("shot");
		}

		@Override
		public void onReset() {
			record("reset");
		}

		@Override
		public void stop() {
			stops.incrementAndGet();
			record("stop");
		}
	}

	private void record(String event) {
		threads.add(Thread.currentThread().getName());
		events.add(event);
	}

	private static void write(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static byte[] read(Optional<InputStream> stream) throws IOException {
		try (InputStream in = stream.orElseThrow(() -> new AssertionError("nothing opened"))) {
			return in.readAllBytes();
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", home.toString());

		// ShootOFF's folder
		write(home.resolve("targets/IPSC.target"), "<target/>".getBytes());
		write(home.resolve("sounds/beep.wav"), new byte[] { 1 });
		write(home.resolve("sounds/chime.wav"), new byte[] { 2 });

		// The exercise's jar: its own beep, a target of its own, and one that shadows ShootOFF's
		write(jar.resolve("sounds/beep.wav"), new byte[] { 9 });
		write(jar.resolve("targets/box.target"), "<target/>".getBytes());
		write(jar.resolve("targets/IPSC.target"), "<target/>".getBytes());
		write(jar.resolve("backgrounds/black.png"), new byte[] { 7 });
		resources = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null);

		support = new ExerciseHostSupport<>(new Drill(), resources);
	}

	@AfterEach
	void tearDown() throws IOException {
		support.stop();
		resources.close();
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	// Waits for everything queued on the exercise's thread so far
	private void sync() throws InterruptedException {
		final CountDownLatch done = new CountDownLatch(1);
		support.run(done::countDown);
		assertTrue(done.await(5, TimeUnit.SECONDS));
	}

	@Test
	void targetsComeFromTheExercisesJarFirstThenShootoffsFolder() throws IOException {
		assertEquals(new JarTarget("targets/box.target", jar.resolve("targets/box.target").toUri().toURL()),
				support.findTarget("@targets/box.target"));
		// The jar's copy shadows ShootOFF's
		assertEquals("targets/IPSC.target", ((JarTarget) support.findTarget("/targets/IPSC.target")).name());
		// A bare name is looked for in the targets folder
		assertEquals(new FileTarget(home.resolve("targets/IPSC.target").toFile()), support.findTarget("IPSC.target"));
		assertEquals(new MissingTarget("targets/no_such.target"), support.findTarget("targets/no_such.target"));
		// "@" names a jar resource, never a file
		assertEquals(new MissingTarget("@IPSC.target"), support.findTarget("@IPSC.target"));
	}

	@Test
	void soundsComeFromTheExercisesJarFirstThenShootoffsSoundsFolder() throws IOException {
		assertArrayEquals(new byte[] { 9 }, read(support.openSound("sounds/beep.wav")));
		assertArrayEquals(new byte[] { 2 }, read(support.openSound("chime.wav")));
		assertEquals(Optional.empty(), support.openSound("sounds/no_such.wav"));

		final List<String> played = new ArrayList<>();
		support.playSounds(List.of("sounds/beep.wav", "no_such.wav", "chime.wav"), (name, sound, whenDone) -> {
			played.add(name);
			whenDone.run();
		});
		assertEquals(List.of("sounds/beep.wav", "chime.wav"), played);
	}

	@Test
	void resourcesComeOnlyFromTheExercisesJarAndImagesAlsoFromShootoff() throws IOException {
		assertArrayEquals(new byte[] { 9 }, read(support.resource("/sounds/beep.wav")));
		assertEquals(Optional.empty(), support.resource("sounds/chime.wav"));
		assertArrayEquals(new byte[] { 7 }, read(support.openImage("backgrounds/black.png")));
		// A resource on ShootOFF's own class path, as the bundled arena backgrounds are
		assertTrue(support.openImage("com/shootoff/exercise/Exercise.class").isPresent());
		assertEquals(Optional.empty(), support.openImage("backgrounds/no_such.png"));
	}

	@Test
	void stopRunsTheExercisesStopOnceAndHandsBackWhatItLeft() throws Exception {
		support.start(null);
		support.track("IPSC");
		support.track("box");
		support.untrack("box");
		support.pauseShotDetection(true, detecting -> events.add("detecting " + detecting));

		final List<Optional<Stopped<String>>> results = new CopyOnWriteArrayList<>();
		final Thread other = new Thread(() -> results.add(support.stop()));
		other.start();
		results.add(support.stop());
		other.join();

		assertEquals(1, stops.get());
		assertEquals(1, results.stream().filter(Optional::isPresent).count());
		assertEquals(new Stopped<>(List.of("IPSC"), true),
				results.stream().flatMap(Optional::stream).findFirst().get());
		assertEquals(List.of("start", "detecting false", "stop"), events);
		assertTrue(support.isStopped());

		// Later calls change nothing
		assertFalse(support.track("late"));
		support.pauseShotDetection(false, detecting -> events.add("detecting " + detecting));
		support.schedule(() -> events.add("late task"), Duration.ZERO);
		support.run(() -> events.add("late callback"));
		assertEquals(Optional.empty(), support.openSound("chime.wav"));
		Thread.sleep(100);
		assertEquals(List.of("start", "detecting false", "stop"), events);
	}

	@Test
	void anExerciseThatNeverPausedDetectionLeavesItAlone() {
		support.track("IPSC");

		assertEquals(Optional.of(new Stopped<>(List.of("IPSC"), false)), support.stop());
	}

	@Test
	void callbacksRunOnTheExercisesThreadInOrder() throws Exception {
		support.start(null);
		support.deliverShot(new Shot(ShotColor.RED, 1, 2, 3), Optional.empty());
		support.targetsChanged(List::of);
		support.reset();
		support.schedule(() -> record("scheduled"), Duration.ofMillis(10));
		support.run(() -> record("button"));
		sync();
		Thread.sleep(50);

		assertEquals(List.of("start", "shot", "reset", "button", "scheduled"), events);
		assertEquals(List.of("Exercise: Support drill"), threads.stream().distinct().toList());
	}

	@Test
	void aTimerRowReadsLikeARedShotsRow() {
		final Shot previous = new Shot(ShotColor.GREEN, 5, 5, 1000);

		final TimerRow row = ExerciseHostSupport.timerRow(2500, Optional.of(previous));

		assertEquals(String.format("%.2f", 2.5f), row.time());
		assertEquals(String.format("%.2f", 1.5f), row.split());
		assertEquals("red", row.laser());
		assertEquals(-10, row.shot().getX(), 0);
		assertEquals(-10, row.shot().getY(), 0);
		assertEquals("-", ExerciseHostSupport.timerRow(2500, Optional.empty()).split());
	}

	@Test
	void listenersHearOnlyTheUsersChangesOnTheExercisesThread() throws Exception {
		final List<String> heard = new CopyOnWriteArrayList<>();
		support.onParTimeChanged(seconds -> {
			record("par");
			heard.add("par " + seconds);
		});
		support.onDelayedStartChanged(range -> {
			record("delay");
			heard.add("delay " + range.minSeconds() + "-" + range.maxSeconds());
		});

		// The exercise's own values
		support.setParTime(4.0);
		support.setDelayedStart(new DelayRange(5, 8));
		assertEquals(4.0, support.parTime(), 0);
		assertEquals(new DelayRange(5, 8), support.delayedStart());

		// The user's: unchanged values and a minimum above the maximum are ignored
		support.userChangedParTime(4.0);
		support.userChangedParTime(3.5);
		support.userChangedDelayedStart(5, 8);
		support.userChangedDelayedStart(9, 6);
		support.userChangedDelayedStart(2, 6);
		sync();

		assertEquals(List.of("par 3.5", "delay 2-6"), heard);
		assertEquals(3.5, support.parTime(), 0);
		assertEquals(new DelayRange(2, 6), support.delayedStart());
		assertEquals(List.of("Exercise: Support drill"), threads.stream().distinct().toList());
	}

	@Test
	void theDefaultsAreTheSharedControlsDefaults() {
		assertEquals(2.0, support.parTime(), 0);
		assertEquals(new DelayRange(4, 8), support.delayedStart());
	}

	@Test
	void aBackgroundIsRestoredToTheOneFromBeforeTheFirstChange() {
		final SavedBackground<String> saved = new SavedBackground<>();
		final List<Optional<String>> restored = new ArrayList<>();

		saved.restore(restored::add);
		assertEquals(List.of(), restored);

		saved.beforeChange(() -> Optional.of("indoor range"));
		saved.beforeChange(() -> Optional.of("the exercise's first background"));
		saved.restore(restored::add);
		assertEquals(List.of(Optional.of("indoor range")), restored);
	}

	@Test
	void theDataDirectoryIsPerExerciseInTheShootoffHome() {
		final Path directory = support.dataDirectory();

		assertEquals(home.resolve("exercise-data").resolve(Drill.class.getName()), directory);
		assertTrue(Files.isDirectory(directory));
	}
}
