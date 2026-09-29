package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetSet;

class TestFakeExerciseHost {
	private static final TextStyle STYLE = new TextStyle(20, "white", "transparent");

	@TempDir Path temp;
	private String previousHome;
	private FakeExerciseHost host;

	@BeforeEach
	void setUp() {
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		host = new FakeExerciseHost(new Size(800, 600), true, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	private static final class Recorder implements Exercise {
		final List<String> events = new ArrayList<>();

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Recorder", "1.0", "ShootOFF tests", "Records its callbacks", true);
		}

		@Override
		public void start(ExerciseHost host) {
			events.add("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			events.add("shot " + hit.map(h -> h.region().index()).orElse(-1));
		}

		@Override
		public void onReset() {
			events.add("reset");
		}

		@Override
		public void stop() {
			events.add("stop");
		}
	}

	@Test
	void advanceRunsDueTasksInTimeOrder() {
		final List<String> ran = new ArrayList<>();
		final long start = host.currentTimeMillis();

		host.schedule(() -> ran.add("b"), Duration.ofSeconds(2));
		host.schedule(() -> ran.add("a"), Duration.ofSeconds(1));
		host.schedule(() -> ran.add("c"), Duration.ofSeconds(2));
		host.schedule(() -> host.schedule(() -> ran.add("d"), Duration.ofMillis(500)), Duration.ofSeconds(1));

		host.advance(Duration.ofMillis(1500));
		assertEquals(List.of("a", "d"), ran);
		assertEquals(start + 1500, host.currentTimeMillis());

		host.advance(Duration.ofMillis(500));
		assertEquals(List.of("a", "d", "b", "c"), ran);
		assertEquals(FakeExerciseHost.START_TIME + 2000, host.currentTimeMillis());
	}

	@Test
	void repeatingTaskRunsEveryPeriodUntilCancelled() {
		final List<Long> times = new ArrayList<>();
		final long start = host.currentTimeMillis();

		final Cancellable repeating = host.scheduleRepeating(() -> times.add(host.currentTimeMillis() - start),
				Duration.ofMillis(100), Duration.ofMillis(250));
		host.advance(Duration.ofMillis(700));
		assertEquals(List.of(100L, 350L, 600L), times);

		repeating.cancel();
		host.advance(Duration.ofSeconds(1));
		assertEquals(3, times.size());
		assertEquals(0, host.pendingTasks());
	}

	@Test
	void cancelledTaskDoesNotRun() {
		final List<String> ran = new ArrayList<>();

		host.schedule(() -> ran.add("cancelled"), Duration.ofSeconds(1)).cancel();
		host.schedule(() -> ran.add("kept"), Duration.ofSeconds(1));
		host.advance(Duration.ofSeconds(1));

		assertEquals(List.of("kept"), ran);
	}

	@Test
	void addTargetLoadsTheDefinitionFromFilesOrTheExercisesJar() throws IOException, TargetFormatException {
		final TargetHandle ipsc = host.addTarget("targets/IPSC.target", 10, 20).get();
		assertEquals(new Point(10, 20), ipsc.position());
		assertEquals(TargetDefinitions.load(Paths.get("targets/IPSC.target")).regions(), ipsc.definition().regions());

		// A bare name is looked up in the targets folder
		assertTrue(host.addTarget("ISSF.target", 0, 0).isPresent());

		// "@" names come from the exercise's jar
		Files.createDirectories(temp.resolve("jar/targets"));
		Files.writeString(temp.resolve("jar/targets/box.target"),
				"<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"red\" /></target>");
		try (URLClassLoader jar = new URLClassLoader(new URL[] { temp.resolve("jar").toUri().toURL() }, null)) {
			host.withResources(jar);
			assertEquals(new Size(10, 20), host.addTarget("@targets/box.target", 0, 0).get().size());
		}

		assertEquals(Optional.empty(), host.addTarget("targets/no_such.target", 0, 0));
		assertEquals(3, host.targets().size());
		assertEquals(ipsc, host.targets().get(0));
	}

	@Test
	void shootDeliversTheModelHitUnlessDetectionIsPaused() throws TargetFormatException {
		final Recorder exercise = new Recorder();
		host.start(exercise);
		final TargetHandle ipsc = host.addTarget("targets/IPSC.target", 0, 0).get();

		// The same target placed the same way, to know which region the middle of it is
		final TargetSet reference = new TargetSet();
		final Rect bounds = reference.add(TargetDefinitions.load(Paths.get("targets/IPSC.target")), Placement.ORIGIN)
				.getBounds();
		final double x = bounds.getMinX() + bounds.getWidth() / 2;
		final double y = bounds.getMinY() + bounds.getHeight() / 2;
		final int region = HitTester.hit(reference, x, y).get().region().index();

		assertTrue(host.shoot(ShotColor.RED, x, y));
		ipsc.setVisible(false);
		assertTrue(host.shoot(ShotColor.RED, x, y));
		host.pauseShotDetection(true);
		assertFalse(host.shoot(ShotColor.RED, x, y));

		// Hidden targets take no hits; paused detection delivers nothing
		assertEquals(List.of("start", "shot " + region, "shot -1"), exercise.events);
		assertFalse(host.isVisible(ipsc));
		assertEquals(2, host.rows().size());
	}

	@Test
	void columnValuesAndStylesGoToTheLatestRow() {
		host.start(new Recorder());
		host.addColumn("Score");

		host.setColumnValue("Score", "ignored: there is no row yet");
		host.shoot(ShotColor.RED, 1, 1);
		host.setColumnValue("Score", "5");
		host.shoot(ShotColor.GREEN, 2, 2);
		host.setColumnValue("Score", "7");
		host.styleLastRow(new RowStyle("coral"));

		assertEquals(List.of("Score"), host.columns());
		assertEquals(List.of(Map.of("Score", "5"), Map.of("Score", "7")),
				host.rows().stream().map(FakeExerciseHost.Row::values).toList());
		assertEquals(Optional.empty(), host.rows().get(0).style());
		assertEquals(Optional.of(new RowStyle("coral")), host.rows().get(1).style());
		assertEquals(ShotColor.GREEN, host.rows().get(1).shot().get().getColor());
	}

	@Test
	void timerRowsWithoutAShotBecomeTheLatestRow() {
		host.start(new Recorder());
		host.addColumn("Score");
		host.shoot(ShotColor.RED, 1, 1);

		host.addTimerRow(2000, new RowStyle("coral"));
		host.setColumnValue("Score", "0");

		assertEquals(2, host.rows().size());
		final FakeExerciseHost.Row row = host.rows().get(1);
		assertEquals(Optional.empty(), row.shot());
		assertEquals(2000, row.timeMillis());
		assertEquals(Map.of("Score", "0"), row.values());
		assertEquals(Optional.of(new RowStyle("coral")), row.style());
		assertEquals(Optional.empty(), host.rows().get(0).style());
		assertEquals(FakeExerciseHost.START_TIME, host.rows().get(0).timeMillis());
	}

	@Test
	void buttonsTextsAndSettingsAreRecordedAndCallBack() {
		final List<String> calls = new ArrayList<>();
		final ButtonHandle pause = host.addButton("Pause", () -> calls.add("pause"));
		host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> calls.add("rounds " + value));
		final TextHandle text = host.showText("Score: 0", 10, 20, STYLE);

		host.click("Pause");
		pause.setLabel("Resume");
		host.click("Resume");
		host.changeSetting("Rounds", 5);
		text.setText("Score: 5");

		assertEquals(List.of("pause", "pause", "rounds 5.0"), calls);
		assertEquals(List.of("Resume"), host.buttonLabels());
		assertEquals(List.of("Rounds"), host.settingLabels());
		assertEquals(5, host.settingValue("Rounds"), 0);
		assertEquals(Optional.of("Score: 5"), host.textAt(10, 20));
		assertThrows(IllegalArgumentException.class, () -> host.changeSetting("Rounds", 101));
		assertThrows(IllegalStateException.class, () -> host.click("Pause"));

		text.move(30, 40);
		assertEquals(List.of(new FakeExerciseHost.ShownText("Score: 5", 30, 40, STYLE)), host.shownTexts());
		text.remove();
		pause.remove();
		assertEquals(List.of(), host.shownTexts());
		assertEquals(List.of(), host.buttonLabels());
	}

	@Test
	void yesNoAndChoiceSettingsAreRecordedAndCallBack() {
		final List<String> calls = new ArrayList<>();
		host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> calls.add("rounds " + value));
		host.addYesNoSetting("Remove hit targets", false, value -> calls.add("remove " + value));
		host.addChoiceSetting("Speed", List.of("1", "2", "3"), "2", choice -> calls.add("speed " + choice));

		host.changeSetting("Remove hit targets", true);
		host.chooseSetting("Speed", "3");

		assertEquals(List.of("remove true", "speed 3"), calls);
		assertEquals(List.of("Rounds", "Remove hit targets", "Speed"), host.settingLabels());
		assertTrue(host.yesNoSettingValue("Remove hit targets"));
		assertEquals("3", host.choiceSettingValue("Speed"));
		assertEquals(List.of("1", "2", "3"), host.settingChoices("Speed"));
		assertThrows(IllegalArgumentException.class, () -> host.chooseSetting("Speed", "4"));
		assertThrows(IllegalStateException.class, () -> host.changeSetting("Speed", true));
		assertThrows(IllegalStateException.class, () -> host.yesNoSettingValue("Rounds"));
		assertThrows(IllegalArgumentException.class,
				() -> host.addChoiceSetting("Count", List.of("1", "2"), "5", choice -> {}));
	}

	@Test
	void parAndDelayListenersHearOnlyUserChanges() {
		final List<String> heard = new ArrayList<>();
		assertEquals(FakeExerciseHost.DEFAULT_PAR_TIME, host.parTime(), 0);
		assertFalse(host.isListeningForParTime());

		host.onParTimeChanged(par -> heard.add("par " + par));
		host.onDelayedStartChanged(range -> heard.add("delay " + range.minSeconds() + "-" + range.maxSeconds()));
		host.setParTime(4.0);
		host.setDelayedStart(new DelayRange(5, 8));

		assertEquals(List.of(), heard);
		assertEquals(4.0, host.parTime(), 0);
		assertEquals(new DelayRange(5, 8), host.delayedStart());

		host.changeParTime(2.5);
		host.changeDelayedStart(new DelayRange(1, 2));

		assertEquals(List.of("par 2.5", "delay 1-2"), heard);
		assertEquals(2.5, host.parTime(), 0);
		assertTrue(host.isListeningForParTime());
		assertTrue(host.isListeningForDelayedStart());
	}

	@Test
	void stopRemovesEverythingAndCancelsTasks() {
		final Recorder exercise = new Recorder();
		host.start(exercise);
		host.addTarget("targets/IPSC.target", 0, 0);
		host.showText("Round: 1", 0, 0, STYLE);
		host.addButton("Pause", () -> {});
		host.addNumberSetting("Rounds", 1, 0, 2, 1, value -> {});
		host.addColumn("Score");
		host.showShotMarker(1, 1, new ShotStyle(ShotColor.RED));
		host.setBackground("backgrounds/black.png");
		host.onParTimeChanged(par -> {});
		host.pauseShotDetection(true);
		final List<String> ran = new ArrayList<>();
		host.schedule(() -> ran.add("late"), Duration.ofSeconds(1));

		host.stop();
		host.advance(Duration.ofSeconds(2));

		assertEquals(List.of("start", "stop"), exercise.events);
		assertEquals(List.of(), ran);
		assertTrue(host.isStopped());
		assertEquals(List.of(), host.targets());
		assertEquals(List.of(), host.shownTexts());
		assertEquals(List.of(), host.buttonLabels());
		assertEquals(List.of(), host.settingLabels());
		assertEquals(List.of(), host.columns());
		assertEquals(List.of(), host.shotMarkers());
		assertEquals(Optional.empty(), host.background());
		assertFalse(host.isListeningForParTime());
		assertFalse(host.isShotDetectionPaused());
		assertEquals(0, host.pendingTasks());

		// Calls after stop change nothing
		host.showText("late", 0, 0, STYLE);
		host.schedule(() -> ran.add("after stop"), Duration.ZERO);
		host.advance(Duration.ofSeconds(1));
		assertEquals(List.of(), host.shownTexts());
		assertEquals(List.of(), ran);
		assertFalse(host.shoot(ShotColor.RED, 1, 1));
	}

	@Test
	void soundsMessagesAndResourcesAreRecorded() throws IOException {
		host.playSound("sounds/beep.wav");
		host.playSounds(List.of("a.wav", "b.wav"));
		host.say("Make ready");
		host.showMessage("Score: 1");

		assertEquals(List.of("sounds/beep.wav", "a.wav", "b.wav"), host.sounds());
		assertEquals(List.of("Make ready"), host.spoken());
		assertEquals(List.of("Score: 1"), host.messages());

		// resource() reads the exercise's jar: the test classpath by default
		try (InputStream in = host.resource("/com/shootoff/exercise/FakeExerciseHost.class").get()) {
			assertTrue(in.read() >= 0);
		}
		assertEquals(Optional.empty(), host.resource("no/such/resource"));
		assertEquals(temp.resolve("data"), host.dataDirectory());
		assertTrue(Files.isDirectory(host.dataDirectory()));
	}

	@Test
	void aSoundExistsInTheExercisesJarOrShootoffsFolder() throws IOException {
		final Path jar = Files.createDirectories(temp.resolve("jar/sounds"));
		Files.write(jar.resolve("cue.wav"), new byte[] { 1 });
		host.withResources(new URLClassLoader(new URL[] { temp.resolve("jar").toUri().toURL() }, null));

		assertTrue(host.hasSound("sounds/cue.wav"));
		assertTrue(host.hasSound("@sounds/cue.wav"));
		assertTrue(host.hasSound("sounds/voice/shootoff-makeready.wav"));
		assertTrue(host.hasSound("beep.wav"));
		assertFalse(host.hasSound("sounds/voice/shootoff-undefined_region_name_5.wav"));
		assertFalse(host.hasSound(""));
	}

	@Test
	void resettingTargetsClearsTheShotsWithoutResettingTheExercise() {
		final Recorder recorder = new Recorder();
		host.start(recorder);
		host.shoot(ShotColor.RED, 5, 5);
		host.showShotMarker(5, 5, new ShotStyle(ShotColor.RED));

		host.resetTargets();
		host.reloadVirtualMagazine();

		assertEquals(List.of(), host.rows());
		assertEquals(List.of(), host.shotMarkers());
		assertEquals(1, host.targetResets());
		assertEquals(1, host.magazineReloads());
		assertEquals(List.of("start", "shot -1"), recorder.events);
	}
}
