package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;

/**
 * What every {@link ExerciseHost} must do, whatever user interface draws it. A user interface's host
 * test extends this and supplies a {@link Harness}: the host on a real surface of that user interface,
 * and ways to play the user and look at what is shown. Run it once per kind of surface (a camera feed,
 * the projector arena).
 */
public abstract class ExerciseHostContract {
	protected static final String EXERCISE_NAME = "Contract drill";
	private static final String EXERCISE_THREAD = "Exercise: " + EXERCISE_NAME;
	private static final TextStyle STYLE = new TextStyle(20, "white", "transparent");

	// A small box of the exercise's own, and one that shadows ShootOFF's targets/IPSC.target
	private static final byte[] BOX = "<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"red\" /></target>"
			.getBytes();
	private static final byte[] SHADOW = "<target><rectangle x=\"0\" y=\"0\" width=\"30\" height=\"40\" fill=\"blue\" /></target>"
			.getBytes();
	private static final byte[] CUE = { 1, 2, 3 };

	@TempDir protected Path temp;

	private Harness harness;
	private final Drill drill = new Drill();

	/**
	 * A host on one of the user interface's surfaces, running the contract's exercise.
	 */
	public interface Harness extends AutoCloseable {
		ExerciseHost host();

		/** Starts the exercise, as the user interface does when the user picks it. */
		void start();

		/** Hands the exercise a shot at (x, y) on its surface, as the shot pipeline does. */
		void shoot(double x, double y);

		/** The user presses Reset. */
		void reset();

		/** Stops the exercise, as the user interface does when the user picks another. */
		void stop();

		/** Waits until the user interface has made every change queued so far. */
		void awaitUi() throws Exception;

		/** The user clicks the exercise's button with this label. */
		void click(String label) throws Exception;

		/** The user sets the exercise's number setting with this label. */
		void changeSetting(String label, double value) throws Exception;

		/** The user ticks or clears the exercise's yes/no setting with this label. */
		void changeYesNoSetting(String label, boolean value) throws Exception;

		/** The user picks a choice of the exercise's choice setting with this label. */
		void chooseSetting(String label, String choice) throws Exception;

		/** The user types a par time into the shared control. */
		void userSetsParTime(double seconds) throws Exception;

		/** The user types a start delay into the shared controls. */
		void userSetsDelayedStart(int minSeconds, int maxSeconds) throws Exception;

		/**
		 * @return everything the surface, the controls and the shot timer show, comparable with
		 *         <tt>equals</tt>
		 */
		Object shownState() throws Exception;

		/** A shot's row appears in the shot timer, as the shot pipeline adds it. */
		void addShotRow(long timeMillis) throws Exception;

		/**
		 * @return the shot timer's rows, with each row's value in <tt>column</tt>
		 */
		List<TimerRowView> timerRows(String column) throws Exception;

		/**
		 * @return whether each view of the surface shows the target (one per view: the arena window and
		 *         the arena tab that mirrors it); empty if no view has it
		 */
		List<Boolean> visibilityOnEachView(TargetHandle target) throws Exception;

		/**
		 * @return how many views the surface has
		 */
		int viewCount();

		/**
		 * @return the names of the sounds played, in order
		 */
		List<String> playedSounds();

		@Override
		void close() throws Exception;
	}

	/**
	 * A shot timer row as the user sees it.
	 */
	public record TimerRowView(String time, String split, String laser, String columnValue, boolean highlighted) {}

	/**
	 * @param jarEntries
	 *            the exercise's jar: resource names and their bytes
	 */
	protected abstract Harness newHarness(Exercise exercise, Map<String, byte[]> jarEntries) throws Exception;

	// The exercise under test: it writes down its callbacks and their threads, and does what the test
	// asks when it starts
	private static final class Drill implements Exercise {
		final List<String> events = new CopyOnWriteArrayList<>();
		final Map<String, String> threads = new ConcurrentHashMap<>();
		final AtomicInteger stops = new AtomicInteger();
		volatile Consumer<ExerciseHost> onStart = host -> {};

		void record(String event) {
			threads.put(event, Thread.currentThread().getName());
			events.add(event);
		}

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata(EXERCISE_NAME, "1.0", "ShootOFF tests", "Checks the host contract");
		}

		@Override
		public void start(ExerciseHost host) {
			onStart.accept(host);
			// Written down once start() has done everything it does
			record("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			record("shot");
		}

		@Override
		public void onTargetsChanged(List<TargetHandle> targets) {
			record("targets");
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

	private Harness harness() throws Exception {
		if (harness == null) {
			final Map<String, byte[]> jar = new LinkedHashMap<>();
			jar.put("targets/box.target", BOX);
			jar.put("targets/IPSC.target", SHADOW);
			jar.put("sounds/cue.wav", CUE);
			harness = newHarness(drill, jar);
		}
		return harness;
	}

	@AfterEach
	void closeHarness() throws Exception {
		if (harness != null) harness.close();
	}

	private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail(failure);
			Thread.sleep(5);
		}
	}

	private void awaitEvent(String event) throws InterruptedException {
		waitFor(() -> drill.events.contains(event), "never saw " + event + " in " + drill.events);
	}

	private static byte[] read(Optional<InputStream> stream) throws IOException {
		try (InputStream in = stream.orElseThrow(() -> new AssertionError("nothing opened"))) {
			return in.readAllBytes();
		}
	}

	@Test
	void everyCallbackRunsOnTheExercisesOneThread() throws Exception {
		drill.onStart = host -> {
			host.addButton("Go", () -> drill.record("click"));
			host.addNumberSetting("Rounds", 1, 1, 10, 1, value -> drill.record("setting"));
			host.onParTimeChanged(seconds -> drill.record("par"));
			host.onDelayedStartChanged(range -> drill.record("delay"));
			host.schedule(() -> drill.record("scheduled"), Duration.ofMillis(10));
			host.scheduleRepeating(() -> drill.record("repeating"), Duration.ofMillis(10), Duration.ofMillis(50));
		};
		final Harness h = harness();

		h.start();
		awaitEvent("start");
		h.shoot(5, 5);
		h.reset();
		h.awaitUi();
		h.click("Go");
		h.changeSetting("Rounds", 2);
		h.userSetsParTime(3.0);
		h.userSetsDelayedStart(2, 6);

		for (final String event : List.of("start", "shot", "reset", "click", "setting", "par", "delay", "scheduled",
				"repeating")) {
			awaitEvent(event);
		}
		h.stop();

		assertTrue(drill.events.contains("stop"));
		assertEquals(Set.of(EXERCISE_THREAD), Set.copyOf(drill.threads.values()));
	}

	@Test
	void yesNoAndChoiceSettingsHearTheUsersChangesOnTheExercisesThread() throws Exception {
		final List<String> heard = new CopyOnWriteArrayList<>();
		drill.onStart = host -> {
			host.addYesNoSetting("Remove hit targets", false, value -> {
				heard.add("remove " + value);
				drill.record("yes/no");
			});
			host.addChoiceSetting("Speed", List.of("1", "5", "10"), "5", choice -> {
				heard.add("speed " + choice);
				drill.record("choice");
			});
		};
		final Harness h = harness();
		h.start();
		awaitEvent("start");
		h.awaitUi();

		h.changeYesNoSetting("Remove hit targets", true);
		h.chooseSetting("Speed", "10");
		awaitEvent("yes/no");
		awaitEvent("choice");

		assertEquals(List.of("remove true", "speed 10"), heard);
		assertEquals(Set.of(EXERCISE_THREAD), Set.copyOf(drill.threads.values()));
		assertThrows(IllegalArgumentException.class,
				() -> h.host().addChoiceSetting("Count", List.of("1", "2"), "5", choice -> {}));
	}

	@Test
	void stopRunsTheExercisesStopExactlyOnceFromAnyThread() throws Exception {
		final Harness h = harness();
		h.start();
		awaitEvent("start");

		final Thread other = new Thread(h::stop);
		other.start();
		h.stop();
		other.join();
		h.stop();

		assertEquals(1, drill.stops.get());
		assertEquals(List.of("start", "stop"), drill.events);
	}

	@Test
	void stopRemovesEverythingTheExerciseAddedAndLaterCallsChangeNothing() throws Exception {
		final AtomicBoolean ran = new AtomicBoolean();
		final List<TargetHandle> added = new CopyOnWriteArrayList<>();
		drill.onStart = host -> {
			host.addTarget("targets/IPSC.target", 5, 5).ifPresent(added::add);
			host.addTarget("IPSC.target", 50, 50).ifPresent(added::add);
			host.showText("Score: 0", 10, 10, STYLE);
			host.showMessage("Make ready");
			host.addButton("Pause", () -> {});
			host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> {});
			host.addYesNoSetting("Remove hit targets", true, value -> {});
			host.addChoiceSetting("Speed", List.of("1", "2"), "1", choice -> {});
			host.addColumn("Score");
			host.showShotMarker(20, 20, new ShotStyle(ShotColor.RED));
			host.onParTimeChanged(seconds -> {});
			host.onDelayedStartChanged(range -> {});
			host.schedule(() -> ran.set(true), Duration.ofMillis(300));
		};
		final Harness h = harness();
		h.awaitUi();
		final Object before = h.shownState();

		h.start();
		awaitEvent("start");
		h.awaitUi();
		assertEquals(2, added.size());
		assertNotEquals(before, h.shownState());

		h.stop();
		h.awaitUi();

		assertEquals(before, h.shownState());
		for (final TargetHandle target : added) {
			assertEquals(List.of(), h.visibilityOnEachView(target), "a target the exercise added is still shown");
		}
		Thread.sleep(500);
		assertFalse(ran.get(), "a scheduled task ran after stop");

		final ExerciseHost host = h.host();
		host.showText("late", 0, 0, STYLE);
		host.addButton("late", () -> {});
		host.showMessage("late");
		assertEquals(Optional.empty(), host.addTarget("targets/IPSC.target", 0, 0));
		h.awaitUi();
		assertEquals(before, h.shownState());
	}

	@Test
	void aTimerRowBecomesTheLatestRow() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();
		host.addColumn("Score");
		h.addShotRow(1000);

		host.addTimerRow(2500, new RowStyle("coral"));
		host.setColumnValue("Score", "0");
		h.awaitUi();
		h.addShotRow(3000);

		assertEquals(List.of(new TimerRowView(String.format("%.2f", 1.0f), "-", "red", "", false),
				new TimerRowView(String.format("%.2f", 2.5f), String.format("%.2f", 1.5f), "red", "0", true),
				new TimerRowView(String.format("%.2f", 3.0f), String.format("%.2f", 0.5f), "red", "", false)),
				h.timerRows("Score"));
	}

	@Test
	void hidingATargetHidesItOnEveryViewOfTheSurface() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();
		final TargetHandle fromJar = host.addTarget("@targets/box.target", 10, 10).get();
		final TargetHandle fromShootoff = host.addTarget("IPSC.target", 100, 100).get();
		h.awaitUi();

		final List<Boolean> allShown = Collections.nCopies(h.viewCount(), true);
		final List<Boolean> allHidden = Collections.nCopies(h.viewCount(), false);
		assertEquals(allShown, h.visibilityOnEachView(fromJar));
		assertEquals(allShown, h.visibilityOnEachView(fromShootoff));

		fromJar.setVisible(false);
		fromShootoff.setVisible(false);
		h.awaitUi();
		assertEquals(allHidden, h.visibilityOnEachView(fromJar));
		assertEquals(allHidden, h.visibilityOnEachView(fromShootoff));

		fromJar.setVisible(true);
		h.awaitUi();
		assertEquals(allShown, h.visibilityOnEachView(fromJar));
		assertEquals(allHidden, h.visibilityOnEachView(fromShootoff));
	}

	@Test
	void removingATargetRemovesItFromEveryViewOfTheSurface() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();
		final TargetHandle box = host.addTarget("@targets/box.target", 10, 10).get();
		final TargetHandle ipsc = host.addTarget("IPSC.target", 100, 100).get();
		h.awaitUi();

		// The handle the exercise got is the target the surface shows
		assertTrue(host.targets().containsAll(List.of(box, ipsc)));
		box.move(30, 40);
		assertEquals(30, box.position().getX(), 0);

		box.remove();
		h.awaitUi();

		assertEquals(List.of(), h.visibilityOnEachView(box));
		assertFalse(host.targets().contains(box));
		assertEquals(Collections.nCopies(h.viewCount(), true), h.visibilityOnEachView(ipsc));
	}

	@Test
	void parAndDelayListenersHearOnlyTheUsersChanges() throws Exception {
		final List<String> heard = new CopyOnWriteArrayList<>();
		drill.onStart = host -> {
			host.setParTime(4.0);
			host.setDelayedStart(new DelayRange(5, 8));
			host.onParTimeChanged(seconds -> heard.add("par " + seconds));
			host.onDelayedStartChanged(range -> heard.add("delay " + range.minSeconds() + "-" + range.maxSeconds()));
		};
		final Harness h = harness();
		h.start();
		awaitEvent("start");
		h.awaitUi();
		assertEquals(List.of(), heard);

		h.userSetsParTime(3.5);
		h.userSetsParTime(3.5);
		h.userSetsDelayedStart(9, 6);
		h.userSetsDelayedStart(2, 6);
		waitFor(() -> heard.size() >= 2, "heard only " + heard);
		Thread.sleep(100);

		assertEquals(List.of("par 3.5", "delay 2-6"), heard);
		assertEquals(3.5, h.host().parTime(), 0);
		assertEquals(new DelayRange(2, 6), h.host().delayedStart());
	}

	@Test
	void namesAreLookedUpInTheExercisesJarFirst() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();

		// The jar's IPSC.target shadows ShootOFF's; a bare name finds ShootOFF's in its targets folder
		assertEquals(1, host.addTarget("targets/IPSC.target", 0, 0).get().definition().regions().size());
		assertTrue(host.addTarget("IPSC.target", 0, 0).get().definition().regions().size() > 1);
		assertEquals(10, host.addTarget("@targets/box.target", 0, 0).get().size().getWidth(), 0);
		assertEquals(Optional.empty(), host.addTarget("targets/no_such.target", 0, 0));

		// Resources are the jar's only; sounds fall back to ShootOFF's sounds folder
		assertArrayEquals(CUE, read(host.resource("/sounds/cue.wav")));
		assertEquals(Optional.empty(), host.resource("sounds/beep.wav"));
		host.playSounds(List.of("sounds/cue.wav", "no_such.wav", "beep.wav"));
		waitFor(() -> h.playedSounds().size() >= 2, "played only " + h.playedSounds());
		assertEquals(List.of("sounds/cue.wav", "beep.wav"), h.playedSounds());
	}
}
