package com.familyhub.demo.service;

import com.familyhub.demo.dto.AppearanceRequest;
import com.familyhub.demo.dto.AppearanceResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.FamilyAppearance;
import com.familyhub.demo.model.FamilyAppearance.BackgroundMode;
import com.familyhub.demo.repository.FamilyAppearanceRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AppearanceService {
    private final FamilyAppearanceRepository repository;
    private final AppearanceMediaService media;

    private FamilyAppearance getOrDefault(UUID familyId) {
        return repository.findById(familyId).orElseGet(() -> FamilyAppearance.defaults(familyId));
    }

    @Transactional(readOnly = true)
    public AppearanceResponse get(UUID familyId) {
        return AppearanceResponse.from(getOrDefault(familyId));
    }

    @Transactional
    public AppearanceResponse update(UUID familyId, AppearanceRequest request) {
        FamilyAppearance appearance = getOrDefault(familyId);
        if (request.backgroundMode() == BackgroundMode.PHOTO && appearance.getPhotoKey() == null) {
            throw new BadRequestException("Upload a family photo before selecting it");
        }
        appearance.setAccent(request.accent());
        appearance.setBackgroundMode(request.backgroundMode());
        appearance.setGradient(request.gradient());
        appearance.setBackgroundStrength(request.backgroundStrength());
        return AppearanceResponse.from(repository.save(appearance));
    }

    @Transactional
    public AppearanceResponse upload(UUID familyId, MultipartFile file) {
        byte[] processed = media.process(file);
        FamilyAppearance appearance = getOrDefault(familyId);
        UUID previous = appearance.getPhotoKey();
        UUID key = UUID.randomUUID();
        media.write(familyId, key, processed);
        media.deleteOnRollback(familyId, key);
        appearance.setPhotoKey(key);
        AppearanceResponse response = AppearanceResponse.from(repository.saveAndFlush(appearance));
        if (previous != null) media.deleteAfterCommit(familyId, previous);
        return response;
    }

    @Transactional(readOnly = true)
    public byte[] photo(UUID familyId) {
        UUID key = getOrDefault(familyId).getPhotoKey();
        if (key == null) throw new ResourceNotFoundException("Family photo", familyId);
        return media.read(familyId, key);
    }

    @Transactional
    public AppearanceResponse removePhoto(UUID familyId) {
        FamilyAppearance appearance = getOrDefault(familyId);
        UUID previous = appearance.getPhotoKey();
        appearance.setPhotoKey(null);
        if (appearance.getBackgroundMode() == BackgroundMode.PHOTO) {
            appearance.setBackgroundMode(BackgroundMode.DEFAULT);
        }
        AppearanceResponse response = AppearanceResponse.from(repository.saveAndFlush(appearance));
        if (previous != null) media.deleteAfterCommit(familyId, previous);
        return response;
    }
}
