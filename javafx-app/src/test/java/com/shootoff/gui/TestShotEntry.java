package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;

import javafx.scene.paint.Color;

class TestShotEntry {
	@Test
	void withRowColorKeepsTheRowsValues() {
		final ShotEntry entry = new ShotEntry(new DisplayShot(new Shot(ShotColor.RED, 1, 2, 1500), 2),
				Optional.of(new Shot(ShotColor.RED, 0, 0, 1000)), Optional.empty(), false, false);
		entry.setExerciseValue("Score", "10");

		final ShotEntry colored = entry.withRowColor(Optional.of(Color.CORAL));

		assertEquals(Optional.of(Color.CORAL), colored.getRowColor());
		assertEquals(Optional.of(Color.CORAL), colored.getSplit().getRowColor());
		assertEquals(entry.getSplit().getSplit(), colored.getSplit().getSplit());
		assertEquals("10", colored.getExerciseValue("Score"));
		assertEquals(entry.getTimestamp(), colored.getTimestamp());
		assertEquals(entry.getColor(), colored.getColor());
		assertSame(entry.getShot(), colored.getShot());
		assertEquals(Optional.empty(), entry.getRowColor());
	}
}
