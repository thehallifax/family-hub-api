CREATE TABLE family_appearance (
    family_id UUID PRIMARY KEY REFERENCES family(id) ON DELETE CASCADE,
    accent VARCHAR(16) NOT NULL DEFAULT 'PURPLE',
    background_mode VARCHAR(16) NOT NULL DEFAULT 'DEFAULT',
    gradient VARCHAR(16) NOT NULL DEFAULT 'SUNRISE',
    background_strength INTEGER NOT NULL DEFAULT 55,
    photo_key UUID,
    CONSTRAINT chk_family_appearance_accent CHECK (accent IN ('PURPLE', 'BLUE', 'TEAL', 'GREEN', 'ORANGE', 'ROSE', 'SLATE')),
    CONSTRAINT chk_family_appearance_mode CHECK (background_mode IN ('DEFAULT', 'GRADIENT', 'PHOTO')),
    CONSTRAINT chk_family_appearance_gradient CHECK (gradient IN ('SUNRISE', 'LAGOON', 'LAVENDER')),
    CONSTRAINT chk_family_appearance_strength CHECK (background_strength BETWEEN 0 AND 100),
    CONSTRAINT chk_family_appearance_photo_mode CHECK (background_mode <> 'PHOTO' OR photo_key IS NOT NULL)
);
