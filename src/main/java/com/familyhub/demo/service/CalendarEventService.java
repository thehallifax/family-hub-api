package com.familyhub.demo.service;

import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.mapper.CalendarEventMapper;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.EventAudienceType;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import net.fortuna.ical4j.model.Recur;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CalendarEventService {
    private final CalendarEventRepository calendarEventRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final RecurrenceRuleValidator recurrenceRuleValidator;
    private final RecurrenceExpander recurrenceExpander;

    public List<CalendarEventResponse> getAllEventsByFamily(
            Family family,
            LocalDate startDate,
            LocalDate endDate,
            UUID memberId
    ) {
        if (memberId != null) {
            FamilyMember familyMember = familyMemberRepository.findById(memberId)
                    .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));
            if (!familyMember.getFamily().getId().equals(family.getId())) {
                throw new AccessDeniedException("Access Denied -- CalendarEventService.getAllEventsByFamily()");
            }
        }

        return getEventsWithExpansion(family, startDate, endDate, memberId);
    }

    private List<CalendarEventResponse> getEventsWithExpansion(
            Family family, LocalDate rangeStart, LocalDate rangeEnd, UUID memberId
    ) {
        if (ChronoUnit.DAYS.between(rangeStart, rangeEnd) > 366) {
            throw new BadRequestException("Date range must not exceed one year");
        }

        // 1. Regular events (non-recurring, non-exception)
        Stream<CalendarEvent> regularStream = calendarEventRepository.findRegularEventsByFamily(family).stream();
        // Filter regular events by date range overlap
        List<CalendarEventResponse> regularResponses = regularStream
                .filter(event -> {
                    LocalDate eventStart = event.getDate();
                    LocalDate eventEnd = event.getEndDate() != null ? event.getEndDate() : event.getDate();
                    return !eventStart.isAfter(rangeEnd) && !eventEnd.isBefore(rangeStart);
                })
                .map(CalendarEventMapper::toDto)
                .toList();

        // Expand before filtering: an edited occurrence may have a different audience.
        List<CalendarEvent> parents = calendarEventRepository.findRecurringParentsByFamily(family, rangeEnd);

        List<CalendarEventResponse> expanded = new ArrayList<>(regularResponses);

        if (!parents.isEmpty()) {
            // 3. Load exceptions for these parents
            List<UUID> parentIds = parents.stream().map(CalendarEvent::getId).toList();
            List<CalendarEvent> allExceptions = calendarEventRepository.findExceptionsByParentIds(parentIds);

            // Group exceptions by parent ID then by originalDate
            Map<UUID, Map<LocalDate, CalendarEvent>> exceptionsByParent = new HashMap<>();
            for (CalendarEvent ex : allExceptions) {
                exceptionsByParent
                        .computeIfAbsent(ex.getRecurringEvent().getId(), k -> new HashMap<>())
                        .put(ex.getOriginalDate(), ex);
            }

            // 4. Expand each parent
            for (CalendarEvent parent : parents) {
                Map<LocalDate, CalendarEvent> exceptions = exceptionsByParent.getOrDefault(parent.getId(), Map.of());
                try {
                    expanded.addAll(recurrenceExpander.expand(parent, rangeStart, rangeEnd, exceptions));
                } catch (Exception e) {
                    log.warn("Failed to expand recurring event {} (rule: {}): {}",
                            parent.getId(), parent.getRecurrenceRule(), e.getMessage());
                }
            }
        }

        // 5. Sort by date then startTime
        if (memberId != null) {
            expanded.removeIf(event -> event.audienceType() != EventAudienceType.FAMILY
                    && !event.memberIds().contains(memberId));
        }
        expanded.sort(Comparator.comparing(CalendarEventResponse::date)
                .thenComparing(r -> CalendarEventMapper.parseTime(r.startTime())));

        return expanded;
    }

    public CalendarEventResponse getEventById(UUID id, Family family) {
        CalendarEvent calendarEvent = calendarEventRepository.findByFamilyAndId(family, id)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", id));

        return CalendarEventMapper.toDto(calendarEvent);
    }

    @Transactional
    public CalendarEventResponse addCalendarEvent(CalendarEventRequest request, Family family) {
        List<FamilyMember> members = resolveAudience(request, family);

        // We turn DTO to an Entity so we can save it in our DB
        CalendarEvent calendarEvent = CalendarEventMapper.toEntity(request, family, members);
        validateEvent(calendarEvent);

        CalendarEvent saved = calendarEventRepository.save(calendarEvent);
        log.info("Calendar event created, eventId={}, familyId={}", saved.getId(), family.getId());

        return CalendarEventMapper.toDto(saved);

    }

    @Transactional
    public CalendarEventResponse updateCalendarEvent(CalendarEventRequest request, UUID eventId, Family family) {
        List<FamilyMember> members = resolveAudience(request, family);

        // Validate the resource belongs to current family
        CalendarEvent calendarEvent = calendarEventRepository.findByFamilyAndId(family, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", eventId));
        rejectGoogleEvent(calendarEvent);

        // Map DTO to Entity to handle `string <-> date/time` conversions
        CalendarEvent update = CalendarEventMapper.toEntity(request, family, members);
        validateEvent(update);

        // Apply changes
        calendarEvent.setTitle(update.getTitle());
        calendarEvent.setStartTime(update.getStartTime());
        calendarEvent.setEndTime(update.getEndTime());
        calendarEvent.setDate(update.getDate());
        calendarEvent.setAllDay(update.isAllDay());
        calendarEvent.setLocation(update.getLocation());
        calendarEvent.setEndDate(update.getEndDate());
        calendarEvent.setAudienceType(update.getAudienceType());
        calendarEvent.getAudienceMembers().clear();
        calendarEvent.getAudienceMembers().addAll(update.getAudienceMembers());
        calendarEvent.setRecurrenceRule(update.getRecurrenceRule());
        calendarEvent.setDescription(update.getDescription());

        CalendarEvent saved = calendarEventRepository.save(calendarEvent);
        log.info("Calendar event updated, eventId={}, familyId={}", saved.getId(), family.getId());

        return CalendarEventMapper.toDto(saved);
    }

    @Transactional
    public void deleteCalendarEvent(UUID id, Family family) {
        CalendarEvent calendarEvent = calendarEventRepository.findByFamilyAndId(family, id)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", id));
        rejectGoogleEvent(calendarEvent);

        calendarEventRepository.delete(calendarEvent);
        log.info("Calendar event deleted, eventId={}, familyId={}", id, family.getId());
    }

    @Transactional
    public CalendarEventResponse editRecurringInstance(UUID parentId, LocalDate date, CalendarEventRequest request, Family family) {
        CalendarEvent parent = calendarEventRepository.findByFamilyAndId(family, parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", parentId));
        rejectGoogleEvent(parent);
        if (parent.getRecurrenceRule() == null) {
            throw new BadRequestException("Event is not recurring");
        }
        validateInstanceDate(parent, date);

        List<FamilyMember> members = resolveAudience(request, family);

        // Find or create exception row
        CalendarEvent exception = calendarEventRepository.findByRecurringEventAndOriginalDate(parent, date)
                .orElseGet(() -> {
                    CalendarEvent ex = new CalendarEvent();
                    ex.setRecurringEvent(parent);
                    ex.setOriginalDate(date);
                    ex.setFamily(family);
                    return ex;
                });

        // Apply edits from request
        CalendarEvent update = CalendarEventMapper.toEntity(request, family, members);
        isEventTimeRangeValid(update);
        validateEndDate(update);

        exception.setTitle(update.getTitle());
        exception.setStartTime(update.getStartTime());
        exception.setEndTime(update.getEndTime());
        exception.setDate(update.getDate());
        exception.setAllDay(update.isAllDay());
        exception.setLocation(update.getLocation());
        exception.setEndDate(update.getEndDate());
        exception.setAudienceType(update.getAudienceType());
        exception.getAudienceMembers().clear();
        exception.getAudienceMembers().addAll(update.getAudienceMembers());
        exception.setCancelled(false);
        exception.setDescription(update.getDescription());

        CalendarEvent saved = calendarEventRepository.save(exception);
        log.info("Recurring instance edited, parentId={}, date={}, exceptionId={}",
                parentId, date, saved.getId());
        return CalendarEventMapper.toDto(saved);
    }

    @Transactional
    public void deleteRecurringInstance(UUID parentId, LocalDate date, Family family) {
        CalendarEvent parent = calendarEventRepository.findByFamilyAndId(family, parentId)
                .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", parentId));
        rejectGoogleEvent(parent);
        if (parent.getRecurrenceRule() == null) {
            throw new BadRequestException("Event is not recurring");
        }
        validateInstanceDate(parent, date);

        // A cancellation row snapshots the parent's audience for data integrity.
        CalendarEvent exception = calendarEventRepository.findByRecurringEventAndOriginalDate(parent, date)
                .orElseGet(() -> {
                    CalendarEvent ex = new CalendarEvent();
                    ex.setRecurringEvent(parent);
                    ex.setOriginalDate(date);
                    ex.setFamily(family);
                    ex.setTitle(parent.getTitle());
                    ex.setStartTime(parent.getStartTime());
                    ex.setEndTime(parent.getEndTime());
                    ex.setDate(date);
                    ex.setAudienceType(parent.getAudienceType());
                    ex.getAudienceMembers().addAll(parent.getAudienceMembers());
                    return ex;
                });

        exception.setCancelled(true);
        calendarEventRepository.save(exception);
        log.info("Recurring instance cancelled, parentId={}, date={}", parentId, date);
    }

    private void validateInstanceDate(CalendarEvent parent, LocalDate date) {
        Recur<LocalDate> recur = new Recur<>(parent.getRecurrenceRule());
        List<LocalDate> dates = recur.getDates(parent.getDate(), date, date);
        if (!dates.contains(date)) {
            throw new BadRequestException("Date %s is not an occurrence of this recurring event".formatted(date));
        }
    }

    private List<FamilyMember> resolveAudience(CalendarEventRequest request, Family family) {
        if (request.audienceType() == null || request.memberIds() == null) {
            throw new BadRequestException("Event audience is required");
        }
        if (request.audienceType() == EventAudienceType.FAMILY) {
            if (!request.memberIds().isEmpty()) {
                throw new BadRequestException("Family events cannot list members");
            }
            return List.of();
        }
        if (request.memberIds().isEmpty() || request.memberIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(request.memberIds()).size() != request.memberIds().size()) {
            throw new BadRequestException("Select one or more unique family members");
        }
        return request.memberIds().stream().map(id -> {
            FamilyMember member = familyMemberRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Family Member", id));
            if (!member.getFamily().getId().equals(family.getId())) {
                throw new AccessDeniedException("Event audience member belongs to another family");
            }
            return member;
        }).toList();
    }

    private void validateEvent(CalendarEvent event) {
        isEventTimeRangeValid(event);
        validateEndDate(event);
        validateRecurrenceRule(event);
    }

    private void isEventTimeRangeValid(CalendarEvent event) {
        //  an "all day event" should not be constrained by this check. (eg. Birthday 12 AM - 12 AM)
        if (!event.isAllDay() && event.getStartTime().isAfter(event.getEndTime())) {
            throw new BadRequestException("Start time must be before end time");
        }
    }

    private void validateRecurrenceRule(CalendarEvent event) {
        if (event.getRecurrenceRule() == null) {
            return;
        }
        if (event.getEndDate() != null) {
            throw new BadRequestException("Recurring events cannot span multiple days (endDate). To set when the series ends, use UNTIL in the recurrence rule.");
        }
        recurrenceRuleValidator.validate(event.getRecurrenceRule());
    }

    private void validateEndDate(CalendarEvent event) {
        if (event.getEndDate() == null) {
            return;
        }
        if (!event.isAllDay()) {
            throw new BadRequestException("End date is only valid for all-day events");
        }
        if (event.getEndDate().isBefore(event.getDate())) {
            throw new BadRequestException("End date must be on or after start date");
        }
        // Normalize: endDate == date means single-day, store as null
        if (event.getEndDate().equals(event.getDate())) {
            event.setEndDate(null);
        }
    }

    private void rejectGoogleEvent(CalendarEvent event) {
        if (event.getSource() == EventSource.GOOGLE) {
            throw new BadRequestException(
                    "Google Calendar events cannot be modified in FamilyHub. Edit them in Google Calendar.");
        }
    }
}
