package com.shootoff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The target and course files that ship with ShootOFF. Tests that check every bundled file use these
 * lists instead of walking targets/ and courses/, where users keep files of their own. Add a file here
 * when it is added to the repository.
 */
public final class BundledFiles {
	public static final List<String> TARGETS = List.of(
			"targets/AQT_Silhouette.target",
			"targets/Chicken_Silhouette.target",
			"targets/Duel_Tree.target",
			"targets/IPSC.target",
			"targets/IPSC_Classic_Falling_Popper.target",
			"targets/IPSC_Classic_Popper.target",
			"targets/ISSF.target",
			"targets/Musical_Target.target",
			"targets/POI_Offset_Adjustment.target",
			"targets/Pepper_Popper.target",
			"targets/Pig_Silhouette.target",
			"targets/Plate_Rack.target",
			"targets/Plate_Rack_Silhouette.target",
			"targets/Ram_Silhouette.target",
			"targets/Reset.target",
			"targets/SimpleBullseye_five_small.target",
			"targets/SimpleBullseye_score.target",
			"targets/Steel_Challenge_Circle.target",
			"targets/Steel_Challenge_Rectangle.target",
			"targets/Steel_Challenge_Stop_Circle.target",
			"targets/Steel_Challenge_Stop_Rectangle.target",
			"targets/Swedish_Soldier.target",
			"targets/Turkey_Silhouette.target",
			"targets/USPSA.target",
			"targets/shoot_dont_shoot/dont_shoot.target",
			"targets/shoot_dont_shoot/shoot.target");

	public static final List<String> COURSES = List.of(
			"courses/steel_challenge/accelerator.course",
			"courses/steel_challenge/five_to_go.course",
			"courses/steel_challenge/outerlimits.course",
			"courses/steel_challenge/pendulum.course",
			"courses/steel_challenge/roundabout.course",
			"courses/steel_challenge/showdown.course",
			"courses/steel_challenge/smoke_and_hope.course",
			"courses/steel_challenge/speed_option.course");

	private BundledFiles() {}

	public static List<Path> targets() {
		return existing(TARGETS);
	}

	public static List<Path> courses() {
		return existing(COURSES);
	}

	private static List<Path> existing(List<String> names) {
		final List<Path> paths = new ArrayList<>();

		for (final String name : names) {
			final Path path = Paths.get(name);
			if (!Files.isRegularFile(path)) {
				throw new IllegalStateException(name + " is listed in BundledFiles but doesn't exist");
			}
			paths.add(path);
		}

		return paths;
	}
}
