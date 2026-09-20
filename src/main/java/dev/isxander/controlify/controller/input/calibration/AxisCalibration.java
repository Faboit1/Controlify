/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.controller.input.calibration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;

/**
 * A linear correction for a single axis, learnt by {@link CalibrationRecorder}.
 *
 * <p>{@code rest} is where the axis physically settles when untouched, and {@code min}/{@code max}
 * are the furthest values it could actually reach. Applying the calibration remaps that measured
 * travel back onto the full {@code -1..1} the rest of the mod expects, which removes resting offset
 * (stick drift) and compensates for sticks that cannot quite reach their extremes.
 */
public record AxisCalibration(float rest, float min, float max) {
	/** An axis side narrower than this was never properly exercised, so it is left untouched. */
	public static final float MIN_SPAN = 0.05f;

	/**
	 * How far below its resting point an axis must have been seen before we believe it really
	 * travels negative. Drift is never this large, and a genuinely two-way axis reaches about -1,
	 * so this cleanly separates a worn stick from a split axis that only ever reads 0 to 1.
	 */
	public static final float BIPOLAR_SPAN = 0.3f;

	/** The no-op calibration: a centred axis with full travel and no measured negative side. */
	public static final AxisCalibration IDENTITY = new AxisCalibration(0f, 0f, 1f);

	public static final Codec<AxisCalibration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.floatRange(-1f, 1f).fieldOf("rest").forGetter(AxisCalibration::rest),
			Codec.floatRange(-1f, 1f).fieldOf("min").forGetter(AxisCalibration::min),
			Codec.floatRange(-1f, 1f).fieldOf("max").forGetter(AxisCalibration::max)
	).apply(instance, AxisCalibration::new));

	public AxisCalibration {
		rest = Mth.clamp(rest, -1f, 1f);
		// the recorded travel always contains the resting point, even if sampling was cut short
		min = Math.min(Mth.clamp(min, -1f, 1f), rest);
		max = Math.max(Mth.clamp(max, -1f, 1f), rest);
	}

	/** Whether this axis was seen travelling meaningfully below its resting point. */
	public boolean isBipolar() {
		return rest - min >= BIPOLAR_SPAN;
	}

	/**
	 * Remaps a raw axis reading onto the axis' measured travel.
	 *
	 * <p>Each side of the resting point is scaled independently, so a stick that reaches 0.92 one
	 * way and 1.0 the other still produces a full-strength input in both directions. A side that
	 * was never exercised far enough to measure is passed through untouched rather than amplified.
	 */
	public float apply(float raw) {
		if (raw >= rest) {
			float span = max - rest;
			if (span < MIN_SPAN) {
				return Mth.clamp(raw, 0f, 1f);
			}
			return Mth.clamp((raw - rest) / span, 0f, 1f);
		}

		if (isBipolar()) {
			return -Mth.clamp((rest - raw) / (rest - min), 0f, 1f);
		}

		// Most of Controlify's axes only run 0 to 1 — a gamepad's stick is split into four of them,
		// and a trigger into one. Sitting below the resting point on such an axis means it is not
		// being pushed at all, so it must read zero rather than being stretched into a negative
		// input that the binding layer would happily act on. A reading that is already negative
		// belongs to an axis we never measured travelling that way, so it passes through untouched.
		return Math.min(0f, raw);
	}

	/** Whether this calibration would leave every reading exactly as it found it. */
	public boolean isTrivial() {
		return Math.abs(rest) < 1.0E-4f
				&& Math.abs(min) < 1.0E-4f
				&& Math.abs(max - 1f) < 1.0E-4f;
	}
}
