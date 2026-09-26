package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;

import static com.familyhub.demo.TestDataFactory.createCalendarEvent;
import static com.familyhub.demo.TestDataFactory.createRecurringCalendarEvent;
import static com.familyhub.demo.TestDataFactory.createFamily;
import static com.familyhub.demo.TestDataFactory.createFamilyMember;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarAudienceServiceTest {
    @Mock CalendarEventRepository events;
    @Mock FamilyMemberRepository members;
    @Mock RecurrenceRuleValidator recurrenceValidator;
    @Mock RecurrenceExpander recurrenceExpander;

    private CalendarEventService service;
    private Family family;
    private FamilyMember james;
    private FamilyMember kathryn;

    @BeforeEach
    void setup() {
        service = new CalendarEventService(events, members, recurrenceValidator, recurrenceExpander);
        family = createFamily();
        james = createFamilyMember(family);
        kathryn = createFamilyMember(family);
        kathryn.setId(UUID.randomUUID());
        lenient().when(events.save(any(CalendarEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CalendarEventRequest request(EventAudienceType audience, UUID... ids) {
        return new CalendarEventRequest("Dentist", "9:00 AM", "10:00 AM",
                LocalDate.of(2025, 6, 15), audience, List.of(ids), false,
                null, null, null, null);
    }

    @Test
    void createsDurableFamilyEventWithNoMemberRows() {
        var response = service.addCalendarEvent(request(EventAudienceType.FAMILY), family);
        assertThat(response.audienceType()).isEqualTo(EventAudienceType.FAMILY);
        assertThat(response.memberIds()).isEmpty();
        assertThat(response.memberId()).isNull();
    }

    @Test
    void createsOneOrMultipleMemberAudienceWithoutDuplicateIds() {
        when(members.findById(james.getId())).thenReturn(Optional.of(james));
        when(members.findById(kathryn.getId())).thenReturn(Optional.of(kathryn));
        assertThat(service.addCalendarEvent(request(EventAudienceType.MEMBERS, james.getId()), family).memberIds())
                .containsExactly(james.getId());
        assertThat(service.addCalendarEvent(request(EventAudienceType.MEMBERS, james.getId(), kathryn.getId()), family).memberIds())
                .containsExactlyInAnyOrder(james.getId(), kathryn.getId());
    }

    @Test
    void rejectsEmptyDuplicateAndFamilyWithMembers() {
        assertThatThrownBy(() -> service.addCalendarEvent(request(EventAudienceType.MEMBERS), family))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.addCalendarEvent(request(EventAudienceType.MEMBERS, james.getId(), james.getId()), family))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.addCalendarEvent(request(EventAudienceType.FAMILY, james.getId()), family))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsMissingAndForeignFamilyMembers() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> service.addCalendarEvent(request(EventAudienceType.MEMBERS, missing), family))
                .isInstanceOf(ResourceNotFoundException.class);
        Family other = createFamily();
        other.setId(UUID.randomUUID());
        FamilyMember foreign = createFamilyMember(other);
        foreign.setId(UUID.randomUUID());
        when(members.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.addCalendarEvent(request(EventAudienceType.MEMBERS, foreign.getId()), family))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updatesBetweenMemberAndFamilyAudiencesWithoutChangingSource() {
        CalendarEvent event = createCalendarEvent(family, james);
        when(events.findByFamilyAndId(family, event.getId())).thenReturn(Optional.of(event));
        when(members.findById(james.getId())).thenReturn(Optional.of(james));
        when(members.findById(kathryn.getId())).thenReturn(Optional.of(kathryn));

        service.updateCalendarEvent(request(EventAudienceType.MEMBERS, james.getId(), kathryn.getId()), event.getId(), family);
        assertThat(event.getAudienceMembers()).containsExactlyInAnyOrder(james, kathryn);
        service.updateCalendarEvent(request(EventAudienceType.FAMILY), event.getId(), family);
        assertThat(event.getAudienceType()).isEqualTo(EventAudienceType.FAMILY);
        assertThat(event.getAudienceMembers()).isEmpty();
        service.updateCalendarEvent(request(EventAudienceType.MEMBERS, kathryn.getId()), event.getId(), family);
        assertThat(event.getAudienceMembers()).containsExactly(kathryn);
    }

    @Test
    void familyFilterIncludesFamilyAndMultiMemberEventsExactlyOnce() {
        CalendarEvent familyEvent = createCalendarEvent(family, james);
        familyEvent.setAudienceType(EventAudienceType.FAMILY);
        familyEvent.getAudienceMembers().clear();
        CalendarEvent shared = createCalendarEvent(family, james);
        shared.setId(UUID.randomUUID());
        shared.getAudienceMembers().add(kathryn);
        when(events.findRegularEventsByFamily(family)).thenReturn(List.of(familyEvent, shared));
        when(events.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of());
        when(members.findById(kathryn.getId())).thenReturn(Optional.of(kathryn));

        var result = service.getAllEventsByFamily(family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), kathryn.getId());
        assertThat(result).hasSize(2);
        assertThat(result).extracting(response -> response.audienceType())
                .containsExactlyInAnyOrder(EventAudienceType.FAMILY, EventAudienceType.MEMBERS);
    }

    @Test
    void recurringVirtualInstancesInheritFamilyOrSharedAudienceAndEditedExceptionKeepsOwnAudience() {
        RecurrenceExpander expander = new RecurrenceExpander();
        CalendarEvent series = createRecurringCalendarEvent(family, james);
        series.getAudienceMembers().add(kathryn);
        var virtual = expander.expand(series, LocalDate.of(2025, 6, 3),
                LocalDate.of(2025, 6, 6), Map.of());
        assertThat(virtual).isNotEmpty();
        assertThat(virtual).allSatisfy(instance ->
                assertThat(instance.memberIds()).containsExactlyInAnyOrder(james.getId(), kathryn.getId()));

        CalendarEvent edited = createCalendarEvent(family, james);
        edited.setRecurringEvent(series);
        edited.setOriginalDate(LocalDate.of(2025, 6, 5));
        edited.setDate(LocalDate.of(2025, 6, 5));
        edited.setAudienceType(EventAudienceType.FAMILY);
        edited.getAudienceMembers().clear();
        var withException = expander.expand(series, LocalDate.of(2025, 6, 3),
                LocalDate.of(2025, 6, 6), Map.of(edited.getOriginalDate(), edited));
        assertThat(withException).filteredOn(instance -> instance.date().equals(edited.getDate()))
                .singleElement().satisfies(instance -> {
                    assertThat(instance.audienceType()).isEqualTo(EventAudienceType.FAMILY);
                    assertThat(instance.memberIds()).isEmpty();
                });

        series.setAudienceType(EventAudienceType.FAMILY);
        series.getAudienceMembers().clear();
        assertThat(expander.expand(series, LocalDate.of(2025, 6, 3),
                LocalDate.of(2025, 6, 6), Map.of()))
                .allSatisfy(instance -> assertThat(instance.audienceType()).isEqualTo(EventAudienceType.FAMILY));
    }
}
