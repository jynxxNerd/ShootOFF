package com.shootoff.camera;

import java.awt.image.BufferedImage;
import java.util.Optional;

import com.shootoff.Closeable;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.geom.Rect;

/**
 * Shows one camera's frames, shots and diagnostic messages. The JavaFX implementation is
 * com.shootoff.gui.CanvasManager. No UI toolkit types appear here, so camera code can live in
 * core.
 *
 * @author phrack
 */
public interface CameraView extends Closeable {
	public void addShot(DisplayShot shot, boolean isMirroredShot);

	/**
	 * Shows a warning until the returned message is removed.
	 */
	public DiagnosticMessage addDiagnosticWarning(String message);

	public void clearShots();

	@Override
	public void close();

	public void reset();

	public void setCameraManager(CameraManager cameraManager);

	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds);
}
