package com.shootoff.sound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestSoundPlayer {
	private final PrintStream originalOut = System.out;
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private String originalHome;

	@BeforeEach
	void captureStdout() {
		originalHome = System.getProperty("shootoff.home");
		System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
	}

	@AfterEach
	void restore() {
		System.setOut(originalOut);
		SoundPlayer.silence(false);
		if (originalHome == null) {
			System.clearProperty("shootoff.home");
		} else {
			System.setProperty("shootoff.home", originalHome);
		}
	}

	private String printed() {
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void absoluteFileIsUnchanged() {
		final File absolute = new File("sounds/beep.wav").getAbsoluteFile();

		assertEquals(absolute, SoundPlayer.resolve(absolute));
	}

	@Test
	void relativeFileResolvesAgainstShootoffHome() {
		System.setProperty("shootoff.home", "/opt/shootoff");

		assertEquals(new File("/opt/shootoff" + File.separator + "sounds/beep.wav"),
				SoundPlayer.resolve(new File("sounds/beep.wav")));
	}

	@Test
	void silencedFilePrintsItsUnresolvedPath() {
		SoundPlayer.silence(true);

		SoundPlayer.play("sounds/beep.wav");

		assertEquals("sounds/beep.wav" + System.lineSeparator(), printed());
	}

	@Test
	void silencedStreamPrintsPlaceholder() {
		SoundPlayer.silence(true);

		SoundPlayer.play(new ByteArrayInputStream(new byte[0]));

		assertEquals("Playing audio for modular exercise." + System.lineSeparator(), printed());
	}

	@Test
	void silencedPlayAllPrintsEachPath() {
		SoundPlayer.silence(true);

		SoundPlayer.playAll(List.of(new File("a.wav"), new File("b.wav")));

		assertEquals("a.wav" + System.lineSeparator() + "b.wav" + System.lineSeparator(), printed());
	}

	@Test
	void missingFileIsLoggedNotThrown() {
		assertDoesNotThrow(() -> SoundPlayer.play(new File("/no/such/dir/missing.wav")));
	}
}
