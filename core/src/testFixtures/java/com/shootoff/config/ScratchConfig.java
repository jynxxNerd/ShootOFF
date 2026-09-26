package com.shootoff.config;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Configuration files for tests. The working tree's <tt>shootoff.properties</tt> holds the owner's
 * settings, so no test may read or write it: a test whose configuration can be written
 * ({@link Settings#writeConfigurationFile()}, which POI adjustment calls) builds it on
 * {@link #emptyFile()} instead, and checks {@link #workingTreeFingerprint()} is unchanged afterwards.
 */
public final class ScratchConfig {
	private static final File WORKING_TREE_FILE = new File("shootoff.properties");

	private ScratchConfig() {}

	/**
	 * @return a new, empty configuration file in the temporary folder, deleted when the JVM exits
	 */
	public static File emptyFile() throws IOException {
		final File file = Files.createTempFile("shootoff-test", ".properties").toFile();
		file.deleteOnExit();
		return file;
	}

	/**
	 * @return the working tree's <tt>shootoff.properties</tt> (tests run in the repository root): a hash
	 *         of its bytes and its modification time, or "absent"
	 */
	public static String workingTreeFingerprint() {
		if (!WORKING_TREE_FILE.exists()) return "absent";

		try {
			final byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(WORKING_TREE_FILE.toPath()));
			return HexFormat.of().formatHex(hash) + " " + WORKING_TREE_FILE.lastModified();
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
