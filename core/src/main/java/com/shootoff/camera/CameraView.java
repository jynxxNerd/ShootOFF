package com.shootoff.camera;

import java.awt.image.BufferedImage;
import java.util.Optional;

import com.shootoff.Closeable;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.geom.Rect;

/**
 * Shows one camera's frames, shots and diagnostic messages. The JavaFX implementation is
 * com.shootoff.gui.CanvasManager. No UI toolkit types appear here, so camera code can live in
 * core.
 *
 * @author phrack
 */
public interface CameraView extends Closeable {
	/**
	 * Receives a newly detected shot on a non-UI thread. The shot is already offset for the
	 * projection bounds and scaled to the display; the view creates its own marker for it.
	 */
	public void addShot(ScaledShot shot);

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

	/**
	 * @return whether the view has targets of its own, an exercise's included, shown or hidden. While it does,
	 *         a camera that limits detection to the arena's projection looks at the whole frame, so that shots
	 *         on those targets beside the projection are seen (see {@link CameraManager#getDetectionArea}).
	 *         Called on the camera's thread for every frame: it must be quick and safe to call from any
	 *         thread.
	 */
	public boolean hasTargets();
}
