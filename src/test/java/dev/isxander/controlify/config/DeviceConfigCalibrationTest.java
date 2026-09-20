/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.isxander.controlify.config.dto.device.AxisCalibrationConfig;
import dev.isxander.controlify.config.dto.device.DeviceConfig;
import dev.isxander.controlify.config.dto.device.GyroCalibrationConfig;
import dev.isxander.controlify.controller.gyro.GyroStateC;
import dev.isxander.controlify.controller.input.calibration.AxisCalibration;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceConfigCalibrationTest {
	@Test
	void aDeviceSavedBeforeCalibrationExistedStillLoads() {
		JsonElement json = JsonParser.parseString("""
				{
					"name": "Xbox Controller",
					"last_seen": 1234,
					"gyro_calibration": {}
				}
				""");

		DeviceConfig config = decode(json);

		assertEquals(AxisCalibrationConfig.EMPTY, config.axisCalibration());
		assertTrue(config.axisCalibration().enabled(), "calibration is on by default, it just has nothing to apply");
		assertTrue(config.axisCalibration().axes().isEmpty());
	}

	@Test
	void calibrationSurvivesARoundTripThroughTheCodec() {
		Identifier axis = Identifier.fromNamespaceAndPath("controlify", "axis/left_stick_up");
		DeviceConfig original = new DeviceConfig(
				"Xbox Controller",
				1234L,
				Identifier.fromNamespaceAndPath("controlify", "xbox"),
				new GyroCalibrationConfig(GyroStateC.ZERO),
				new AxisCalibrationConfig(true, 5678L, Map.of(axis, new AxisCalibration(0.04f, 0f, 0.93f))),
				Optional.empty()
		);

		DeviceConfig decoded = decode(
				DeviceConfig.CODEC.encodeStart(JsonOps.INSTANCE, original)
						.result()
						.orElseThrow(() -> new AssertionError("failed to encode device config"))
		);

		assertEquals(original.axisCalibration(), decoded.axisCalibration());
	}

	private static DeviceConfig decode(JsonElement json) {
		return DeviceConfig.CODEC.parse(JsonOps.INSTANCE, json)
				.result()
				.orElseThrow(() -> new AssertionError("failed to decode device config"));
	}
}
