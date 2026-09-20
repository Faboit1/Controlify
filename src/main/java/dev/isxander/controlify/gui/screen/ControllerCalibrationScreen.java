/*
 * Copyright (C) 2026 isXander
 * This file is part of Controlify.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.isxander.controlify.gui.screen;

import dev.isxander.controlify.Controlify;
import dev.isxander.controlify.config.settings.device.AxisCalibrationSettings;
import dev.isxander.controlify.config.settings.device.DeviceSettings;
import dev.isxander.controlify.controller.ControllerEntity;
import dev.isxander.controlify.controller.input.ControllerStateView;
import dev.isxander.controlify.controller.input.DeadzoneGroup;
import dev.isxander.controlify.controller.input.InputComponent;
import dev.isxander.controlify.controller.input.calibration.AxisCalibration;
import dev.isxander.controlify.controller.input.calibration.CalibrationRecorder;
import dev.isxander.controlify.screenop.ScreenControllerEventListener;
import dev.isxander.controlify.screenop.ScreenProcessor;
import dev.isxander.controlify.screenop.ScreenProcessorProvider;
import dev.isxander.controlify.utils.ColorUtils;
import dev.isxander.controlify.utils.MinecraftUtil;
import dev.isxander.controlify.utils.render.RenderUtils;
import dev.isxander.controlify.utils.render.elements.CircleElementRenderState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A guided wizard that measures a controller's sticks and triggers.
 *
 * <p>It runs one rest pass to find stick drift, one pass per stick to find how far it really
 * travels, and one pass for anything left over (triggers and loose axes). The results become an
 * {@link AxisCalibration} per axis plus a recommended deadzone per stick, which the summary step
 * applies on the player's say-so.
 */
public class ControllerCalibrationScreen extends Screen implements ScreenControllerEventListener, ScreenProcessorProvider, DontInterruptScreen {
	/** Long enough to catch slow drift without making the player sit still for an age. */
	private static final int REST_SAMPLE_TICKS = 100;

	/** Grace period after a step appears, so the player can read it before sampling starts. */
	private static final int SETTLE_TICKS = 20;

	/** How long a finished step lingers, so the player sees that it worked. */
	private static final int HOLD_TICKS = 15;

	private static final int VISUAL_RADIUS = 52;

	private final InputComponent input;
	private final Screen lastScreen;
	private final @Nullable BiConsumer<Identifier, Float> deadzoneApplier;

	private final CalibrationRecorder recorder = new CalibrationRecorder();
	private final List<Step> steps;
	private final ScreenProcessor<ControllerCalibrationScreen> screenProcessor = new ScreenProcessorImpl(this);

	private int stepIndex;
	private int settleTicks = SETTLE_TICKS;
	private int restTicksRemaining = REST_SAMPLE_TICKS;
	private int holdTicks = -1;
	private boolean restDisturbed;
	private boolean applyDeadzones = true;

	public ControllerCalibrationScreen(
			InputComponent input,
			Screen lastScreen,
			@Nullable BiConsumer<Identifier, Float> deadzoneApplier
	) {
		super(Component.translatable("controlify.gui.calibration.title"));
		this.input = input;
		this.lastScreen = lastScreen;
		this.deadzoneApplier = deadzoneApplier;
		this.steps = buildSteps(input);
	}

	private static List<Step> buildSteps(InputComponent input) {
		List<Step> steps = new ArrayList<>();
		steps.add(Step.rest());

		Set<Identifier> grouped = new HashSet<>();
		for (DeadzoneGroup group : input.getDeadzoneGroups().values()) {
			grouped.addAll(group.axes());
			steps.add(Step.group(group));
		}

		List<Identifier> loose = input.rawStateNow().getAxes().stream()
				.filter(axis -> !grouped.contains(axis))
				.sorted(Comparator.comparing(Identifier::toString))
				.toList();
		if (!loose.isEmpty()) {
			steps.add(Step.loose(loose));
		}

		steps.add(Step.summary());
		return steps;
	}

	private Step currentStep() {
		return steps.get(Math.min(stepIndex, steps.size() - 1));
	}

	@Override
	protected void init() {
		Step step = currentStep();
		int rowY = height - 30;

		if (step.type() == Step.Type.SUMMARY) {
			addRenderableWidget(Button.builder(
							deadzoneToggleMessage(),
							button -> {
								applyDeadzones = !applyDeadzones;
								button.setMessage(deadzoneToggleMessage());
							})
					.bounds(width / 2 - 155, rowY - 24, 310, 20)
					.build());
			addRenderableWidget(Button.builder(
							Component.translatable("controlify.gui.calibration.apply"),
							button -> applyAndClose())
					.bounds(width / 2 - 155, rowY, 100, 20)
					.build());
			addRenderableWidget(Button.builder(
							Component.translatable("controlify.gui.calibration.start_over"),
							button -> restartWizard())
					.bounds(width / 2 - 50, rowY, 100, 20)
					.build());
			addRenderableWidget(Button.builder(
							CommonComponents.GUI_CANCEL,
							button -> onClose())
					.bounds(width / 2 + 55, rowY, 100, 20)
					.build());
			return;
		}

		if (step.type() == Step.Type.REST) {
			addRenderableWidget(Button.builder(
							Component.translatable("controlify.gui.calibration.restart_step"),
							button -> restartStep())
					.bounds(width / 2 - 152, rowY, 150, 20)
					.build());
			addRenderableWidget(Button.builder(
							CommonComponents.GUI_CANCEL,
							button -> onClose())
					.bounds(width / 2 + 2, rowY, 150, 20)
					.build());
			return;
		}

		addRenderableWidget(Button.builder(
						Component.translatable("controlify.gui.calibration.skip_step"),
						button -> skipStep())
				.bounds(width / 2 - 155, rowY, 100, 20)
				.build());
		addRenderableWidget(Button.builder(
						Component.translatable("controlify.gui.calibration.restart_step"),
						button -> restartStep())
				.bounds(width / 2 - 50, rowY, 100, 20)
				.build());
		addRenderableWidget(Button.builder(
						CommonComponents.GUI_CANCEL,
						button -> onClose())
				.bounds(width / 2 + 55, rowY, 100, 20)
				.build());
	}

	private Component deadzoneToggleMessage() {
		return Component.translatable(
				"controlify.gui.calibration.apply_deadzones",
				applyDeadzones ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF
		);
	}

	@Override
	public void tick() {
		Step step = currentStep();
		if (step.type() == Step.Type.SUMMARY) {
			return;
		}

		if (settleTicks > 0) {
			settleTicks--;
			return;
		}

		ControllerStateView raw = input.rawStateNow();

		switch (step.type()) {
			case REST -> {
				recorder.sampleRest(raw);
				if (recorder.movedDuringRest()) {
					// something was being held or knocked; the readings so far are worthless
					recorder.resetRest();
					restTicksRemaining = REST_SAMPLE_TICKS;
					restDisturbed = true;
					return;
				}

				if (--restTicksRemaining <= 0) {
					advance();
				}
			}
			case RANGE -> {
				recorder.sampleRange(raw, step.axes());
				if (step.group() != null) {
					recorder.sampleCoverage(step.group(), raw);
				}

				if (isStepComplete(step)) {
					if (holdTicks < 0) {
						holdTicks = HOLD_TICKS;
					} else if (--holdTicks <= 0) {
						advance();
					}
				}
			}
			case SUMMARY -> {
			}
		}
	}

	private boolean isStepComplete(Step step) {
		if (step.group() != null) {
			return recorder.isGroupExplored(step.group());
		}
		return step.axes().stream().allMatch(recorder::isExplored);
	}

	private float stepProgress(Step step) {
		return switch (step.type()) {
			case REST -> 1f - (float) restTicksRemaining / REST_SAMPLE_TICKS;
			case RANGE -> {
				if (step.group() != null) {
					yield recorder.groupProgress(step.group());
				}

				float total = 0f;
				for (Identifier axis : step.axes()) {
					total += Math.min(1f, recorder.reach(axis) / CalibrationRecorder.EXPLORED_REACH);
				}
				yield step.axes().isEmpty() ? 1f : total / step.axes().size();
			}
			case SUMMARY -> 1f;
		};
	}

	private void advance() {
		stepIndex = Math.min(stepIndex + 1, steps.size() - 1);
		enterStep();
	}

	private void enterStep() {
		settleTicks = SETTLE_TICKS;
		holdTicks = -1;
		restDisturbed = false;
		restTicksRemaining = REST_SAMPLE_TICKS;
		rebuildWidgets();
	}

	private void restartStep() {
		Step step = currentStep();
		if (step.type() == Step.Type.REST) {
			recorder.resetRest();
		} else {
			resetRange(step);
		}
		enterStep();
	}

	/** Leaves a step's axes uncalibrated for travel, so hardware we could not measure is untouched. */
	private void skipStep() {
		resetRange(currentStep());
		advance();
	}

	private void resetRange(Step step) {
		if (step.group() != null) {
			recorder.resetRange(step.group());
		} else {
			recorder.resetRange(step.axes());
		}
	}

	private void restartWizard() {
		stepIndex = 0;
		recorder.resetRest();
		for (Step step : steps) {
			if (step.type() == Step.Type.RANGE) {
				resetRange(step);
			}
		}
		enterStep();
	}

	private void applyAndClose() {
		DeviceSettings device = Controlify.instance().config().getSettings()
				.getOrCreateDeviceSettings(input.getController().uid());

		AxisCalibrationSettings calibration = device.axisCalibration;
		calibration.replaceAll(recorder.calibrations(), System.currentTimeMillis());
		calibration.enabled = true;

		if (applyDeadzones) {
			for (DeadzoneGroup group : input.getDeadzoneGroups().values()) {
				float deadzone = recorder.recommendedDeadzone(group);
				if (deadzoneApplier != null) {
					// the config screen owns these values while it is open; go through it so the
					// wizard's numbers are not overwritten when that screen saves
					deadzoneApplier.accept(group.name(), deadzone);
				} else {
					input.settings().sensitivity.putDeadzone(group.name(), deadzone);
				}
			}
		}

		Controlify.instance().config().saveSafely();
		MinecraftUtil.setScreen(lastScreen);
	}

	@Override
	public void onClose() {
		MinecraftUtil.setScreen(lastScreen);
	}

	@Override
	public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);

		Step step = currentStep();

		graphics.centeredText(font, accent(Component.translatable("controlify.gui.calibration.title")), width / 2, 14, ColorUtils.ACCENT);
		graphics.centeredText(font, step.title(), width / 2, 30, 0xFFFFFFFF);
		drawCenteredLines(graphics, step.description().getString(), width / 2, 46, Math.min(width - 40, 320), 0xFFBFBFBF);

		int centerY = height / 2 + 4;
		switch (step.type()) {
			case REST -> renderRest(graphics, centerY);
			case RANGE -> renderRange(graphics, step, centerY);
			case SUMMARY -> renderSummary(graphics);
		}

		if (step.type() != Step.Type.SUMMARY) {
			Component status = statusText(step);
			graphics.centeredText(font, status, width / 2, height - 58, statusColor(step));
			RenderUtils.extractAccentBar(graphics, width / 2, height - 46, 182, overallProgress());
		}
	}

	private float overallProgress() {
		// every step before this one is done, and this one counts for its own fraction
		float perStep = 1f / Math.max(1, steps.size() - 1);
		return Mth.clamp(stepIndex * perStep + stepProgress(currentStep()) * perStep, 0f, 1f);
	}

	private Component statusText(Step step) {
		if (settleTicks > 0) {
			return Component.translatable("controlify.gui.calibration.get_ready");
		}
		if (step.type() == Step.Type.REST) {
			if (restDisturbed) {
				return Component.translatable("controlify.gui.calibration.rest.disturbed");
			}
			return Component.translatable(
					"controlify.gui.calibration.rest.counting",
					String.format("%.1f", restTicksRemaining / 20f)
			);
		}
		if (holdTicks >= 0) {
			return Component.translatable("controlify.gui.calibration.step_done");
		}
		return Component.translatable("controlify.gui.calibration.keep_going");
	}

	private int statusColor(Step step) {
		if (step.type() == Step.Type.REST && restDisturbed) {
			return 0xFFFF7A7A;
		}
		if (holdTicks >= 0) {
			return 0xFF9AE49A;
		}
		return ColorUtils.ACCENT;
	}

	private void renderRest(GuiGraphicsExtractor graphics, int centerY) {
		List<DeadzoneGroup> groups = input.getDeadzoneGroups().values().stream()
				.filter(CalibrationRecorder::isTwoDimensional)
				.toList();

		if (groups.isEmpty()) {
			renderAxisBars(graphics, input.rawStateNow().getAxes().stream().sorted(Comparator.comparing(Identifier::toString)).toList(), centerY);
			return;
		}

		// drift is tiny by nature, so magnify it heavily — otherwise every stick looks perfect
		int radius = Math.min(VISUAL_RADIUS, (width - 40) / (groups.size() * 2) - 10);
		int spacing = radius * 2 + 30;
		int startX = width / 2 - (spacing * (groups.size() - 1)) / 2;

		for (int i = 0; i < groups.size(); i++) {
			DeadzoneGroup group = groups.get(i);
			int centerX = startX + i * spacing;

			Vector2f position = CalibrationRecorder.stickPosition(input.rawStateNow(), group);
			float magnified = 8f;

			drawCrosshair(graphics, centerX, centerY, radius);
			CircleElementRenderState.outline(graphics, centerX, centerY, radius, 1f, ColorUtils.ACCENT_DIM).submit(graphics);

			if (position != null) {
				float x = Mth.clamp(position.x() * magnified, -1f, 1f);
				float y = Mth.clamp(position.y() * magnified, -1f, 1f);
				CircleElementRenderState.filled(graphics, centerX + x * radius, centerY + y * radius, 2f, ColorUtils.ACCENT).submit(graphics);
			}

			Component label = groupName(group);
			graphics.centeredText(font, label, centerX, centerY + radius + 6, 0xFFFFFFFF);

			float drift = recorder.worstRestOffset(group.axes());
			graphics.centeredText(
					font,
					Component.translatable("controlify.gui.calibration.measured_drift", percent(drift)),
					centerX, centerY + radius + 18,
					drift > 0.05f ? 0xFFFFC46B : 0xFF9AE49A
			);
		}
	}

	private void renderRange(GuiGraphicsExtractor graphics, Step step, int centerY) {
		DeadzoneGroup group = step.group();
		if (group == null || !CalibrationRecorder.isTwoDimensional(group)) {
			renderAxisBars(graphics, step.axes(), centerY);
			return;
		}

		int centerX = width / 2;
		int radius = VISUAL_RADIUS;

		drawCrosshair(graphics, centerX, centerY, radius);
		CircleElementRenderState.outline(graphics, centerX, centerY, radius, 1f, ColorUtils.ACCENT_DIM).submit(graphics);

		// one pip per sector the stick still has to visit
		for (int sector = 0; sector < CalibrationRecorder.COVERAGE_SECTORS; sector++) {
			float angle = -Mth.PI + (sector + 0.5f) * Mth.TWO_PI / CalibrationRecorder.COVERAGE_SECTORS;
			float pipRadius = radius + 6f;
			boolean covered = recorder.isSectorCovered(group, sector);
			CircleElementRenderState.filled(
					graphics,
					centerX + Mth.cos(angle) * pipRadius,
					centerY + Mth.sin(angle) * pipRadius,
					covered ? 3f : 2f,
					covered ? ColorUtils.ACCENT : 0xFF4A4458
			).submit(graphics);
		}

		Vector2f position = CalibrationRecorder.stickPosition(input.rawStateNow(), group);
		if (position != null) {
			CircleElementRenderState.filled(
					graphics,
					centerX + Mth.clamp(position.x(), -1f, 1f) * radius,
					centerY + Mth.clamp(position.y(), -1f, 1f) * radius,
					2.5f, ColorUtils.ACCENT
			).submit(graphics);
		}

		graphics.centeredText(
				font,
				Component.translatable(
						"controlify.gui.calibration.sectors",
						recorder.coverageCount(group),
						CalibrationRecorder.REQUIRED_SECTORS
				),
				centerX, centerY + radius + 16, 0xFFFFFFFF
		);
	}

	private void renderAxisBars(GuiGraphicsExtractor graphics, List<Identifier> axes, int centerY) {
		if (axes.isEmpty()) {
			return;
		}

		int barWidth = Math.min(200, width - 60);
		int rowHeight = font.lineHeight + 8;
		int startY = centerY - (axes.size() * rowHeight) / 2;
		int x = width / 2 - barWidth / 2;

		for (int i = 0; i < axes.size(); i++) {
			Identifier axis = axes.get(i);
			int y = startY + i * rowHeight;

			float value = Math.abs(input.rawStateNow().getAxisState(axis));
			float reach = recorder.reach(axis);

			graphics.text(font, axisName(axis), x, y - font.lineHeight - 1, 0xFFBFBFBF);
			graphics.fill(x, y, x + barWidth, y + 5, 0xFF2A2433);
			graphics.fill(x, y, x + Mth.floor(Mth.clamp(value, 0f, 1f) * barWidth), y + 5, ColorUtils.ACCENT);

			int reachX = x + Mth.floor(Mth.clamp(reach, 0f, 1f) * barWidth);
			graphics.fill(reachX - 1, y - 2, reachX + 1, y + 7, recorder.isExplored(axis) ? 0xFF9AE49A : 0xFFFFFFFF);
		}
	}

	private void renderSummary(GuiGraphicsExtractor graphics) {
		int y = 74;
		int lineHeight = font.lineHeight + 3;

		Map<Identifier, AxisCalibration> calibrations = recorder.calibrations();
		graphics.centeredText(
				font,
				accent(Component.translatable("controlify.gui.calibration.summary.header", calibrations.size())),
				width / 2, y, ColorUtils.ACCENT
		);
		y += lineHeight + 4;

		for (DeadzoneGroup group : input.getDeadzoneGroups().values()) {
			float drift = recorder.worstRestOffset(group.axes());
			float deadzone = recorder.recommendedDeadzone(group);
			float current = input.settings().sensitivity.getDeadzone(group.name());

			graphics.centeredText(font, groupName(group), width / 2, y, 0xFFFFFFFF);
			y += lineHeight;
			graphics.centeredText(
					font,
					Component.translatable("controlify.gui.calibration.summary.drift", percent(drift)),
					width / 2, y, 0xFFBFBFBF
			);
			y += lineHeight;
			graphics.centeredText(
					font,
					Component.translatable("controlify.gui.calibration.summary.deadzone", percent(current), percent(deadzone)),
					width / 2, y, ColorUtils.ACCENT
			);
			y += lineHeight + 4;
		}
	}

	private void drawCrosshair(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius) {
		graphics.horizontalLine(centerX - radius, centerX + radius, centerY, 0xFF4A4458);
		graphics.verticalLine(centerX, centerY - radius, centerY + radius, 0xFF4A4458);
	}

	private void drawCenteredLines(GuiGraphicsExtractor graphics, String text, int centerX, int y, int maxWidth, int color) {
		int lineY = y;
		for (String line : wrap(text, maxWidth)) {
			graphics.text(font, line, centerX - font.width(line) / 2, lineY, color);
			lineY += font.lineHeight + 1;
		}
	}

	private List<String> wrap(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();

		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && font.width(candidate) > maxWidth) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	private static Component accent(Component component) {
		return component.copy().withStyle(Style.EMPTY.withColor(ColorUtils.ACCENT_RGB));
	}

	private static String percent(float value) {
		return String.format("%.1f%%", value * 100f);
	}

	private static Component groupName(DeadzoneGroup group) {
		Identifier name = group.name();
		return Component.translatable("controlify.deadzone_group." + name.getNamespace() + "." + name.getPath());
	}

	private static Component axisName(Identifier axis) {
		return Component.translatable("controlify.input." + axis.getNamespace() + "." + axis.getPath());
	}

	@Override
	public ScreenProcessor<?> screenProcessor() {
		return screenProcessor;
	}

	private static class Step {
		private enum Type { REST, RANGE, SUMMARY }

		private final Type type;
		private final @Nullable DeadzoneGroup group;
		private final List<Identifier> axes;

		private Step(Type type, @Nullable DeadzoneGroup group, List<Identifier> axes) {
			this.type = type;
			this.group = group;
			this.axes = axes;
		}

		static Step rest() {
			return new Step(Type.REST, null, List.of());
		}

		static Step group(DeadzoneGroup group) {
			return new Step(Type.RANGE, group, group.axes());
		}

		static Step loose(List<Identifier> axes) {
			return new Step(Type.RANGE, null, axes);
		}

		static Step summary() {
			return new Step(Type.SUMMARY, null, List.of());
		}

		Type type() {
			return type;
		}

		@Nullable DeadzoneGroup group() {
			return group;
		}

		List<Identifier> axes() {
			return axes;
		}

		Component title() {
			return switch (type) {
				case REST -> Component.translatable("controlify.gui.calibration.rest.title");
				case RANGE -> group != null
						? Component.translatable("controlify.gui.calibration.range.title", groupName(group))
						: Component.translatable("controlify.gui.calibration.triggers.title");
				case SUMMARY -> Component.translatable("controlify.gui.calibration.summary.title");
			};
		}

		Component description() {
			return switch (type) {
				case REST -> Component.translatable("controlify.gui.calibration.rest.description");
				case RANGE -> group != null
						? Component.translatable("controlify.gui.calibration.range.description")
						: Component.translatable("controlify.gui.calibration.triggers.description");
				case SUMMARY -> Component.translatable("controlify.gui.calibration.summary.description");
			};
		}
	}

	private static class ScreenProcessorImpl extends ScreenProcessor<ControllerCalibrationScreen> {
		ScreenProcessorImpl(ControllerCalibrationScreen screen) {
			super(screen);
		}

		@Override
		public void onControllerUpdate(ControllerEntity controller) {
			// the player is waving the sticks around on purpose here; navigating the screen with
			// them would fight the calibration
		}
	}
}
