/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.input.calibration;

import dev.isxander.controlify.controller.input.ControllerStateView;
import dev.isxander.controlify.controller.input.DeadzoneGroup;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;

import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers raw axis readings while the calibration wizard runs and turns them into
 * {@link AxisCalibration}s and recommended deadzones.
 *
 * <p>Sampling happens in two flavours. The <em>rest</em> pass watches an untouched controller to
 * find where each axis settles and how much it jitters; the <em>range</em> pass watches the user
 * exercise a stick or trigger to find how far it can actually travel. Everything here works on
 * raw, uncalibrated readings — feeding it already-calibrated values would compound corrections.
 */
public class CalibrationRecorder {
	/** A rest sample spread wider than this means the controller was not left alone. */
	public static final float MOVEMENT_TOLERANCE = 0.25f;

	/** Recommended deadzones are padded by this much over the measured jitter. */
	public static final float DEADZONE_SAFETY_FACTOR = 1.5f;
	public static final float DEADZONE_MARGIN = 0.02f;
	public static final float MAX_RECOMMENDED_DEADZONE = 0.5f;

	/** How far an axis must travel from rest before we trust its measured extreme. */
	public static final float EXPLORED_REACH = 0.8f;

	/** Below this, an axis is assumed to only travel one way (a trigger, or a split stick axis). */
	public static final float SIGNED_AXIS_REACH = AxisCalibration.BIPOLAR_SPAN;

	/** A stick is considered fully explored once this many of the sectors below are reached. */
	public static final int COVERAGE_SECTORS = 16;
	public static final int REQUIRED_SECTORS = 14;
	public static final float COVERAGE_RADIUS = 0.7f;

	private final Map<Identifier, AxisSamples> samples = new LinkedHashMap<>();
	private final Map<Identifier, BitSet> coverage = new LinkedHashMap<>();

	/** Records one tick of an untouched controller across every axis it reports. */
	public void sampleRest(ControllerStateView state) {
		for (Identifier axis : state.getAxes()) {
			samplesFor(axis).addRest(state.getAxisState(axis));
		}
	}

	/** Records one tick of the given axes being exercised through their full travel. */
	public void sampleRange(ControllerStateView state, Collection<Identifier> axes) {
		for (Identifier axis : axes) {
			samplesFor(axis).addRange(state.getAxisState(axis));
		}
	}

	/**
	 * Notes which direction of a two-dimensional stick has been reached, so the wizard can tell a
	 * user who only pushed the stick left and right from one who rolled it all the way round.
	 */
	public void sampleCoverage(DeadzoneGroup group, ControllerStateView state) {
		Vector2f position = stickPosition(state, group);
		if (position == null || position.length() < COVERAGE_RADIUS) {
			return;
		}

		float angle = (float) Math.atan2(position.y(), position.x());
		int sector = Math.floorMod(
				Mth.floor((angle + Mth.PI) / Mth.TWO_PI * COVERAGE_SECTORS),
				COVERAGE_SECTORS
		);
		coverage.computeIfAbsent(group.name(), name -> new BitSet(COVERAGE_SECTORS)).set(sector);
	}

	public void resetRest() {
		samples.values().forEach(AxisSamples::clearRest);
	}

	/** Throws away everything learnt about these axes' travel, reverting them to full range. */
	public void resetRange(DeadzoneGroup group) {
		resetRange(group.axes());
		coverage.remove(group.name());
	}

	public void resetRange(Collection<Identifier> axes) {
		for (Identifier axis : axes) {
			samplesFor(axis).clearRange();
		}
	}

	public boolean hasRestSamples() {
		return samples.values().stream().anyMatch(AxisSamples::hasRest);
	}

	/** Whether the rest pass saw movement large enough that its readings cannot be trusted. */
	public boolean movedDuringRest() {
		return samples.values().stream().anyMatch(axis -> axis.restSpan() > MOVEMENT_TOLERANCE);
	}

	/** How much an axis wobbled while untouched — the raw size of its stick drift. */
	public float restNoise(Identifier axis) {
		AxisSamples axisSamples = samples.get(axis);
		return axisSamples == null ? 0f : axisSamples.restSpan();
	}

	/** Where an axis settled while untouched. */
	public float restOffset(Identifier axis) {
		AxisSamples axisSamples = samples.get(axis);
		return axisSamples == null || !axisSamples.hasRest() ? 0f : axisSamples.restMean();
	}

	/** The worst resting offset across a whole stick or trigger group. */
	public float worstRestOffset(Collection<Identifier> axes) {
		float worst = 0f;
		for (Identifier axis : axes) {
			worst = Math.max(worst, Math.abs(restOffset(axis)));
		}
		return worst;
	}

	public AxisCalibration calibrationFor(Identifier axis) {
		AxisSamples axisSamples = samples.get(axis);
		if (axisSamples == null || !axisSamples.hasRest()) {
			return AxisCalibration.IDENTITY;
		}

		float rest = axisSamples.restMean();
		if (!axisSamples.hasRange()) {
			// travel was never measured, so only correct the resting offset and leave the negative
			// side unmeasured, which passes it through rather than guessing at its extent
			return new AxisCalibration(rest, rest, 1f);
		}

		return new AxisCalibration(rest, axisSamples.rangeMin(), axisSamples.rangeMax());
	}

	/** Every non-trivial calibration gathered so far, ready to be stored against the device. */
	public Map<Identifier, AxisCalibration> calibrations() {
		Map<Identifier, AxisCalibration> result = new LinkedHashMap<>();
		for (Identifier axis : samples.keySet()) {
			AxisCalibration calibration = calibrationFor(axis);
			if (!calibration.isTrivial()) {
				result.put(axis, calibration);
			}
		}
		return result;
	}

	/**
	 * The deadzone that would just swallow this group's remaining jitter once calibrated.
	 *
	 * <p>The rest extremes are run through the calibration first, because correcting the resting
	 * offset also rescales the noise around it — measuring the raw wobble would over-recommend.
	 */
	public float recommendedDeadzone(DeadzoneGroup group) {
		float worst = 0f;
		for (Identifier axis : group.axes()) {
			AxisSamples axisSamples = samples.get(axis);
			if (axisSamples == null || !axisSamples.hasRest()) {
				continue;
			}

			AxisCalibration calibration = calibrationFor(axis);
			worst = Math.max(worst, Math.abs(calibration.apply(axisSamples.restMin())));
			worst = Math.max(worst, Math.abs(calibration.apply(axisSamples.restMax())));
		}

		float deadzone = worst * DEADZONE_SAFETY_FACTOR + DEADZONE_MARGIN;
		// round up to whole percent so a recommendation never lands under the measured jitter
		return Mth.clamp(Mth.ceil(deadzone * 100f) / 100f, 0f, MAX_RECOMMENDED_DEADZONE);
	}

	/** How far an axis has been pushed from rest, as a fraction of a full push. */
	public float reach(Identifier axis) {
		AxisSamples axisSamples = samples.get(axis);
		if (axisSamples == null || !axisSamples.hasRange()) {
			return 0f;
		}

		float rest = axisSamples.hasRest() ? axisSamples.restMean() : 0f;
		return Math.min(1f, Math.max(axisSamples.rangeMax() - rest, rest - axisSamples.rangeMin()));
	}

	/**
	 * Whether an axis has been exercised far enough to calibrate. An axis that has only ever moved
	 * one way from rest is taken at its word rather than waiting forever for a pull that a trigger
	 * or a split stick axis can never give.
	 */
	public boolean isExplored(Identifier axis) {
		AxisSamples axisSamples = samples.get(axis);
		if (axisSamples == null || !axisSamples.hasRange()) {
			return false;
		}

		float rest = axisSamples.hasRest() ? axisSamples.restMean() : 0f;
		float positive = axisSamples.rangeMax() - rest;
		float negative = rest - axisSamples.rangeMin();

		boolean positiveDone = positive >= EXPLORED_REACH;
		boolean negativeDone = negative < SIGNED_AXIS_REACH || negative >= EXPLORED_REACH;
		return positiveDone && negativeDone;
	}

	public int coverageCount(DeadzoneGroup group) {
		BitSet sectors = coverage.get(group.name());
		return sectors == null ? 0 : sectors.cardinality();
	}

	public boolean isSectorCovered(DeadzoneGroup group, int sector) {
		BitSet sectors = coverage.get(group.name());
		return sectors != null && sectors.get(sector);
	}

	/** Whether a whole group has been exercised enough for its readings to be worth keeping. */
	public boolean isGroupExplored(DeadzoneGroup group) {
		boolean axesExplored = group.axes().stream().allMatch(this::isExplored);
		if (!isTwoDimensional(group)) {
			return axesExplored;
		}
		return axesExplored && coverageCount(group) >= REQUIRED_SECTORS;
	}

	/** Progress through a group's range pass, for the wizard's progress bar. */
	public float groupProgress(DeadzoneGroup group) {
		List<Identifier> axes = group.axes();
		if (axes.isEmpty()) {
			return 1f;
		}

		float axisProgress = 0f;
		for (Identifier axis : axes) {
			axisProgress += Math.min(1f, reach(axis) / EXPLORED_REACH);
		}
		axisProgress /= axes.size();

		if (!isTwoDimensional(group)) {
			return Mth.clamp(axisProgress, 0f, 1f);
		}

		float sectorProgress = Math.min(1f, (float) coverageCount(group) / REQUIRED_SECTORS);
		return Mth.clamp((axisProgress + sectorProgress) / 2f, 0f, 1f);
	}

	private AxisSamples samplesFor(Identifier axis) {
		return samples.computeIfAbsent(axis, ignored -> new AxisSamples());
	}

	/** Whether a group describes a stick, i.e. has an up/down/left/right axis each. */
	public static boolean isTwoDimensional(DeadzoneGroup group) {
		return group.axes().size() == 4;
	}

	/**
	 * The current position of a stick, with x pointing right and y pointing down, or {@code null}
	 * if the group is not a stick. Axes are ordered up, down, left, right.
	 */
	public static @Nullable Vector2f stickPosition(ControllerStateView state, DeadzoneGroup group) {
		if (!isTwoDimensional(group)) {
			return null;
		}

		List<Identifier> axes = group.axes();
		float up = state.getAxisState(axes.get(0));
		float down = state.getAxisState(axes.get(1));
		float left = state.getAxisState(axes.get(2));
		float right = state.getAxisState(axes.get(3));
		return new Vector2f(right - left, down - up);
	}

	private static class AxisSamples {
		private int restCount;
		private double restSum;
		private float restMin = Float.POSITIVE_INFINITY;
		private float restMax = Float.NEGATIVE_INFINITY;

		private boolean hasRange;
		private float rangeMin = Float.POSITIVE_INFINITY;
		private float rangeMax = Float.NEGATIVE_INFINITY;

		void addRest(float value) {
			restCount++;
			restSum += value;
			restMin = Math.min(restMin, value);
			restMax = Math.max(restMax, value);
		}

		void addRange(float value) {
			hasRange = true;
			rangeMin = Math.min(rangeMin, value);
			rangeMax = Math.max(rangeMax, value);
		}

		void clearRest() {
			restCount = 0;
			restSum = 0;
			restMin = Float.POSITIVE_INFINITY;
			restMax = Float.NEGATIVE_INFINITY;
		}

		void clearRange() {
			hasRange = false;
			rangeMin = Float.POSITIVE_INFINITY;
			rangeMax = Float.NEGATIVE_INFINITY;
		}

		boolean hasRest() {
			return restCount > 0;
		}

		boolean hasRange() {
			return hasRange;
		}

		float restMean() {
			return restCount == 0 ? 0f : (float) (restSum / restCount);
		}

		float restMin() {
			return hasRest() ? restMin : 0f;
		}

		float restMax() {
			return hasRest() ? restMax : 0f;
		}

		float restSpan() {
			return hasRest() ? restMax - restMin : 0f;
		}

		float rangeMin() {
			return hasRange ? rangeMin : 0f;
		}

		float rangeMax() {
			return hasRange ? rangeMax : 0f;
		}
	}
}
