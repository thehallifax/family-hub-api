package com.familyhub.demo.dto;

import com.familyhub.demo.model.FamilyAppearance.Accent;
import com.familyhub.demo.model.FamilyAppearance.BackgroundMode;
import com.familyhub.demo.model.FamilyAppearance.Gradient;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AppearanceRequest(
        @NotNull Accent accent,
        @NotNull BackgroundMode backgroundMode,
        @NotNull Gradient gradient,
        @NotNull @Min(0) @Max(100) Integer backgroundStrength
) {}
