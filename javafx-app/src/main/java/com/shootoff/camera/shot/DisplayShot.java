package com.shootoff.camera.shot;

import com.shootoff.camera.Shot;

import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;

/**
 * A {@link ScaledShot} with the JavaFX marker that shows it on a canvas.
 *
 * @author cbdmaul
 */
public class DisplayShot extends ScaledShot {
	protected Ellipse marker;

	public DisplayShot(ShotColor color, double x, double y, long timestamp, int frame, int markerRadius) {
		super(color, x, y, timestamp, frame);
		marker = new Ellipse(x, y, markerRadius, markerRadius);
		marker.setFill(toPaint(color));
	}

	public DisplayShot(ShotColor color, double x, double y, long timestamp, int markerRadius) {
		super(color, x, y, timestamp);
		marker = new Ellipse(x, y, markerRadius, markerRadius);
		marker.setFill(toPaint(color));
	}

	/**
	 * Creates the marker for <tt>shot</tt> at its display position (keeping any bounds and
	 * display values it already has).
	 */
	public DisplayShot(Shot shot, int markerRadius) {
		super(shot);
		marker = new Ellipse(getX(), getY(), markerRadius, markerRadius);
		marker.setFill(toPaint(color));
	}

	public DisplayShot(Shot shot, Ellipse marker) {
		super(shot);
		this.marker = marker;
	}

	/**
	 * @return the JavaFX paint used for markers of shots of <tt>color</tt>
	 */
	public static Color toPaint(ShotColor color) {
		return switch (color) {
		case RED -> Color.RED;
		case GREEN -> Color.GREEN;
		case INFRARED -> Color.ORANGE;
		};
	}

	public Color getPaintColor() {
		return toPaint(color);
	}

	public Ellipse getMarker() {
		return marker;
	}

	@Override
	public void setDisplayVals(int displayWidth, int displayHeight, int feedWidth, int feedHeight) {
		super.setDisplayVals(displayWidth, displayHeight, feedWidth, feedHeight);

		marker = new Ellipse(getDisplayX(), getDisplayY(), marker.radiusXProperty().get(),
				marker.radiusYProperty().get());
		marker.setFill(toPaint(color));
	}

	public Ellipse getDisplayMarker() {
		return this.marker;
	}
}
