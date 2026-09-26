package com.shootoff.session.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;

class TestSessionColors {
	@Test
	void paintStringsMatchJavaFxColors() {
		for (final ShotColor color : ShotColor.values()) {
			// The strings JavaFX's Color.toString() gave the markers, which session files contain
			assertEquals(DisplayShot.toPaint(color).toString(), SessionColors.paintString(color));
			assertEquals(color, SessionColors.parse(SessionColors.paintString(color)));
			assertEquals(color, SessionColors.parse(color.name()));
		}
	}
}
