package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.familyhub.demo.TestDataFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarEventServiceTest {

    @Mock
    private CalendarEventRepository calendarEventRepository;

    @Mock
    private FamilyMemberRepository familyMemberRepository;

    @Mock
    private RecurrenceRuleValidator recurrenceRuleValidator;

    @Mock
    private RecurrenceExpander recurrenceExpander;

    @InjectMocks
    private CalendarEventService calendarEventService;

    private Family family;
    private FamilyMember member;
    private CalendarEvent event;

    @BeforeEach
    void setUp() {
        family = createFamily();
        family.setFamilyMembers(List.of());
        member = createFamilyMember(family);
        event = createCalendarEvent(family, member);
    }

    @Test
    void getAllEventsByFamily_filteredByDateRange_returnsFiltered() {
        CalendarEvent outsideRange = createCalendarEvent(family, member);
        outsideRange.setId(UUID.randomUUID());
        outsideRange.setDate(LocalDate.of(2025, 1, 1));

        when(calendarEventRepository.findRegularEventsByFamily(family)).thenReturn(List.of(event, outsideRange));
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of());

        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Test Event");
    }

    @Test
    void getAllEventsByFamily_filteredByMemberId_returnsFiltered() {
        FamilyMember otherMember = createFamilyMember(family);
        UUID otherMemberId = UUID.randomUUID();
        otherMember.setId(otherMemberId);

        CalendarEvent otherEvent = createCalendarEvent(family, otherMember);
        otherEvent.setId(UUID.randomUUID());

        when(calendarEventRepository.findRegularEventsByFamily(family)).thenReturn(List.of(event, otherEvent));
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of());
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), MEMBER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().memberId()).isEqualTo(MEMBER_ID);
    }

    @Test
    void getAllEventsByFamily_memberFromWrongFamily_throwsAccessDenied() {
        Family otherFamily = createOtherFamily();
        FamilyMember wrongMember = createFamilyMember(otherFamily);
        UUID wrongMemberId = UUID.randomUUID();
        wrongMember.setId(wrongMemberId);

        when(familyMemberRepository.findById(wrongMemberId)).thenReturn(Optional.of(wrongMember));

        assertThatThrownBy(() -> calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), wrongMemberId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getEventById_found_returnsEvent() {
        when(calendarEventRepository.findByFamilyAndId(family, EVENT_ID)).thenReturn(Optional.of(event));

        CalendarEventResponse result = calendarEventService.getEventById(EVENT_ID, family);

        assertThat(result.id()).isEqualTo(EVENT_ID);
        assertThat(result.title()).isEqualTo("Test Event");
    }

    @Test
    void getEventById_notFound_throwsResourceNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(calendarEventRepository.findByFamilyAndId(family, unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendarEventService.getEventById(unknownId, family))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addCalendarEvent_success_returnsSavedEvent() {
        CalendarEventRequest request = createCalendarEventRequest(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenReturn(event);

        CalendarEventResponse result = calendarEventService.addCalendarEvent(request, family);

        assertThat(result.id()).isEqualTo(EVENT_ID);
        verify(calendarEventRepository).save(any(CalendarEvent.class));
    }

    @Test
    void addCalendarEvent_invalidTimeRange_throwsBadRequest() {
        CalendarEventRequest request = new CalendarEventRequest(
                "Bad Event", "10:00 AM", "9:00 AM",
                LocalDate.of(2025, 6, 15), MEMBER_ID, false, null, null, null, null);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> calendarEventService.addCalendarEvent(request, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Start time must be before end time");
    }

    @Test
    void addCalendarEvent_allDayEvent_skipsTimeValidation() {
        CalendarEventRequest request = new CalendarEventRequest(
                "Birthday", "12:00 AM", "12:00 AM",
                LocalDate.of(2025, 6, 15), MEMBER_ID, true, null, null, null, null);

        CalendarEvent allDayEvent = createCalendarEvent(family, member);
        allDayEvent.setAllDay(true);
        allDayEvent.setStartTime(LocalTime.MIDNIGHT);
        allDayEvent.setEndTime(LocalTime.MIDNIGHT);

        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenReturn(allDayEvent);

        CalendarEventResponse result = calendarEventService.addCalendarEvent(request, family);

        assertThat(result.isAllDay()).isTrue();
    }

    @Test
    void addCalendarEvent_memberFromWrongFamily_throwsAccessDenied() {
        Family otherFamily = createOtherFamily();
        FamilyMember wrongMember = createFamilyMember(otherFamily);
        UUID wrongMemberId = UUID.randomUUID();
        wrongMember.setId(wrongMemberId);

        CalendarEventRequest request = createCalendarEventRequest(wrongMemberId);
        when(familyMemberRepository.findById(wrongMemberId)).thenReturn(Optional.of(wrongMember));

        assertThatThrownBy(() -> calendarEventService.addCalendarEvent(request, family))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateCalendarEvent_success_verifySaveCalled() {
        CalendarEventRequest request = createCalendarEventRequest(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.findByFamilyAndId(family, EVENT_ID)).thenReturn(Optional.of(event));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenReturn(event);

        calendarEventService.updateCalendarEvent(request, EVENT_ID, family);

        verify(calendarEventRepository).save(any(CalendarEvent.class));
    }

    @Test
    void deleteCalendarEvent_success() {
        when(calendarEventRepository.findByFamilyAndId(family, EVENT_ID)).thenReturn(Optional.of(event));

        calendarEventService.deleteCalendarEvent(EVENT_ID, family);

        verify(calendarEventRepository).delete(event);
    }

    @Test
    void addCalendarEvent_multiDayAllDay_success() {
        CalendarEventRequest request = createMultiDayCalendarEventRequest(MEMBER_ID);
        CalendarEvent multiDayEvent = createMultiDayCalendarEvent(family, member);

        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenReturn(multiDayEvent);

        CalendarEventResponse result = calendarEventService.addCalendarEvent(request, family);

        assertThat(result.isAllDay()).isTrue();
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2025, 3, 9));
    }

    @Test
    void addCalendarEvent_endDateWithoutAllDay_throwsBadRequest() {
        CalendarEventRequest request = new CalendarEventRequest(
                "Trip", "9:00 AM", "10:00 AM",
                LocalDate.of(2025, 3, 7), MEMBER_ID, false, null,
                LocalDate.of(2025, 3, 9), null, null);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> calendarEventService.addCalendarEvent(request, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("End date is only valid for all-day events");
    }

    @Test
    void addCalendarEvent_endDateBeforeDate_throwsBadRequest() {
        CalendarEventRequest request = new CalendarEventRequest(
                "Trip", "12:00 AM", "12:00 AM",
                LocalDate.of(2025, 3, 9), MEMBER_ID, true, null,
                LocalDate.of(2025, 3, 7), null, null);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> calendarEventService.addCalendarEvent(request, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("End date must be on or after start date");
    }

    @Test
    void addCalendarEvent_endDateEqualsDate_normalizesToNull() {
        CalendarEventRequest request = new CalendarEventRequest(
                "Birthday", "12:00 AM", "12:00 AM",
                LocalDate.of(2025, 3, 7), MEMBER_ID, true, null,
                LocalDate.of(2025, 3, 7), null, null);

        CalendarEvent savedEvent = createCalendarEvent(family, member);
        savedEvent.setAllDay(true);
        savedEvent.setDate(LocalDate.of(2025, 3, 7));
        savedEvent.setEndDate(null);

        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(invocation -> {
            CalendarEvent arg = invocation.getArgument(0);
            assertThat(arg.getEndDate()).isNull();
            return savedEvent;
        });

        calendarEventService.addCalendarEvent(request, family);

        verify(calendarEventRepository).save(any(CalendarEvent.class));
    }

    @Test
    void getAllEventsByFamily_multiDayEvent_overlapsDateRange() {
        CalendarEvent multiDay = createMultiDayCalendarEvent(family, member);
        when(calendarEventRepository.findRegularEventsByFamily(family)).thenReturn(List.of(multiDay));
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of());

        // Query Mar 8–8, event is Mar 7–9 → should overlap
        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 3, 8), LocalDate.of(2025, 3, 8), null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Vacation");
    }

    @Test
    void getAllEventsByFamily_multiDayEvent_outsideDateRange() {
        CalendarEvent multiDay = createMultiDayCalendarEvent(family, member);
        when(calendarEventRepository.findRegularEventsByFamily(family)).thenReturn(List.of(multiDay));
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of());

        // Query Mar 10–15, event is Mar 7–9 → should not overlap
        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 3, 10), LocalDate.of(2025, 3, 15), null);

        assertThat(result).isEmpty();
    }

    @Test
    void getAllEventsByFamily_withDateRange_expandsRecurringParents() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);

        when(calendarEventRepository.findRegularEventsByFamily(family)).thenReturn(List.of());
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of(parent));
        when(calendarEventRepository.findExceptionsByParentIds(List.of(parent.getId()))).thenReturn(List.of());

        CalendarEventResponse instance1 = CalendarEventResponse.builder()
                .id(null).title("Preschool").startTime("9:00 AM").endTime("12:00 PM")
                .date(LocalDate.of(2025, 6, 3)).memberId(MEMBER_ID).isRecurring(true)
                .recurringEventId(parent.getId()).recurrenceRule("FREQ=WEEKLY;BYDAY=TU,TH,FR").build();
        CalendarEventResponse instance2 = CalendarEventResponse.builder()
                .id(null).title("Preschool").startTime("9:00 AM").endTime("12:00 PM")
                .date(LocalDate.of(2025, 6, 5)).memberId(MEMBER_ID).isRecurring(true)
                .recurringEventId(parent.getId()).recurrenceRule("FREQ=WEEKLY;BYDAY=TU,TH,FR").build();

        when(recurrenceExpander.expand(eq(parent), any(), any(), any()))
                .thenReturn(List.of(instance1, instance2));

        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 8), null);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CalendarEventResponse::title).containsOnly("Preschool");
        verify(recurrenceExpander).expand(eq(parent), any(), any(), any());
    }

    @Test
    void getAllEventsByFamily_withDateRange_mergesRegularAndExpanded() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        CalendarEvent regular = createCalendarEvent(family, member);
        regular.setDate(LocalDate.of(2025, 6, 4));

        when(calendarEventRepository.findRegularEventsByFamily(family)).thenReturn(List.of(regular));
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of(parent));
        when(calendarEventRepository.findExceptionsByParentIds(List.of(parent.getId()))).thenReturn(List.of());

        CalendarEventResponse expandedInstance = CalendarEventResponse.builder()
                .id(null).title("Preschool").startTime("9:00 AM").endTime("12:00 PM")
                .date(LocalDate.of(2025, 6, 3)).memberId(MEMBER_ID).isRecurring(true)
                .recurringEventId(parent.getId()).build();

        when(recurrenceExpander.expand(eq(parent), any(), any(), any()))
                .thenReturn(List.of(expandedInstance));

        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), null);

        assertThat(result).hasSize(2);
        // Should be sorted by date
        assertThat(result.get(0).date()).isEqualTo(LocalDate.of(2025, 6, 3));
        assertThat(result.get(1).date()).isEqualTo(LocalDate.of(2025, 6, 4));
    }

    @Test
    void getAllEventsByFamily_withDateRange_filtersAfterExpansion() {
        FamilyMember otherMember = createFamilyMember(family);
        UUID otherMemberId = UUID.randomUUID();
        otherMember.setId(otherMemberId);

        CalendarEvent parentForOther = createRecurringCalendarEvent(family, otherMember);
        CalendarEvent parentForMember = createRecurringCalendarEvent(family, member);

        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.findRegularEventsByFamily(family)).thenReturn(List.of());
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any()))
                .thenReturn(List.of(parentForOther, parentForMember));
        when(calendarEventRepository.findExceptionsByParentIds(List.of(parentForOther.getId(), parentForMember.getId())))
                .thenReturn(List.of());

        CalendarEventResponse instance = CalendarEventResponse.builder()
                .id(null).title("Preschool").startTime("9:00 AM").endTime("12:00 PM")
                .date(LocalDate.of(2025, 6, 3)).memberId(MEMBER_ID)
                .audienceType(com.familyhub.demo.model.EventAudienceType.MEMBERS)
                .memberIds(List.of(MEMBER_ID)).isRecurring(true)
                .recurringEventId(parentForMember.getId()).build();

        when(recurrenceExpander.expand(eq(parentForMember), any(), any(), any()))
                .thenReturn(List.of(instance));

        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 8), MEMBER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().memberId()).isEqualTo(MEMBER_ID);
        // Every parent is expanded so an exception can have a different audience.
        verify(recurrenceExpander).expand(eq(parentForOther), any(), any(), any());
    }

    @Test
    void addCalendarEvent_recurrenceRuleWithEndDate_throwsBadRequest() {
        CalendarEventRequest request = new CalendarEventRequest(
                "Dance", "9:00 AM", "10:00 AM",
                LocalDate.of(2025, 6, 3), MEMBER_ID, true, null,
                LocalDate.of(2025, 6, 10), "FREQ=WEEKLY;BYDAY=SU", null);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> calendarEventService.addCalendarEvent(request, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Recurring events cannot span multiple days (endDate). To set when the series ends, use UNTIL in the recurrence rule.");
    }

    @Test
    void addCalendarEvent_validRecurrenceRule_success() {
        CalendarEventRequest request = createRecurringCalendarEventRequest(MEMBER_ID);
        CalendarEvent recurringEvent = createRecurringCalendarEvent(family, member);

        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenReturn(recurringEvent);

        CalendarEventResponse result = calendarEventService.addCalendarEvent(request, family);

        assertThat(result.recurrenceRule()).isEqualTo("FREQ=WEEKLY;BYDAY=TU,TH,FR");
        assertThat(result.isRecurring()).isTrue();
        verify(recurrenceRuleValidator).validate("FREQ=WEEKLY;BYDAY=TU,TH,FR");
    }

    @Test
    void deleteCalendarEvent_notFound_throws() {
        UUID unknownId = UUID.randomUUID();
        when(calendarEventRepository.findByFamilyAndId(family, unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> calendarEventService.deleteCalendarEvent(unknownId, family))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllEventsByFamily_dateRangeExceedsOneYear_throwsBadRequest() {
        assertThatThrownBy(() -> calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 1, 1), LocalDate.of(2035, 1, 1), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Date range must not exceed one year");
    }

    @Test
    void getAllEventsByFamily_sortsByTimeCorrectly() {
        CalendarEvent event10am = createCalendarEvent(family, member);
        event10am.setId(UUID.randomUUID());
        event10am.setStartTime(LocalTime.of(10, 0));
        event10am.setDate(LocalDate.of(2025, 6, 15));

        CalendarEvent event9am = createCalendarEvent(family, member);
        event9am.setId(UUID.randomUUID());
        event9am.setStartTime(LocalTime.of(9, 0));
        event9am.setDate(LocalDate.of(2025, 6, 15));

        when(calendarEventRepository.findRegularEventsByFamily(family))
                .thenReturn(List.of(event10am, event9am));
        when(calendarEventRepository.findRecurringParentsByFamily(eq(family), any())).thenReturn(List.of());

        List<CalendarEventResponse> result = calendarEventService.getAllEventsByFamily(
                family, LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startTime()).isEqualTo("9:00 AM");
        assertThat(result.get(1).startTime()).isEqualTo("10:00 AM");
    }

    // --- Instance edit/delete tests ---

    @Test
    void editRecurringInstance_createsException() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        LocalDate instanceDate = LocalDate.of(2025, 6, 5);
        CalendarEventRequest request = new CalendarEventRequest(
                "Edited Preschool", "10:00 AM", "1:00 PM",
                instanceDate, MEMBER_ID, false, "New Location", null, null, null);

        when(calendarEventRepository.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.findByRecurringEventAndOriginalDate(parent, instanceDate))
                .thenReturn(Optional.empty());
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(invocation -> {
            CalendarEvent saved = invocation.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        CalendarEventResponse result = calendarEventService.editRecurringInstance(
                parent.getId(), instanceDate, request, family);

        assertThat(result.title()).isEqualTo("Edited Preschool");
        verify(calendarEventRepository).save(any(CalendarEvent.class));
    }

    @Test
    void editRecurringInstance_updatesExistingException() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        LocalDate instanceDate = LocalDate.of(2025, 6, 5);

        CalendarEvent existingException = new CalendarEvent();
        existingException.setId(UUID.randomUUID());
        existingException.setRecurringEvent(parent);
        existingException.setOriginalDate(instanceDate);
        existingException.setFamily(family);
        existingException.getAudienceMembers().add(member);
        existingException.setTitle("Old Title");
        existingException.setStartTime(LocalTime.of(9, 0));
        existingException.setEndTime(LocalTime.of(12, 0));
        existingException.setDate(instanceDate);

        CalendarEventRequest request = new CalendarEventRequest(
                "Updated Title", "10:00 AM", "1:00 PM",
                instanceDate, MEMBER_ID, false, null, null, null, null);

        when(calendarEventRepository.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.findByRecurringEventAndOriginalDate(parent, instanceDate))
                .thenReturn(Optional.of(existingException));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenReturn(existingException);

        CalendarEventResponse result = calendarEventService.editRecurringInstance(
                parent.getId(), instanceDate, request, family);

        assertThat(result).isNotNull();
        verify(calendarEventRepository).save(existingException);
    }

    @Test
    void editRecurringInstance_nonRecurring_throwsBadRequest() {
        // event has no recurrenceRule
        when(calendarEventRepository.findByFamilyAndId(family, EVENT_ID)).thenReturn(Optional.of(event));

        CalendarEventRequest request = createCalendarEventRequest(MEMBER_ID);

        assertThatThrownBy(() -> calendarEventService.editRecurringInstance(
                EVENT_ID, LocalDate.of(2025, 6, 15), request, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Event is not recurring");
    }

    @Test
    void deleteRecurringInstance_createsCancelledRow() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        LocalDate instanceDate = LocalDate.of(2025, 6, 5);

        when(calendarEventRepository.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));
        when(calendarEventRepository.findByRecurringEventAndOriginalDate(parent, instanceDate))
                .thenReturn(Optional.empty());
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenAnswer(invocation -> {
            CalendarEvent saved = invocation.getArgument(0);
            assertThat(saved.isCancelled()).isTrue();
            return saved;
        });

        calendarEventService.deleteRecurringInstance(parent.getId(), instanceDate, family);

        verify(calendarEventRepository).save(any(CalendarEvent.class));
    }

    @Test
    void editRecurringInstance_invalidDate_throwsBadRequest() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        // FREQ=WEEKLY;BYDAY=TU,TH,FR — Wednesday is not an occurrence
        LocalDate wednesday = LocalDate.of(2025, 6, 4);
        CalendarEventRequest request = createCalendarEventRequest(MEMBER_ID);

        when(calendarEventRepository.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> calendarEventService.editRecurringInstance(
                parent.getId(), wednesday, request, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("is not an occurrence of this recurring event");
    }

    @Test
    void deleteRecurringInstance_invalidDate_throwsBadRequest() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        // FREQ=WEEKLY;BYDAY=TU,TH,FR — Wednesday is not an occurrence
        LocalDate wednesday = LocalDate.of(2025, 6, 4);

        when(calendarEventRepository.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> calendarEventService.deleteRecurringInstance(
                parent.getId(), wednesday, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("is not an occurrence of this recurring event");
    }

    @Test
    void deleteRecurringInstance_cancelsExistingException() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        LocalDate instanceDate = LocalDate.of(2025, 6, 5);

        CalendarEvent existingException = new CalendarEvent();
        existingException.setId(UUID.randomUUID());
        existingException.setRecurringEvent(parent);
        existingException.setOriginalDate(instanceDate);
        existingException.setFamily(family);
        existingException.getAudienceMembers().add(member);
        existingException.setTitle("Edited");
        existingException.setStartTime(LocalTime.of(10, 0));
        existingException.setEndTime(LocalTime.of(13, 0));
        existingException.setDate(instanceDate);
        existingException.setCancelled(false);

        when(calendarEventRepository.findByFamilyAndId(family, parent.getId())).thenReturn(Optional.of(parent));
        when(calendarEventRepository.findByRecurringEventAndOriginalDate(parent, instanceDate))
                .thenReturn(Optional.of(existingException));
        when(calendarEventRepository.save(any(CalendarEvent.class))).thenReturn(existingException);

        calendarEventService.deleteRecurringInstance(parent.getId(), instanceDate, family);

        assertThat(existingException.isCancelled()).isTrue();
        verify(calendarEventRepository).save(existingException);
    }

    @Test
    void updateCalendarEvent_googleSource_throwsBadRequest() {
        CalendarEvent googleEvent = createCalendarEvent(family, member);
        googleEvent.setSource(EventSource.GOOGLE);
        googleEvent.setId(UUID.randomUUID());

        CalendarEventRequest request = createCalendarEventRequest(MEMBER_ID);
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(calendarEventRepository.findByFamilyAndId(family, googleEvent.getId()))
                .thenReturn(Optional.of(googleEvent));

        assertThatThrownBy(() -> calendarEventService.updateCalendarEvent(
                request, googleEvent.getId(), family))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Google Calendar events cannot be modified");
    }

    @Test
    void deleteCalendarEvent_googleSource_throwsBadRequest() {
        CalendarEvent googleEvent = createCalendarEvent(family, member);
        googleEvent.setSource(EventSource.GOOGLE);
        googleEvent.setId(UUID.randomUUID());

        when(calendarEventRepository.findByFamilyAndId(family, googleEvent.getId()))
                .thenReturn(Optional.of(googleEvent));

        assertThatThrownBy(() -> calendarEventService.deleteCalendarEvent(
                googleEvent.getId(), family))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Google Calendar events cannot be modified");
    }

    @Test
    void editRecurringInstance_googleSource_throwsBadRequest() {
        CalendarEvent googleParent = createRecurringCalendarEvent(family, member);
        googleParent.setSource(EventSource.GOOGLE);

        CalendarEventRequest request = createCalendarEventRequest(MEMBER_ID);
        when(calendarEventRepository.findByFamilyAndId(family, googleParent.getId()))
                .thenReturn(Optional.of(googleParent));

        assertThatThrownBy(() -> calendarEventService.editRecurringInstance(
                googleParent.getId(), LocalDate.of(2025, 6, 5), request, family))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Google Calendar events cannot be modified");
    }

    @Test
    void deleteRecurringInstance_googleSource_throwsBadRequest() {
        CalendarEvent googleParent = createRecurringCalendarEvent(family, member);
        googleParent.setSource(EventSource.GOOGLE);

        when(calendarEventRepository.findByFamilyAndId(family, googleParent.getId()))
                .thenReturn(Optional.of(googleParent));

        assertThatThrownBy(() -> calendarEventService.deleteRecurringInstance(
                googleParent.getId(), LocalDate.of(2025, 6, 5), family))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Google Calendar events cannot be modified");
    }
}
