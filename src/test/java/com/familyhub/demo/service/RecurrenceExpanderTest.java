package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.familyhub.demo.TestDataFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

class RecurrenceExpanderTest {

    private final RecurrenceExpander expander = new RecurrenceExpander();
    private Family family;
    private FamilyMember member;

    @BeforeEach
    void setUp() {
        family = createFamily();
        member = createFamilyMember(family);
    }

    @Test
    void weeklyExpansion_returnsCorrectDates() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        // FREQ=WEEKLY;BYDAY=TU,TH,FR starting Jun 3 (Tue)

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 8),
                Map.of()
        );

        assertThat(result).extracting(CalendarEventResponse::date)
                .containsExactly(
                        LocalDate.of(2025, 6, 3),  // Tue
                        LocalDate.of(2025, 6, 5),  // Thu
                        LocalDate.of(2025, 6, 6)   // Fri
                );
        // All virtual instances should have null id and reference the parent
        assertThat(result).allSatisfy(r -> {
            assertThat(r.id()).isNull();
            assertThat(r.recurringEventId()).isEqualTo(parent.getId());
            assertThat(r.isRecurring()).isTrue();
            assertThat(r.title()).isEqualTo("Preschool");
            assertThat(r.recurrenceRule()).isEqualTo(parent.getRecurrenceRule());
        });
    }

    @Test
    void dailyExpansion_returnsCorrectDates() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        parent.setRecurrenceRule("FREQ=DAILY");
        parent.setDate(LocalDate.of(2025, 6, 1));

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 3),
                Map.of()
        );

        assertThat(result).hasSize(3);
        assertThat(result).extracting(CalendarEventResponse::date)
                .containsExactly(
                        LocalDate.of(2025, 6, 1),
                        LocalDate.of(2025, 6, 2),
                        LocalDate.of(2025, 6, 3)
                );
    }

    @Test
    void overnightRecurringOccurrenceOverlapsFollowingDay() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        parent.setRecurrenceRule("FREQ=DAILY");
        parent.setDate(LocalDate.of(2025, 6, 1));
        parent.setEndDate(LocalDate.of(2025, 6, 2));
        parent.setStartTime(LocalTime.of(23, 0));
        parent.setEndTime(LocalTime.of(1, 0));

        List<CalendarEventResponse> result = expander.expand(
                parent, LocalDate.of(2025, 6, 2), LocalDate.of(2025, 6, 2), Map.of());

        assertThat(result).extracting(CalendarEventResponse::date)
                .containsExactly(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 2));
        assertThat(result.getFirst().endDate()).isEqualTo(LocalDate.of(2025, 6, 2));
        assertThat(result.getLast().endDate()).isEqualTo(LocalDate.of(2025, 6, 3));
    }

    @Test
    void cancelledDate_isSkipped() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        parent.setRecurrenceRule("FREQ=DAILY");
        parent.setDate(LocalDate.of(2025, 6, 1));

        CalendarEvent cancelled = new CalendarEvent();
        cancelled.setId(UUID.randomUUID());
        cancelled.setCancelled(true);
        cancelled.setOriginalDate(LocalDate.of(2025, 6, 2));
        cancelled.setRecurringEvent(parent);

        Map<LocalDate, CalendarEvent> exceptions = new HashMap<>();
        exceptions.put(LocalDate.of(2025, 6, 2), cancelled);

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 3),
                exceptions
        );

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CalendarEventResponse::date)
                .containsExactly(
                        LocalDate.of(2025, 6, 1),
                        LocalDate.of(2025, 6, 3)
                );
    }

    @Test
    void editedException_usesExceptionData() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        parent.setRecurrenceRule("FREQ=DAILY");
        parent.setDate(LocalDate.of(2025, 6, 1));

        CalendarEvent edited = new CalendarEvent();
        edited.setId(UUID.randomUUID());
        edited.setTitle("Edited Preschool");
        edited.setStartTime(LocalTime.of(10, 0));
        edited.setEndTime(LocalTime.of(13, 0));
        edited.setDate(LocalDate.of(2025, 6, 2));
        edited.setOriginalDate(LocalDate.of(2025, 6, 2));
        edited.setRecurringEvent(parent);
        edited.getAudienceMembers().add(member);
        edited.setFamily(family);
        edited.setCancelled(false);

        Map<LocalDate, CalendarEvent> exceptions = new HashMap<>();
        exceptions.put(LocalDate.of(2025, 6, 2), edited);

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 3),
                exceptions
        );

        assertThat(result).hasSize(3);
        CalendarEventResponse editedResponse = result.get(1);
        assertThat(editedResponse.title()).isEqualTo("Edited Preschool");
        assertThat(editedResponse.startTime()).isEqualTo("10:00 AM");
    }

    @Test
    void emptyRange_returnsEmpty() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 5),
                Map.of()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void exdates_areFilteredFromExpansion() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        parent.setRecurrenceRule("FREQ=DAILY");
        parent.setDate(LocalDate.of(2025, 6, 1));
        parent.setExdates("2025-06-02,2025-06-04");

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 5),
                Map.of()
        );

        assertThat(result).hasSize(3);
        assertThat(result).extracting(CalendarEventResponse::date)
                .containsExactly(
                        LocalDate.of(2025, 6, 1),
                        LocalDate.of(2025, 6, 3),
                        LocalDate.of(2025, 6, 5)
                );
    }

    @Test
    void nullExdates_expandsNormally() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        parent.setRecurrenceRule("FREQ=DAILY");
        parent.setDate(LocalDate.of(2025, 6, 1));

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 3),
                Map.of()
        );

        assertThat(result).hasSize(3);
    }

    @Test
    void parentAfterRange_returnsEmpty() {
        CalendarEvent parent = createRecurringCalendarEvent(family, member);
        // Parent starts Jun 3 but we query Jan

        List<CalendarEventResponse> result = expander.expand(
                parent,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31),
                Map.of()
        );

        assertThat(result).isEmpty();
    }
}
