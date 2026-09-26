package com.shootoff.courses;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Size;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.gui.controller.MockProjectorArenaController;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.targets.Target;

public class TestArenaCourse {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private static final String BACKGROUND_URL = "/arena/backgrounds/indoor_range.gif";

	private MockProjectorArenaController arenaPane;
	private final List<String> notices = new ArrayList<>();

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final Configuration config = new Configuration(new String[0]);
		arenaPane = new MockProjectorArenaController(config, new MockCanvasManager(config));
		Settings.setUserNotifier((title, header, message) -> notices.add(message));
	}

	@After
	public void tearDown() {
		Settings.setUserNotifier(null);
	}

	@Test
	public void currentCourseKeepsTargetsBackgroundAndResolution() {
		arenaPane.setArenaBackground(
				new LocatedImage(TestArenaCourse.class.getResourceAsStream(BACKGROUND_URL), BACKGROUND_URL));
		final Target target = arenaPane.getCanvasManager().addTarget(new File("targets/Reset.target")).get();
		target.setPosition(10, 100);
		target.setDimensions(10, 1);

		final Course course = arenaPane.getCourse();

		assertEquals(Optional.of(new CourseBackground(BACKGROUND_URL, true)), course.getBackground());
		assertEquals(1, course.getTargets().size());
		final CourseTarget saved = course.getTargets().get(0);
		assertEquals(new File("targets/Reset.target"), saved.file());
		assertEquals(10, saved.x(), 1);
		assertEquals(100, saved.y(), 1);
		assertEquals(10, saved.width(), 1);
		assertEquals(1, saved.height(), 1);

		// Default arena dimensions (this will fail if you change the dimensions in
		// MockProjectorArenaController)
		assertTrue(course.getResolution().isPresent());
		assertEquals(640, course.getResolution().get().getWidth(), 1);
		assertEquals(360, course.getResolution().get().getHeight(), 1);
	}

	@Test
	public void appliedCourseKeepsEachTargetsPositionAndSize() {
		final List<Target> added = arenaPane.setCourse(new Course(Optional.empty(),
				List.of(new CourseTarget(new File("targets/Reset.target"), 10, 100, 10, 1),
						new CourseTarget(new File("targets/IPSC.target"), 200, 50, 90, 114)),
				Optional.of(new Size(arenaPane.getWidth(), arenaPane.getHeight()))));

		assertEquals(2, added.size());
		assertEquals(added, arenaPane.getCanvasManager().getTargets());
		assertEquals(10, added.get(0).getPosition().getX(), 0.001);
		assertEquals(100, added.get(0).getPosition().getY(), 0.001);
		assertEquals(10, added.get(0).getDimension().getWidth(), 0.001);
		assertEquals(1, added.get(0).getDimension().getHeight(), 0.001);
		assertEquals(200, added.get(1).getPosition().getX(), 0.001);
		assertEquals(90, added.get(1).getDimension().getWidth(), 0.001);
		assertEquals(114, added.get(1).getDimension().getHeight(), 0.001);
		assertTrue(notices.isEmpty());
	}

	@Test
	public void appliedCourseScalesToTheArena() {
		final List<Target> added = arenaPane.setCourse(new Course(Optional.empty(),
				List.of(new CourseTarget(new File("targets/IPSC.target"), 200, 50, 90, 114)),
				Optional.of(new Size(arenaPane.getWidth() * 2, arenaPane.getHeight() * 2))));

		assertEquals(45, added.get(0).getDimension().getWidth(), 0.01);
		assertEquals(57, added.get(0).getDimension().getHeight(), 0.01);
	}

	@Test
	public void missingCourseTargetIsSkippedAndReported() {
		final List<Target> added = arenaPane.setCourse(new Course(Optional.empty(),
				List.of(new CourseTarget(new File("targets/no_such_target.target"), 0, 0, 10, 10),
						new CourseTarget(new File("targets/Reset.target"), 10, 100, 10, 1)),
				Optional.of(new Size(arenaPane.getWidth(), arenaPane.getHeight()))));

		assertEquals(1, added.size());
		assertEquals(1, notices.size());
		assertTrue(notices.get(0), notices.get(0).contains("no_such_target.target"));
	}

	@Test
	public void courseWithAMissingResourceBackgroundStillApplies() {
		final List<Target> added = arenaPane.setCourse(new Course(
				Optional.of(new CourseBackground("/backgrounds/no_such_background.png", true)),
				List.of(new CourseTarget(new File("targets/Reset.target"), 10, 100, 10, 1)),
				Optional.of(new Size(arenaPane.getWidth(), arenaPane.getHeight()))));

		assertEquals(1, added.size());
		assertFalse(arenaPane.getArenaBackground().isPresent());
	}

	@Test
	public void missingResourceBackgroundHasNoImage() {
		assertFalse(ProjectorArenaPane
				.toLocatedImage(new CourseBackground("/backgrounds/no_such_background.png", true)).isPresent());
		assertTrue(ProjectorArenaPane.toLocatedImage(new CourseBackground(BACKGROUND_URL, true)).isPresent());
	}

	@Test
	public void backgroundOnlyAnExerciseCanLoadIsNotSavedInACourse() {
		// An exercise jar's own resource, e.g. RandomTargetParDrill's /backgrounds/blackBG.png: ShootOFF
		// can't load it back from its own class path
		arenaPane.setArenaBackground(new LocatedImage(TestArenaCourse.class.getResourceAsStream(BACKGROUND_URL),
				"/backgrounds/no_such_background.png"));

		assertFalse(arenaPane.getCourse().getBackground().isPresent());
	}
}
