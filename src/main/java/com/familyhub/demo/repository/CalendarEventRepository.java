package com.familyhub.demo.repository;

import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.GoogleSyncedCalendar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {
    void deleteByMemberAndSource(FamilyMember member, EventSource source);

    void deleteBySyncedCalendarAndSource(GoogleSyncedCalendar syncedCalendar, EventSource source);

    List<CalendarEvent> findByFamily(Family family);
    Optional<CalendarEvent> findByFamilyAndId(Family family, UUID uuid);

    @Query("SELECT e FROM CalendarEvent e WHERE e.family = :family " +
            "AND e.recurrenceRule IS NULL " +
            "AND e.recurringEvent IS NULL")
    List<CalendarEvent> findRegularEventsByFamily(@Param("family") Family family);

    @Query("SELECT e FROM CalendarEvent e WHERE e.family = :family " +
            "AND e.recurrenceRule IS NOT NULL " +
            "AND e.recurringEvent IS NULL " +
            "AND e.date <= :rangeEnd")
    List<CalendarEvent> findRecurringParentsByFamily(@Param("family") Family family, @Param("rangeEnd") LocalDate rangeEnd);

    @Query("SELECT e FROM CalendarEvent e " +
            "WHERE e.recurringEvent.id IN :parentIds")
    List<CalendarEvent> findExceptionsByParentIds(@Param("parentIds") Collection<UUID> parentIds);

    Optional<CalendarEvent> findByRecurringEventAndOriginalDate(CalendarEvent recurringEvent, LocalDate originalDate);

    Optional<CalendarEvent> findBySyncedCalendarAndSourceAndGoogleEventId(
            GoogleSyncedCalendar syncedCalendar, EventSource source, String googleEventId);
    void deleteBySyncedCalendarAndSourceAndGoogleEventId(
            GoogleSyncedCalendar syncedCalendar, EventSource source, String googleEventId);
}
