package com.familyhub.demo.dto;

import com.familyhub.demo.model.FamilyAppearance;
import com.familyhub.demo.model.FamilyAppearance.Accent;
import com.familyhub.demo.model.FamilyAppearance.BackgroundMode;
import com.familyhub.demo.model.FamilyAppearance.Gradient;
import java.util.UUID;

public record AppearanceResponse(
        Accent accent,
        BackgroundMode backgroundMode,
        Gradient gradient,
        int backgroundStrength,
        UUID photoKey
) {
    public static AppearanceResponse from(FamilyAppearance appearance) {
        return new AppearanceResponse(appearance.getAccent(), appearance.getBackgroundMode(),
                appearance.getGradient(), appearance.getBackgroundStrength(), appearance.getPhotoKey());
    }
}
