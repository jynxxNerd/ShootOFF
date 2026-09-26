package com.shootoff.gui.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Point;
import com.shootoff.targets.io.TargetIO;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;

class TestTargetViewThreading {
	private static <T> T onFx(Callable<T> action) throws Exception {
		final CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.call());
			} catch (final Throwable t) {
				result.completeExceptionally(t);
			}
		});
		return result.get(5, TimeUnit.SECONDS);
	}

	@Test
	void offThreadMovesOfAShownTargetAreAppliedOnTheFxThread() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final List<Boolean> changedOnFxThread = new CopyOnWriteArrayList<>();

		final TargetView target = onFx(() -> {
			final TargetView view = new TargetView(TargetIO.loadTarget(new File("targets/IPSC.target"), false).get(),
					new ArrayList<>());
			new Scene(new Group(view.getTargetGroup()));
			view.getTargetGroup().layoutXProperty()
					.addListener((observable, oldX, newX) -> changedOnFxThread.add(Platform.isFxApplicationThread()));
			return view;
		});

		// From the test thread, as an exercise moves its targets from its own thread
		target.setPosition(40, 50);

		assertEquals(new Point(40, 50), target.getPlacedTarget().getPosition());
		assertEquals(40, onFx(() -> target.getTargetGroup().getLayoutX()), 0);
		assertEquals(List.of(true), changedOnFxThread);
	}
}
