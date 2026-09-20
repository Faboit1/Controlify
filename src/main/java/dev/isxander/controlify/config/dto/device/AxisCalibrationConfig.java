/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config.dto.device;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.isxander.controlify.controller.input.calibration.AxisCalibration;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * The stick and trigger calibration measured for one physical device.
 *
 * <p>This lives against the device rather than the profile because it describes the hardware's
 * wear, not the player's taste — swapping profiles should not throw away a measured stick.
 */
public record AxisCalibrationConfig(
		boolean enabled,
		long calibratedAt,
		Map<Identifier, AxisCalibration> axes
) {
	public static final AxisCalibrationConfig EMPTY = new AxisCalibrationConfig(true, 0L, Map.of());

	public static final Codec<AxisCalibrationConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.BOOL.optionalFieldOf("enabled", true).forGetter(AxisCalibrationConfig::enabled),
			Codec.LONG.optionalFieldOf("calibrated_at", 0L).forGetter(AxisCalibrationConfig::calibratedAt),
			Codec.unboundedMap(Identifier.CODEC, AxisCalibration.CODEC).optionalFieldOf("axes", Map.of()).forGetter(AxisCalibrationConfig::axes)
	).apply(instance, AxisCalibrationConfig::new));

	public AxisCalibrationConfig {
		axes = Map.copyOf(axes);
	}
}
