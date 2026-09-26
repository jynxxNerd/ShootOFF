/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.shootoff.gui.targets;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.config.Configuration;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.FxGeometry;
import com.shootoff.targets.Hit;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.RectangleRegion;
import com.shootoff.targets.RegionType;
import com.shootoff.targets.Target;
import com.shootoff.targets.TargetRegion;
import com.shootoff.targets.animation.SpriteAnimation;
import com.shootoff.targets.io.TargetIO.TargetComponents;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetSet;
import com.shootoff.targets.model.TargetSetListener;

import javafx.animation.Animation.Status;
import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.transform.Scale;

/**
 * Shows a target and lets the user select, move and resize it. The target's position, scale and
 * visibility live in a {@link PlacedTarget} owned by a {@link TargetSet}: its canvas's set once
 * {@link CanvasManager#addTarget(Target)} adds it, a private one before that and after it is
 * removed. Every change goes through the set, and the JavaFX nodes follow the set's change
 * events. The scale is drawn with a {@link Scale} transform about the model's pivot; the group's
 * own scaleX/scaleY stay 1.
 *
 * @author phrack
 */
public class TargetView implements Target {
	private static final Logger logger = LoggerFactory.getLogger(TargetView.class);

	private static final double ANCHOR_WIDTH = 10;
	private static final double ANCHOR_HEIGHT = ANCHOR_WIDTH;

	protected static final int MOVEMENT_DELTA = 1;
	protected static final int SCALE_DELTA = 1;
	private static final int RESIZE_MARGIN = 5;

	private final TargetComponents components;
	private final Group targetGroup;
	private final List<Node> regionNodes;
	private final Map<String, String> targetTags;
	private final Scale scale = new Scale(1, 1, 0, 0);
	private final Set<Node> resizeAnchors = new HashSet<>();
	private final Optional<Configuration> config;
	private final Optional<CanvasManager> parent;
	private final Optional<List<Target>> targets;
	private final boolean userDeletable;
	private final String cameraName;
	private final TargetSetListener placementListener = new TargetSetListener() {
		@Override
		public void targetChanged(PlacedTarget target) {
			if (target.getId().equals(membership.placed().getId())) applyPlacement(target);
		}
	};

	// The set this target is in and its entry there, swapped together when it joins or leaves a
	// canvas
	private volatile Membership membership;
	private boolean keepInBounds = false;
	private boolean isSelected = false;
	private boolean move;
	private boolean resize;
	private boolean top;
	private boolean bottom;
	private boolean left;
	private boolean right;
	private double x;
	private double y;

	private TargetSelectionListener selectionListener;

	private record Membership(TargetSet set, PlacedTarget placed) {}

	public TargetView(TargetComponents components, CanvasManager parent, boolean userDeletable) {
		this(components, Optional.of(parent), Optional.empty(), userDeletable);

		targetGroup.setOnMouseClicked((event) -> {
			// Skip target selection if click to shoot is being used
			if (config.isPresent() && config.get().inDebugMode() && (event.isShiftDown() || event.isControlDown()))
				return;

			parent.toggleTargetSelection(Optional.of(this));
			targetGroup.requestFocus();
			event.consume();
		});
	}

	// Used by the session viewer, target pane, and for testing
	public TargetView(TargetComponents components, List<Target> targets) {
		this(components, Optional.empty(), Optional.of(targets), false);
	}

	private TargetView(TargetComponents components, Optional<CanvasManager> parent, Optional<List<Target>> targets,
			boolean userDeletable) {
		this.components = components;
		targetGroup = components.getTargetGroup();

		final int regionCount = components.getDefinition().regions().size();
		if (targetGroup.getChildren().size() < regionCount) {
			throw new IllegalArgumentException("The target group has fewer nodes than the target has regions");
		}
		regionNodes = List.copyOf(targetGroup.getChildren().subList(0, regionCount));

		targetTags = components.getTargetTags();
		config = parent.isPresent() ? Optional.ofNullable(Configuration.getConfig()) : Optional.empty();
		this.parent = parent;
		this.targets = targets;
		this.userDeletable = userDeletable;
		cameraName = parent.map(CanvasManager::getCameraName).orElse(null);

		// The model's scale is drawn by this transform. setAll: a group shared with an earlier view
		// (MirroredCanvasManager) keeps one scale.
		targetGroup.setScaleX(1);
		targetGroup.setScaleY(1);
		targetGroup.getTransforms().setAll(scale);

		final TargetSet privateSet = new TargetSet();
		membership = new Membership(privateSet, privateSet.add(components.getDefinition()));
		privateSet.addListener(placementListener);
		applyPlacement(membership.placed());
		watchImageFrames();
		pushImageMasks();

		mousePressed();
		mouseDragged();
		mouseMoved();
		mouseReleased();
		keyPressed();
	}

	public boolean isUserDeletable() {
		return userDeletable;
	}

	@Override
	public File getTargetFile() {
		return components.getTargetFile();
	}

	public Group getTargetGroup() {
		return targetGroup;
	}

	public TargetComponents getComponents() {
		return components;
	}

	/**
	 * @return the set this target is in: its canvas's set once added to a canvas, otherwise a
	 *         private one
	 */
	public TargetSet getTargetSet() {
		return membership.set();
	}

	public PlacedTarget getPlacedTarget() {
		return membership.placed();
	}

	public Placement getPlacement() {
		return membership.placed().getPlacement();
	}

	/**
	 * Sets the position, scale and visibility at once, without mirroring or session events (used
	 * to copy one view's placement to another).
	 */
	public final void setPlacement(Placement placement) {
		final Membership m = membership;
		m.set().place(m.placed().getId(), placement);
	}

	/**
	 * Moves this target's model into <tt>set</tt>, keeping its placement. CanvasManager calls this
	 * when it adds the target.
	 */
	public void joinTargetSet(TargetSet set) {
		final Membership old = membership;
		if (old.set() == set) return;

		final PlacedTarget placed = set.add(components.getDefinition(), old.placed().getPlacement());
		old.set().removeListener(placementListener);
		old.set().remove(old.placed().getId());
		membership = new Membership(set, placed);
		set.addListener(placementListener);
		applyPlacement(placed);
		pushImageMasks();
	}

	/**
	 * Moves this target's model back into a private set, keeping its placement. CanvasManager
	 * calls this when it removes the target.
	 */
	public void leaveTargetSet() {
		joinTargetSet(new TargetSet());
	}

	// The JavaFX side of a placement: layout from the position, the Scale transform from the scale
	// about the model's pivot, and unresizable regions scaled back to their own size
	private void applyPlacement(PlacedTarget target) {
		final Placement p = target.getPlacement();
		final Point pivot = target.getPivot();

		targetGroup.setLayoutX(p.x());
		targetGroup.setLayoutY(p.y());
		scale.setPivotX(pivot.getX());
		scale.setPivotY(pivot.getY());
		scale.setX(p.scaleX());
		scale.setY(p.scaleY());
		targetGroup.setVisible(p.visible());

		final List<Region> regions = target.getDefinition().regions();
		for (int i = 0; i < regions.size(); i++) {
			if (!regions.get(i).isResizable()) {
				regionNodes.get(i).setScaleX(1 / p.scaleX());
				regionNodes.get(i).setScaleY(1 / p.scaleY());
			}
		}
	}

	// The hit tester reads each image region's current frame, which animations and resets change
	private void watchImageFrames() {
		for (int i = 0; i < regionNodes.size(); i++) {
			if (regionNodes.get(i) instanceof ImageRegion imageRegion) {
				final int regionIndex = i;
				imageRegion.imageProperty()
						.addListener((observable, oldImage, newImage) -> pushImageMask(regionIndex, newImage));
			}
		}
	}

	private void pushImageMasks() {
		for (int i = 0; i < regionNodes.size(); i++) {
			if (regionNodes.get(i) instanceof ImageRegion imageRegion) pushImageMask(i, imageRegion.getImage());
		}
	}

	private void pushImageMask(int regionIndex, Image image) {
		if (image == null) return;

		final Membership m = membership;
		m.set().setImageMask(m.placed().getId(), regionIndex, FxAlphaMasks.of(image));
	}

	// Only the canvas that records session events records this target, and only once the
	// target is registered on it (index -1 means it isn't yet)
	private boolean shouldRecordSessionEvents() {
		return config.isPresent() && config.get().getSessionRecorder().isPresent() && parent.isPresent()
				&& parent.get().recordsSessionEvents() && getTargetIndex() >= 0;
	}

	// For resizes applied through a mirror (see MirroredTarget.mirrorSetDimensions), whose own
	// handlers ran on the non-recording canvas
	protected void recordResize(double newWidth, double newHeight) {
		if (shouldRecordSessionEvents()) {
			config.get().getSessionRecorder().get().recordTargetResized(cameraName, this, newWidth, newHeight);
		}
	}

	private void recordMoved() {
		if (shouldRecordSessionEvents()) {
			final Placement p = getPlacement();
			config.get().getSessionRecorder().get().recordTargetMoved(cameraName, this, (int) p.x(), (int) p.y());
		}
	}

	private void recordResized() {
		if (shouldRecordSessionEvents()) {
			final Size size = membership.placed().getSize();
			config.get().getSessionRecorder().get().recordTargetResized(cameraName, this, size.getWidth(),
					size.getHeight());
		}
	}

	@Override
	public int getTargetIndex() {
		if (parent.isPresent())
			return parent.get().getTargets().indexOf(this);
		else
			return -1;
	}

	@Override
	public void fillParent() {
		if (parent.isPresent()) {
			final Bounds b = parent.get().getCanvasGroup().getBoundsInParent();
			setDimensions(b.getWidth(), b.getHeight());
			final Point p = membership.placed().localToParent(0, 0);
			setPosition(p.getX() * -1, p.getY() * -1);
		}
	}

	// Drawn only: children added here aren't regions of the target's model
	@Override
	public void addTargetChild(Node child) {
		getTargetGroup().getChildren().add(child);
	}

	@Override
	public void removeTargetChild(Node child) {
		getTargetGroup().getChildren().remove(child);
	}

	@Override
	public List<TargetRegion> getRegions() {
		final List<TargetRegion> regions = new ArrayList<>();

		for (final Node n : regionNodes) {
			regions.add((TargetRegion) n);
		}

		return regions;
	}

	@Override
	public boolean hasRegion(TargetRegion region) {
		return regionNodes.contains(region);
	}

	@Override
	public void setVisible(boolean isVisible) {
		final Membership m = membership;
		m.set().setVisible(m.placed().getId(), isVisible);
	}

	@Override
	public boolean isVisible() {
		return getPlacement().visible();
	}

	@Override
	public void setPosition(double x, double y) {
		final Membership m = membership;
		m.set().move(m.placed().getId(), x, y);
		recordMoved();
	}

	@Override
	public Point2D getPosition() {
		final Placement p = getPlacement();
		return new Point2D(p.x(), p.y());
	}

	@Override
	public void setDimensions(double newWidth, double newHeight) {
		final Membership m = membership;
		m.set().resize(m.placed().getId(), newWidth, newHeight);
	}

	@Override
	public Dimension2D getDimension() {
		final Size size = membership.placed().getSize();
		return new Dimension2D(size.getWidth(), size.getHeight());
	}

	@Override
	public double getScaleX() {
		return getPlacement().scaleX();
	}

	@Override
	public double getScaleY() {
		return getPlacement().scaleY();
	}

	@Override
	public void scale(double widthFactor, double heightFactor) {
		final double newWidth = getDimension().getWidth() * widthFactor;
		final double widthDelta = newWidth - getDimension().getWidth();
		final double newX = getBoundsInParent().getMinX() * widthFactor;
		final double deltaX = newX - getBoundsInParent().getMinX() + (widthDelta / 2);

		final double newHeight = getDimension().getHeight() * heightFactor;
		final double heightDelta = newHeight - getDimension().getHeight();
		final double newY = getBoundsInParent().getMinY() * heightFactor;
		final double deltaY = newY - getBoundsInParent().getMinY() + (heightDelta / 2);

		setPosition(getPosition().getX() + deltaX, getPosition().getY() + deltaY);

		setDimensions(newWidth, newHeight);
	}

	@Override
	public Bounds getBoundsInParent() {
		return FxGeometry.toBounds(membership.placed().getBounds());
	}

	@Override
	public Point2D parentToLocal(double x, double y) {
		final Point p = membership.placed().parentToLocal(x, y);
		return new Point2D(p.getX(), p.getY());
	}

	@Override
	public void setClip(Rectangle clip) {
		getTargetGroup().setClip(clip);
	}

	/**
	 * Sets whether or not the target should stay in the bounds of its parent.
	 * 
	 * @param keepInBounds
	 *            <tt>true</tt> if the target should stay in bounds,
	 *            <tt>false</tt> otherwise.
	 */
	public void setKeepInBounds(boolean keepInBounds) {
		this.keepInBounds = keepInBounds;
	}

	public boolean getKeepInBounds() {
		return keepInBounds;
	}

	// Unchanged from here to toggleSelected
	public static void parseCommandTag(TargetRegion region, CommandProcessor commandProcessor) {
		if (!region.tagExists("command")) return;

		final String commandsSource = region.getTag("command");
		final List<String> commands = Arrays.asList(commandsSource.split(";"));

		for (final String command : commands) {
			final int openParen = command.indexOf('(');
			String commandName;
			List<String> args;

			if (openParen > 0) {
				commandName = command.substring(0, openParen);
				args = Arrays.asList(command.substring(openParen + 1, command.indexOf(')')).split(","));
			} else {
				commandName = command;
				args = new ArrayList<>();
			}

			commandProcessor.process(commands, commandName, args);
		}
	}

	public static Optional<TargetRegion> getTargetRegionByName(List<Target> targets, TargetRegion region, String name) {
		for (final Target target : targets) {
			if (target.hasRegion(region)) {
				for (final TargetRegion r : target.getRegions()) {
					if (r.tagExists("name") && r.getTag("name").equals(name)) return Optional.of(r);
				}
			}
		}

		return Optional.empty();
	}

	@Override
	public void animate(TargetRegion region, List<String> args) {
		ImageRegion imageRegion;

		boolean resetAfterAnimation = false;

		if (args.size() == 0) {
			imageRegion = (ImageRegion) region;
		} else if (args.get(0).equals("true")) {
			imageRegion = (ImageRegion) region;
			resetAfterAnimation = true;
		} else {
			Optional<TargetRegion> r;

			if (targets.isPresent()) {
				r = getTargetRegionByName(targets.get(), region, args.get(0));
			} else if (parent.isPresent()) {
				r = getTargetRegionByName(parent.get().getTargets(), region, args.get(0));
			} else {
				r = Optional.empty();
			}

			if (r.isPresent()) {
				imageRegion = (ImageRegion) r.get();
			} else {
				logger.error("Request to animate region named {}, but it doesn't exist.", args.get(0));
				return;
			}
		}

		// Don't repeat animations for fallen targets
		if (!imageRegion.onFirstFrame()) return;

		if (imageRegion.getAnimation().isPresent()) {
			final SpriteAnimation animation = imageRegion.getAnimation().get();
			animation.play();

			if (resetAfterAnimation) {
				animation.setOnFinished((e) -> {
					animation.reset();
					animation.setOnFinished(null);
				});
			}
		} else {
			logger.error("Request to animate region, but region does not contain an animation.");
		}
	}

	@Override
	public void reverseAnimation(TargetRegion region) {
		if (region.getType() != RegionType.IMAGE) {
			logger.error("A reversal was requested on a non-image region.");
			return;
		}

		final ImageRegion imageRegion = (ImageRegion) region;
		if (imageRegion.getAnimation().isPresent()) {
			final SpriteAnimation animation = imageRegion.getAnimation().get();

			if (animation.getStatus() == Status.RUNNING) {
				animation.setOnFinished((e) -> {
					animation.reverse();
					animation.setOnFinished(null);
				});
			} else {
				animation.reverse();
			}
		} else {
			logger.error("A reversal was requested on an image region that isn't animated.");
		}
	}

	public void toggleSelected() {
		isSelected = !isSelected;

		final Color stroke = isSelected ? TargetRegion.SELECTED_STROKE_COLOR : TargetRegion.UNSELECTED_STROKE_COLOR;

		for (final Node node : getTargetGroup().getChildren()) {
			if (!(node instanceof TargetRegion)) continue;

			final TargetRegion region = (TargetRegion) node;
			if (region.getType() != RegionType.IMAGE) {
				((Shape) region).setStroke(stroke);
			}
		}

		if (isSelected) {
			addResizeAnchors();
		} else {
			getTargetGroup().getChildren().removeAll(resizeAnchors);
			resizeAnchors.clear();
		}

		if (selectionListener != null) selectionListener.targetSelected(this, isSelected);
	}

	@Override
	public void setTargetSelectionListener(TargetSelectionListener selectionListener) {
		this.selectionListener = selectionListener;
	}

	public interface TargetSelectionListener {
		void targetSelected(Target target, boolean isSelected);
	}

	public boolean isSelected() {
		return isSelected;
	}

	private void addResizeAnchors() {
		final Bounds localBounds = getTargetGroup().getBoundsInLocal();
		final double horizontalMiddle = localBounds.getMinX() + (localBounds.getWidth() / 2) - (ANCHOR_WIDTH / 2);
		final double verticleMiddle = localBounds.getMinY() + (localBounds.getHeight() / 2) - (ANCHOR_HEIGHT / 2);

		// Top left
		addAnchor(localBounds.getMinX(), localBounds.getMinY());
		// Top middle
		addAnchor(horizontalMiddle, localBounds.getMinY());
		// Top right
		addAnchor(localBounds.getMaxX() - ANCHOR_WIDTH, localBounds.getMinY());
		// Middle left
		addAnchor(localBounds.getMinX(), verticleMiddle);
		// Middle right
		addAnchor(localBounds.getMaxX() - ANCHOR_WIDTH, verticleMiddle);
		// Bottom left
		addAnchor(localBounds.getMinX(), localBounds.getMaxY() - ANCHOR_HEIGHT);
		// Bottom middle
		addAnchor(horizontalMiddle, localBounds.getMaxY() - ANCHOR_HEIGHT);
		// Bottom right
		addAnchor(localBounds.getMaxX() - ANCHOR_WIDTH, localBounds.getMaxY() - ANCHOR_HEIGHT);
	}

	private RectangleRegion addAnchor(final double x, final double y) {
		final RectangleRegion anchor = new RectangleRegion(x, y, ANCHOR_WIDTH, ANCHOR_HEIGHT);

		// Make the anchor regions unshootable and unresizable
		final Map<String, String> regionTags = ((TargetRegion) anchor).getAllTags();
		regionTags.put(TargetView.TAG_IGNORE_HIT, "true");
		regionTags.put(TargetView.TAG_RESIZABLE, "false");

		anchor.setFill(Color.GOLD);
		anchor.setStroke(Color.BLACK);

		getTargetGroup().getChildren().add(anchor);

		// Ensure anchors appear the intended visual size even if the target
		// has been scaled
		final Placement p = getPlacement();

		if (p.scaleX() != 1.0f) {
			final double scaledPercentChange = (ANCHOR_WIDTH / (ANCHOR_WIDTH * p.scaleX()));
			anchor.setScaleX(scaledPercentChange);
		}

		if (p.scaleY() != 1.0f) {
			final double scaledPercentChange = (ANCHOR_HEIGHT / (ANCHOR_HEIGHT * p.scaleY()));
			anchor.setScaleY(scaledPercentChange);
		}

		resizeAnchors.add(anchor);

		return anchor;
	}

	/**
	 * @return a model hit on this target as the v1 API reports it: the region's JavaFX node, and
	 *         the impact relative to the region's bounds on the canvas, in whole pixels
	 */
	public Hit toHit(com.shootoff.targets.model.Hit hit, double x, double y) {
		final Rect regionBounds = membership.placed().regionBounds(hit.region());

		return new Hit(this, (TargetRegion) regionNodes.get(hit.region().index()),
				(int) (x - regionBounds.getMinX()), (int) (y - regionBounds.getMinY()));
	}

	/**
	 * The v1 hit test for this target alone: the core hit tester, ignoring whether the target is
	 * visible, as this method always has.
	 */
	@Override
	public Optional<Hit> isHit(double x, double y) {
		return HitTester.hit(membership.placed(), x, y).map(hit -> toHit(hit, x, y));
	}

	// Unchanged
	private void mousePressed() {
		targetGroup.setOnMousePressed((event) -> {
			if (!isInResizeZone(event)) {
				move = true;

				return;
			}

			resize = true;
			top = isTopZone(event);
			bottom = isBottomZone(event);
			left = isLeftZone(event);
			right = isRightZone(event);
		});
	}

	// The old handler's arithmetic, step for step, on the model's placement and bounds: the
	// candidate placement is checked against the display, then applied through the set once
	private void mouseDragged() {
		targetGroup.setOnMouseDragged((event) -> {

			if (!resize && !move) return;

			final Membership m = membership;
			final PlacedTarget placed = m.placed();
			final Placement start = placed.getPlacement();

			if (move) {
				if (config.isPresent() && config.get().inDebugMode() && (event.isControlDown() || event.isShiftDown()))
					return;

				final double deltaX = event.getX() - x;
				final double deltaY = event.getY() - y;
				final Rect bounds = placed.getBounds();
				double newX = start.x();
				double newY = start.y();

				if (!keepInBounds || (bounds.getMinX() + deltaX >= 0
						&& bounds.getMaxX() + deltaX <= config.get().getDisplayWidth())) {

					newX = start.x() + (deltaX * start.scaleX());
				}

				if (!keepInBounds || (bounds.getMinY() + deltaY >= 0
						&& bounds.getMaxY() + deltaY <= config.get().getDisplayHeight())) {

					newY = start.y() + (deltaY * start.scaleY());
				}

				m.set().move(placed.getId(), newX, newY);
				recordMoved();

				return;
			}

			final boolean fixedAspectRatioResize = (top || bottom) && (left || right) && event.isControlDown();
			double aspectScaleDelta = 0.0;
			final Rect local = placed.getLocalBounds();
			Placement current = start;

			if (left || right) {
				// The gap between the mouse and nearest target edge
				final double gap;

				if (right) {
					gap = (event.getX() - local.getMaxX()) * current.scaleX();
				} else {
					gap = (event.getX() - local.getMinX()) * current.scaleX();
				}

				final Rect bounds = placed.boundsAt(current);
				final double currentWidth = bounds.getWidth();
				final double newWidth = currentWidth + gap;

				double scaleDelta = (newWidth - currentWidth) / currentWidth;

				if (fixedAspectRatioResize) aspectScaleDelta = scaleDelta;

				final double currentOriginX = bounds.getMinX();
				final double newOriginX;

				if (right) {
					scaleDelta *= -1.0;
					newOriginX = currentOriginX - ((newWidth - currentWidth) / 2);
				} else {
					newOriginX = currentOriginX + ((newWidth - currentWidth) / 2);
				}

				double originXDelta = newOriginX - currentOriginX;

				if (right) originXDelta *= -1.0;

				final double newScaleX = current.scaleX() * (1.0 - scaleDelta);

				// If we scale too small the target can do weird things
				if (newScaleX < 0.001 || Double.isNaN(newScaleX) || Double.isInfinite(newScaleX)) return;

				final Placement resized = new Placement(current.x() + originXDelta, current.y(), newScaleX,
						current.scaleY(), current.visible());
				final Rect resizedBounds = placed.boundsAt(resized);

				// If the target would go out of bounds, it keeps its old size
				if (!keepInBounds || !(resizedBounds.getMinX() <= 0
						|| resizedBounds.getMaxX() >= config.get().getDisplayWidth())) {
					current = resized;
				}
			}

			if (top || bottom) {
				final double gap;

				if (bottom) {
					gap = (event.getY() - local.getMaxY()) * current.scaleY();
				} else {
					gap = (event.getY() - local.getMinY()) * current.scaleY();
				}

				final Rect bounds = placed.boundsAt(current);
				final double currentHeight = bounds.getHeight();
				double newHeight = currentHeight + gap;

				if (fixedAspectRatioResize) {
					if ((left && bottom) || (right && top)) aspectScaleDelta *= -1.0;

					newHeight = currentHeight + (currentHeight * aspectScaleDelta);
				}

				double scaleDelta = (newHeight - currentHeight) / currentHeight;

				final double currentOriginY = bounds.getMinY();
				final double newOriginY;

				if (bottom) {
					scaleDelta *= -1.0;
					newOriginY = currentOriginY - ((newHeight - currentHeight) / 2);
				} else {
					newOriginY = currentOriginY + ((newHeight - currentHeight) / 2);
				}

				double originYDelta = newOriginY - currentOriginY;

				if (bottom) originYDelta *= -1.0;

				final double newScaleY = current.scaleY() * (1.0 - scaleDelta);

				// If we scale too small the target can do weird things; a width change above still
				// applies, as it always did
				if (newScaleY < 0.001 || Double.isNaN(newScaleY) || Double.isInfinite(newScaleY)) {
					m.set().place(placed.getId(), current);
					return;
				}

				final Placement resized = new Placement(current.x(), current.y() + originYDelta, current.scaleX(),
						newScaleY, current.visible());
				final Rect resizedBounds = placed.boundsAt(resized);

				if (!keepInBounds || !(resizedBounds.getMinY() <= 0
						|| resizedBounds.getMaxY() >= config.get().getDisplayHeight())) {
					current = resized;
				}
			}

			m.set().place(placed.getId(), current);
			recordMoved();
			recordResized();
		});
	}

	// Unchanged
	private void mouseMoved() {
		targetGroup.setOnMouseMoved((event) -> {
			x = event.getX();
			y = event.getY();

			if (isTopZone(event) && isLeftZone(event)) {
				targetGroup.setCursor(Cursor.NW_RESIZE);
			} else if (isTopZone(event) && isRightZone(event)) {
				targetGroup.setCursor(Cursor.NE_RESIZE);
			} else if (isBottomZone(event) && isLeftZone(event)) {
				targetGroup.setCursor(Cursor.SW_RESIZE);
			} else if (isBottomZone(event) && isRightZone(event)) {
				targetGroup.setCursor(Cursor.SE_RESIZE);
			} else if (isTopZone(event)) {
				targetGroup.setCursor(Cursor.N_RESIZE);
			} else if (isBottomZone(event)) {
				targetGroup.setCursor(Cursor.S_RESIZE);
			} else if (isLeftZone(event)) {
				targetGroup.setCursor(Cursor.W_RESIZE);
			} else if (isRightZone(event)) {
				targetGroup.setCursor(Cursor.E_RESIZE);
			} else {
				targetGroup.setCursor(Cursor.DEFAULT);
			}
		});
	}

	// Unchanged
	private void mouseReleased() {
		targetGroup.setOnMouseReleased((event) -> {
			resize = false;
			move = false;
			targetGroup.setCursor(Cursor.DEFAULT);
		});
	}

	// The old handler's arithmetic on the model's placement and bounds
	private void keyPressed() {
		targetGroup.setOnKeyPressed((event) -> {
			final Membership m = membership;
			final Placement p = m.placed().getPlacement();
			final Rect bounds = m.placed().getBounds();
			final double currentWidth = bounds.getWidth();
			final double currentHeight = bounds.getHeight();

			switch (event.getCode()) {
			case DELETE:
			case BACK_SPACE:
				if (userDeletable && parent.isPresent()) parent.get().removeTarget(this);
				break;

			case LEFT: {
				if (event.isShiftDown()) {
					final double newWidth = currentWidth - SCALE_DELTA;
					final double scaleDelta = (newWidth - currentWidth) / currentWidth;

					m.set().scale(m.placed().getId(), p.scaleX() * (1.0 - scaleDelta), p.scaleY());
					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinX() - MOVEMENT_DELTA >= 0
							&& bounds.getMaxX() - MOVEMENT_DELTA <= config.get().getDisplayWidth())) {

						m.set().move(m.placed().getId(), p.x() - MOVEMENT_DELTA, p.y());
					}

					recordMoved();
				}
			}

				break;

			case RIGHT: {
				if (event.isShiftDown()) {
					final double newWidth = currentWidth + SCALE_DELTA;
					final double scaleDelta = (newWidth - currentWidth) / currentWidth;

					if (!keepInBounds || (bounds.getMinX() + (SCALE_DELTA / 2) >= 0
							&& bounds.getMaxX() + (SCALE_DELTA / 2) <= config.get().getDisplayWidth())) {
						m.set().scale(m.placed().getId(), p.scaleX() * (1.0 - scaleDelta), p.scaleY());
					}

					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinX() + MOVEMENT_DELTA >= 0
							&& bounds.getMaxX() + MOVEMENT_DELTA <= config.get().getDisplayWidth())) {

						m.set().move(m.placed().getId(), p.x() + MOVEMENT_DELTA, p.y());
					}

					recordMoved();
				}
			}

				break;

			case UP: {
				if (event.isShiftDown()) {
					final double newHeight = currentHeight - SCALE_DELTA;
					final double scaleDelta = (newHeight - currentHeight) / currentHeight;
					double scaleX = p.scaleX();

					// Scale up proportionally if ctrl is down
					if (event.isControlDown()) {
						final double newWidth = currentWidth - (SCALE_DELTA * (currentWidth / currentHeight));
						final double widthDelta = (newWidth - currentWidth) / currentWidth;

						scaleX = scaleX * (1.0 - widthDelta);
					}

					m.set().scale(m.placed().getId(), scaleX, p.scaleY() * (1.0 - scaleDelta));
					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinY() - MOVEMENT_DELTA >= 0
							&& bounds.getMaxY() - MOVEMENT_DELTA <= config.get().getDisplayHeight())) {

						m.set().move(m.placed().getId(), p.x(), p.y() - MOVEMENT_DELTA);
					}

					recordMoved();
				}
			}

				break;

			case DOWN: {
				if (event.isShiftDown()) {
					final double newHeight = currentHeight + SCALE_DELTA;
					final double scaleDelta = (newHeight - currentHeight) / currentHeight;

					if (!keepInBounds || (bounds.getMinY() + (SCALE_DELTA / 2) >= 0
							&& bounds.getMaxY() + (SCALE_DELTA / 2) <= config.get().getDisplayHeight())) {
						double scaleX = p.scaleX();

						// Scale down proportionally if ctrl is down
						if (event.isControlDown()) {
							final double newWidth = currentWidth + (SCALE_DELTA * (currentWidth / currentHeight));
							final double widthDelta = (newWidth - currentWidth) / currentWidth;

							scaleX = scaleX * (1.0 - widthDelta);
						}

						m.set().scale(m.placed().getId(), scaleX, p.scaleY() * (1.0 - scaleDelta));
					}

					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinY() + MOVEMENT_DELTA >= 0
							&& bounds.getMaxY() + MOVEMENT_DELTA <= config.get().getDisplayHeight())) {

						m.set().move(m.placed().getId(), p.x(), p.y() + MOVEMENT_DELTA);
					}

					recordMoved();
				}
			}

				break;

			default:
				break;
			}
			event.consume();
		});
	}

	// The resize zones are measured on the target's regions, not on selection decorations
	private Rect localBounds() {
		return membership.placed().getLocalBounds();
	}

	private boolean isTopZone(MouseEvent event) {
		return event.getY() < (localBounds().getMinY() + RESIZE_MARGIN);
	}

	private boolean isBottomZone(MouseEvent event) {
		return event.getY() > (localBounds().getMaxY() - RESIZE_MARGIN);
	}

	private boolean isLeftZone(MouseEvent event) {
		return event.getX() < (localBounds().getMinX() + RESIZE_MARGIN);
	}

	private boolean isRightZone(MouseEvent event) {
		return event.getX() > (localBounds().getMaxX() - RESIZE_MARGIN);
	}

	private boolean isInResizeZone(MouseEvent event) {
		return isTopZone(event) || isBottomZone(event) || isLeftZone(event) || isRightZone(event);
	}

	@Override
	public boolean tagExists(String name) {
		return targetTags.containsKey(name);
	}

	@Override
	public String getTag(String name) {
		return targetTags.get(name);
	}

	@Override
	public Map<String, String> getAllTags() {
		return targetTags;
	}
}
