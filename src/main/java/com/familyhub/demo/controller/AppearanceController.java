package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.AppearanceRequest;
import com.familyhub.demo.dto.AppearanceResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.AppearanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/family/appearance")
@RequiredArgsConstructor
public class AppearanceController {
    private final AppearanceService service;

    @GetMapping
    public ApiResponse<AppearanceResponse> get(@AuthenticationPrincipal Family family) {
        return new ApiResponse<>(service.get(family.getId()), "Appearance found");
    }

    @PutMapping
    public ApiResponse<AppearanceResponse> update(@AuthenticationPrincipal Family family,
                                                   @RequestBody @Valid AppearanceRequest request) {
        return new ApiResponse<>(service.update(family.getId(), request), "Appearance updated");
    }

    @PostMapping(path = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AppearanceResponse> upload(@AuthenticationPrincipal Family family,
                                                   @RequestPart("file") MultipartFile file) {
        return new ApiResponse<>(service.upload(family.getId(), file), "Photo uploaded");
    }

    @GetMapping(path = "/photo", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore()).body(service.photo(family.getId()));
    }

    @DeleteMapping("/photo")
    public ApiResponse<AppearanceResponse> removePhoto(@AuthenticationPrincipal Family family) {
        return new ApiResponse<>(service.removePhoto(family.getId()), "Photo removed");
    }
}
