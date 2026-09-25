package com.shootoff.plugins;

import static org.junit.Assert.assertTrue;

import javax.sound.sampled.AudioInputStream;

import org.junit.Test;

import marytts.LocalMaryInterface;
import marytts.MaryInterface;

public class TestTextToSpeechEngine {
	@Test
	public void testSynthesizesSpeech() throws Exception {
		final MaryInterface mary = new LocalMaryInterface();
		final AudioInputStream audio = mary.generateAudio("Bad shoot!");
		assertTrue(audio.getFrameLength() > 0);
	}
}
