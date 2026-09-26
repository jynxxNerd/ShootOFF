package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.processors.MalfunctionsProcessor;
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.session.Event;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.ShotEvent;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.RectangleRegion;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetSet;

class TestShotPipeline {
	private static final Rect PROJECTION = new Rect(100, 100, 540, 260);
	private static final Size ARENA = new Size(640, 360);

	private Settings settings;
	private SessionRecorder recorder;
	private final List<String> events = new CopyOnWriteArrayList<>();
	private FakeSurface camera;
	private FakeSurface arena;
	private ShotPipeline<Shot> cameraPipeline;
	private ShotPipeline<Shot> arenaPipeline;

	// A surface that writes down what the pipeline asks of it
	private class FakeSurface implements ShotPipeline.Surface<Shot> {
		final String name;
		final TargetSet targets = new TargetSet();
		final List<Shot> rows = new CopyOnWriteArrayList<>();
		Optional<ShotPipeline.Arena<Shot>> arena = Optional.empty();
		boolean hasTimer = true;
		boolean exerciseRunning = true;
		boolean throwOnRow = false;

		FakeSurface(String name) {
			this.name = name;
		}

		PlacedTarget addBox(double x, double y, Map<String, String> tags) {
			final PlacedTarget box = targets.add(new TargetDefinition(Optional.empty(), Map.of(),
					List.of(new RectangleRegion(0, 0, 0, 50, 50, "red", tags))));
			targets.move(box.getId(), x, y);
			return box;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public TargetSet targets() {
			return targets;
		}

		@Override
		public Optional<Hit> hitTest(double x, double y) {
			events.add(name + " hit test");
			return HitTester.hit(targets, x, y);
		}

		@Override
		public Optional<ShotTimer<Shot>> shotTimer() {
			if (!hasTimer) return Optional.empty();

			return Optional.of((shot, hadMalfunction, hadReload) -> {
				if (throwOnRow) throw new IllegalStateException("Called endChange before beginChange");

				events.add(name + " row" + (hadMalfunction ? " after a malfunction" : "")
						+ (hadReload ? " after a reload" : ""));
				rows.add(shot);
			});
		}

		@Override
		public void show(Shot shot) {
			events.add(name + " shows (" + (int) shot.getX() + ", " + (int) shot.getY() + ")");
		}

		@Override
		public int markerRadius(Shot shot) {
			return 3;
		}

		@Override
		public void runRegionCommands(Shot shot, Hit hit, boolean mirrored) {
			events.add(name + " runs " + hit.region().commands() + (mirrored ? " mirrored" : ""));
		}

		@Override
		public boolean deliver(Shot shot, Optional<Hit> hit, boolean arenaShot) {
			events.add(name + " delivers " + (hit.isPresent() ? "a hit" : "a miss") + (arenaShot ? " on the arena" : ""));
			return exerciseRunning;
		}

		@Override
		public Optional<ShotPipeline.Arena<Shot>> arena() {
			return arena;
		}
	}

	// The arena as the camera feed passes shots to it
	private final class FakeArena implements ShotPipeline.Arena<Shot> {
		Size size = ARENA;

		@Override
		public Optional<Rect> projection() {
			return Optional.of(PROJECTION);
		}

		@Override
		public Size size() {
			return size;
		}

		@Override
		public Shot toArenaShot(Shot shot, Point arenaPoint) {
			return new Shot(shot.getColor(), arenaPoint.getX(), arenaPoint.getY(), shot.getTimestamp(), shot.getFrame());
		}

		@Override
		public boolean addArenaShot(Shot shot, Optional<String> videoString, boolean mirrored) {
			return arenaPipeline.addArenaShot(shot, videoString, mirrored);
		}
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		settings = new Settings(new String[0]);
		recorder = new SessionRecorder();
		settings.setSessionRecorder(recorder);
		MalfunctionsProcessor.setUseTTS(false);

		camera = new FakeSurface("Camera 1");
		arena = new FakeSurface("arena");
		arena.hasTimer = false;
		cameraPipeline = new ShotPipeline<>(camera, settings);
		arenaPipeline = new ShotPipeline<>(arena, settings);
	}

	@AfterEach
	void tearDown() {
		settings.setSessionRecorder(null);
	}

	private static Shot shot(double x, double y) {
		return new Shot(ShotColor.RED, x, y, 1000);
	}

	private List<ShotEvent> recorded(String surface) {
		final List<ShotEvent> shots = new java.util.ArrayList<>();
		for (final Event event : recorder.getCameraEvents(surface)) {
			if (event instanceof ShotEvent shotEvent) shots.add(shotEvent);
		}
		return shots;
	}

	@Test
	void aCameraShotIsRowedShownHitTestedRecordedAndDelivered() {
		camera.addBox(10, 10, Map.of());
		camera.addBox(200, 200, Map.of());

		cameraPipeline.addShot(shot(220, 220), false);

		assertEquals(List.of("Camera 1 row", "Camera 1 shows (220, 220)", "Camera 1 hit test", "Camera 1 delivers a hit"),
				events);
		final ShotEvent recordedShot = recorded("Camera 1").get(0);
		assertEquals(Optional.of(1), recordedShot.getTargetIndex());
		assertEquals(Optional.of(0), recordedShot.getHitRegionIndex());
		assertEquals(3, recordedShot.getMarkerRadius());
		assertFalse(recordedShot.isMalfunction());
	}

	@Test
	void aMissIsRecordedWithoutATarget() {
		camera.addBox(10, 10, Map.of());

		cameraPipeline.addShot(shot(300, 300), false);

		assertTrue(events.contains("Camera 1 delivers a miss"));
		assertEquals(Optional.empty(), recorded("Camera 1").get(0).getTargetIndex());
		assertEquals(Optional.empty(), recorded("Camera 1").get(0).getHitRegionIndex());
	}

	@Test
	void aMalfunctionRejectsTheShotAndItsNextRowSaysSo() {
		settings.setMalfunctions(true);
		settings.setMalfunctionsProbability(100);

		cameraPipeline.addShot(shot(20, 20), false);

		// Recorded as a malfunction, and nothing else
		assertEquals(List.of(), events);
		assertTrue(recorded("Camera 1").get(0).isMalfunction());

		settings.setMalfunctions(false);
		cameraPipeline.addShot(shot(30, 30), false);
		cameraPipeline.addShot(shot(40, 40), false);

		assertEquals(List.of("Camera 1 row after a malfunction", "Camera 1 row"),
				events.stream().filter(event -> event.contains("row")).toList());
	}

	@Test
	void anEmptyVirtualMagazineRejectsTheShotAndItsNextRowIsAReload() {
		settings.setUseVirtualMagazine(true);
		settings.setVirtualMagazineCapacity(1);
		for (final ShotProcessor processor : settings.getShotProcessors()) {
			if (processor instanceof VirtualMagazineProcessor magazine) magazine.setUseTTS(false);
		}

		cameraPipeline.addShot(shot(20, 20), false);
		cameraPipeline.addShot(shot(30, 30), false);
		cameraPipeline.addShot(shot(40, 40), false);

		assertEquals(List.of("Camera 1 row", "Camera 1 row after a reload"),
				events.stream().filter(event -> event.contains("row")).toList());
		assertEquals(List.of(false, true, false), recorded("Camera 1").stream().map(ShotEvent::isReload).toList());
	}

	@Test
	void aShotInsideTheProjectionGoesToTheArenaInArenaCoordinates() {
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(360, 220, Map.of());
		final Point onArena = ArenaGeometry.canvasToArena(381, 235, PROJECTION, ARENA);
		arena.addBox(onArena.getX() - 5, onArena.getY() - 5, Map.of());

		cameraPipeline.addShot(shot(381, 235), false);

		// The camera feed shows the shot and rows it; the arena hit-tests, records and delivers it
		assertEquals(List.of("Camera 1 row", "Camera 1 shows (381, 235)",
				"arena shows (" + (int) onArena.getX() + ", " + (int) onArena.getY() + ")", "arena hit test",
				"arena delivers a hit on the arena"), events);
		assertEquals(List.of(), recorded("Camera 1"));
		assertEquals(Optional.of(0), recorded("arena").get(0).getTargetIndex());
		assertEquals(onArena.getX(), recorded("arena").get(0).getShot().getX(), 0);
	}

	@Test
	void aShotOnTheProjectionsEdgeGoesToTheArenaAtTheArenasCurrentSize() {
		final FakeArena fakeArena = new FakeArena();
		camera.arena = Optional.of(fakeArena);

		// The projection's far corner, edges inclusive
		cameraPipeline.addShot(shot(640, 360), false);
		// The arena window was resized after calibration
		fakeArena.size = new Size(1280, 720);
		cameraPipeline.addShot(shot(640, 360), false);

		assertEquals(List.of("arena shows (640, 360)", "arena shows (1280, 720)"),
				events.stream().filter(event -> event.startsWith("arena shows")).toList());
		assertFalse(events.contains("Camera 1 hit test"));
	}

	@Test
	void aShotOutsideTheProjectionIsHitTestedOnTheCameraFeed() {
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(40, 40, Map.of());

		cameraPipeline.addShot(shot(50, 50), false);

		assertEquals(List.of("Camera 1 row", "Camera 1 shows (50, 50)", "Camera 1 hit test", "Camera 1 delivers a hit"),
				events);
		assertEquals(List.of(), recorded("arena"));
	}

	@Test
	void mirroredShotsAreNotProcessedRecordedOrDeliveredFromTheArena() {
		settings.setMalfunctions(true);
		settings.setMalfunctionsProbability(100);
		arena.addBox(0, 0, Map.of("command", "reset"));

		cameraPipeline.addShot(shot(20, 20), true);
		final boolean taken = arenaPipeline.addArenaShot(shot(20, 20), Optional.empty(), true);

		// Not rejected by the malfunction, and not recorded
		assertEquals(List.of("Camera 1 row", "Camera 1 shows (20, 20)", "Camera 1 hit test", "Camera 1 delivers a miss",
				"arena shows (20, 20)", "arena hit test", "arena runs [RegionCommand[name=reset, args=[]]] mirrored"),
				events);
		assertFalse(taken);
		assertEquals(List.of(), recorded("Camera 1"));
		assertEquals(List.of(), recorded("arena"));
	}

	@Test
	void regionCommandsRunOnlyForRegionsThatHaveThem() {
		camera.addBox(0, 0, Map.of("command", "animate(pepper_popper);play_sound(sounds/metal_clang.wav)"));
		camera.addBox(100, 100, Map.of("name", "plain"));

		cameraPipeline.addShot(shot(10, 10), false);
		cameraPipeline.addShot(shot(110, 110), false);

		assertEquals(List.of("Camera 1 runs [RegionCommand[name=animate, args=[pepper_popper]], "
				+ "RegionCommand[name=play_sound, args=[sounds/metal_clang.wav]]]"),
				events.stream().filter(event -> event.contains("runs")).toList());
	}

	@Test
	void twoShotsInOneFrameMakeTwoRowsInOrderOnOneThread() throws Exception {
		final ShotQueue queue = new ShotQueue("Test shots");
		final AtomicInteger appending = new AtomicInteger();
		final AtomicBoolean overlapped = new AtomicBoolean();
		final CountDownLatch secondArrived = new CountDownLatch(1);
		final CountDownLatch bothRowed = new CountDownLatch(2);
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		final List<Shot> rowed = new CopyOnWriteArrayList<>();
		final ShotPipeline<Shot> pipeline = new ShotPipeline<>(new FakeSurface("Camera 1") {
			@Override
			public Optional<ShotTimer<Shot>> shotTimer() {
				return Optional.of((shot, hadMalfunction, hadReload) -> {
					threads.add(Thread.currentThread());
					if (appending.incrementAndGet() > 1) {
						overlapped.set(true);
						secondArrived.countDown();
					} else {
						try {
							secondArrived.await(300, TimeUnit.MILLISECONDS);
						} catch (final InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					rowed.add(shot);
					appending.decrementAndGet();
					bothRowed.countDown();
				});
			}
		}, settings);

		// One camera frame, two lasers: both shots are submitted at once
		final Shot first = shot(100, 100);
		final Shot second = shot(400, 300);
		queue.submit(() -> pipeline.addShot(first, false));
		final Thread other = new Thread(() -> queue.submit(() -> pipeline.addShot(second, false)));
		other.start();
		other.join();

		assertTrue(bothRowed.await(5, TimeUnit.SECONDS));
		assertFalse(overlapped.get(), "two threads appended rows at once");
		assertEquals(List.of(first, second), rowed);
		assertEquals(1, Set.copyOf(threads).size());
	}

	// Defense in depth: a user interface's row list can throw (e.g. two threads changed it at once);
	// the shot must still be shown, hit-tested, recorded and delivered
	@Test
	void aThrowingRowAppendDoesNotLoseTheShot() {
		camera.addBox(10, 10, Map.of());
		camera.addBox(200, 200, Map.of());
		camera.throwOnRow = true;

		cameraPipeline.addShot(shot(220, 220), false);

		// No "Camera 1 row" event: the row append threw and was caught, but everything after it ran
		assertEquals(List.of("Camera 1 shows (220, 220)", "Camera 1 hit test", "Camera 1 delivers a hit"), events);
		assertEquals(List.of(), camera.rows);
		final ShotEvent recordedShot = recorded("Camera 1").get(0);
		assertEquals(Optional.of(1), recordedShot.getTargetIndex());
		assertEquals(Optional.of(0), recordedShot.getHitRegionIndex());
	}
}
