package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class FamilyAppearance {
    public enum Accent { PURPLE, BLUE, TEAL, GREEN, ORANGE, ROSE, SLATE }
    public enum BackgroundMode { DEFAULT, GRADIENT, PHOTO }
    public enum Gradient { SUNRISE, LAGOON, LAVENDER }

    @Id
    @Column(name = "family_id")
    private UUID familyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Accent accent = Accent.PURPLE;

    @Enumerated(EnumType.STRING)
    @Column(name = "background_mode", nullable = false, length = 16)
    private BackgroundMode backgroundMode = BackgroundMode.DEFAULT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Gradient gradient = Gradient.SUNRISE;

    @Column(name = "background_strength", nullable = false)
    private int backgroundStrength = 55;

    @Column(name = "photo_key")
    private UUID photoKey;

    public static FamilyAppearance defaults(UUID familyId) {
        FamilyAppearance appearance = new FamilyAppearance();
        appearance.setFamilyId(familyId);
        return appearance;
    }
}
