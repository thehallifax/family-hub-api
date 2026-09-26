package com.familyhub.demo.service;

import com.familyhub.demo.dto.FamilyRequest;
import com.familyhub.demo.dto.FamilyResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.familyhub.demo.TestDataFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FamilyServiceTest {

    @Mock
    private FamilyRepository familyRepository;

    @Mock
    private AppearanceMediaService appearanceMediaService;

    @InjectMocks
    private FamilyService familyService;

    private Family family;

    @BeforeEach
    void setUp() {
        family = createFamily();
        family.setFamilyMembers(List.of());
    }

    @Test
    void findFamilyResponse_found_returnsResponse() {
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));

        FamilyResponse result = familyService.findFamilyResponse(FAMILY_ID);

        assertThat(result.id()).isEqualTo(FAMILY_ID);
        assertThat(result.name()).isEqualTo("Test Family");
    }

    @Test
    void findFamilyResponse_notFound_throws() {
        UUID unknownId = UUID.randomUUID();
        when(familyRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> familyService.findFamilyResponse(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateFamily_nameOnly_updatesName() {
        FamilyRequest request = new FamilyRequest("New Name", null, null);
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));
        when(familyRepository.save(any(Family.class))).thenReturn(family);

        FamilyResponse result = familyService.updateFamily(FAMILY_ID, request);

        assertThat(result).isNotNull();
        assertThat(family.getName()).isEqualTo("New Name");
        assertThat(family.getUsername()).isEqualTo("testfamily");
    }

    @Test
    void updateFamily_usernameOnly_updatesUsername() {
        FamilyRequest request = new FamilyRequest(null, "newusername", null);
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));
        when(familyRepository.save(any(Family.class))).thenReturn(family);

        familyService.updateFamily(FAMILY_ID, request);

        assertThat(family.getUsername()).isEqualTo("newusername");
        assertThat(family.getName()).isEqualTo("Test Family");
    }

    @Test
    void updateFamily_fullUpdate_updatesBoth() {
        FamilyRequest request = new FamilyRequest("New Name", "newusername", null);
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));
        when(familyRepository.save(any(Family.class))).thenReturn(family);

        familyService.updateFamily(FAMILY_ID, request);

        assertThat(family.getName()).isEqualTo("New Name");
        assertThat(family.getUsername()).isEqualTo("newusername");
    }

    @Test
    void updateFamily_timezoneOnly_updatesNormalizedTimezone() {
        FamilyRequest request = new FamilyRequest(null, null, " America/New_York ");
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));
        when(familyRepository.save(any(Family.class))).thenReturn(family);

        familyService.updateFamily(FAMILY_ID, request);

        assertThat(family.getTimezone()).isEqualTo("America/New_York");
        assertThat(family.getName()).isEqualTo("Test Family");
        assertThat(family.getUsername()).isEqualTo("testfamily");
    }

    @Test
    void updateFamily_invalidTimezone_throwsBadRequestAndDoesNotSave() {
        FamilyRequest request = new FamilyRequest(null, null, "Mars/Olympus");
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));

        assertThatThrownBy(() -> familyService.updateFamily(FAMILY_ID, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Timezone must be a valid IANA timezone.");

        verify(familyRepository, never()).save(any(Family.class));
    }

    @Test
    void updateFamily_timezoneOmitted_leavesTimezoneUnchanged() {
        family.setTimezone("Asia/Tokyo");
        FamilyRequest request = new FamilyRequest("New Name", null, null);
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));
        when(familyRepository.save(any(Family.class))).thenReturn(family);

        familyService.updateFamily(FAMILY_ID, request);

        assertThat(family.getTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(family.getName()).isEqualTo("New Name");
    }

    @Test
    void deleteFamily_success() {
        when(familyRepository.findById(FAMILY_ID)).thenReturn(Optional.of(family));

        familyService.deleteFamily(FAMILY_ID);

        verify(familyRepository).delete(family);
        verify(appearanceMediaService).deleteFamilyAfterCommit(FAMILY_ID);
    }

    @Test
    void deleteFamily_notFound_throws() {
        UUID unknownId = UUID.randomUUID();
        when(familyRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> familyService.deleteFamily(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
