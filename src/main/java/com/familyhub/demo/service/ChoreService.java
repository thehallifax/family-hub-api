package com.familyhub.demo.service;

import com.familyhub.demo.dto.ChoreAssigneeGroupResponse;
import com.familyhub.demo.dto.ChoreBoardItemResponse;
import com.familyhub.demo.dto.ChoreBoardResponse;
import com.familyhub.demo.dto.ChoreCurrentPeriodStateResponse;
import com.familyhub.demo.dto.ChoreDueState;
import com.familyhub.demo.dto.ChoreScopeBoardResponse;
import com.familyhub.demo.dto.ChoreTemplateResponse;
import com.familyhub.demo.dto.CreateChoreTemplateRequest;
import com.familyhub.demo.dto.FamilyMemberResponse;
import com.familyhub.demo.dto.UpdateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateCurrentPeriodCompletionRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.ChoreCadence;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreScope;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChoreService {
    private static final String INACTIVE_TEMPLATE_MESSAGE = "Chore template is not active for the current period.";
    private static final String STALE_PERIOD_MESSAGE = "Chore period is stale. Refresh and try again.";

    private final ChoreTemplateRepository choreTemplateRepository;
    private final ChorePeriodCompletionRepository chorePeriodCompletionRepository;
    private final ChorePeriodCompletionWriter chorePeriodCompletionWriter;
    private final FamilyMemberRepository familyMemberRepository;
    private final Clock clock;

    public ChoreBoardResponse getBoard(Family family) {
        LocalDate today = familyLocalToday(family);
        List<ChoreTemplate> activeTemplates = choreTemplateRepository.findActiveByFamily(family);

        ChoreScopeBoardResponse todayBoard = buildScopeBoard(
                activeTemplates,
                ChoreScope.TODAY,
                today,
                today,
                today
        );

        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        ChoreScopeBoardResponse weekBoard = buildScopeBoard(
                activeTemplates,
                ChoreScope.THIS_WEEK,
                today,
                weekStart,
                weekStart.plusDays(6)
        );

        LocalDate monthStart = today.withDayOfMonth(1);
        ChoreScopeBoardResponse monthBoard = buildScopeBoard(
                activeTemplates,
                ChoreScope.THIS_MONTH,
                today,
                monthStart,
                today.withDayOfMonth(today.lengthOfMonth())
        );

        return new ChoreBoardResponse(resolveTimezone(family), todayBoard, weekBoard, monthBoard);
    }

    @Transactional
    public ChoreTemplateResponse createTemplate(CreateChoreTemplateRequest request, Family family) {
        FamilyMember assignedToMember = requireAssignedMember(family, request.assignedToMemberId());

        ChoreTemplate template = new ChoreTemplate();
        template.setFamily(family);
        template.setAssignedToMember(assignedToMember);
        template.setTitle(request.title().trim());
        template.setCadence(request.cadence());
        validateSchedule(request.cadence(), request.dueWeekday(), request.dueDayOfMonth(), request.recurrenceAnchorDate());
        template.setDueWeekday(request.dueWeekday());
        template.setDueDayOfMonth(request.dueDayOfMonth());
        template.setRecurrenceAnchorDate(request.recurrenceAnchorDate());
        template.setActiveFrom(request.activeFrom());

        return toTemplateResponse(choreTemplateRepository.save(template));
    }

    @Transactional
    public ChoreTemplateResponse updateTemplate(UUID templateId, UpdateChoreTemplateRequest request, Family family) {
        ChoreTemplate template = choreTemplateRepository.findByFamilyAndId(family, templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Chore Template", templateId));

        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new BadRequestException("Chore title is required");
            }
            template.setTitle(request.title().trim());
        }
        if (request.assignedToMemberId() != null) {
            template.setAssignedToMember(requireAssignedMember(family, request.assignedToMemberId()));
        }
        if (request.cadence() != null) {
            // A cadence-bearing PATCH replaces the schedule. Legacy weekly/monthly
            // nulls still mean "any day"; fortnightly requires both schedule fields.
            validateSchedule(request.cadence(), request.dueWeekday(), request.dueDayOfMonth(), request.recurrenceAnchorDate());
            template.setCadence(request.cadence());
            template.setDueWeekday(request.dueWeekday());
            template.setDueDayOfMonth(request.dueDayOfMonth());
            template.setRecurrenceAnchorDate(request.recurrenceAnchorDate());
        } else if (request.dueWeekday() != null || request.dueDayOfMonth() != null
                || request.recurrenceAnchorDate() != null) {
            throw new BadRequestException("Include cadence when changing a chore schedule");
        }
        if (request.activeFrom() != null) {
            template.setActiveFrom(request.activeFrom());
        }
        if (request.archived() != null) {
            template.setArchivedAt(Boolean.TRUE.equals(request.archived()) ? LocalDateTime.now(clock) : null);
        }

        return toTemplateResponse(choreTemplateRepository.save(template));
    }

    @Transactional
    public ChoreCurrentPeriodStateResponse completeCurrentPeriod(
            UUID templateId,
            UpdateCurrentPeriodCompletionRequest request,
            Family family
    ) {
        LocalDate today = familyLocalToday(family);
        ChoreTemplate template = requireActiveTemplate(family, templateId, today);
        ResolvedPeriod period = resolveCurrentPeriod(template, today);
        assertFreshPeriod(request, period);

        ChorePeriodCompletion existingCompletion = chorePeriodCompletionRepository
                .findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                        template,
                        period.periodStartDate(),
                        period.periodEndDate()
                )
                .orElse(null);

        if (existingCompletion != null) {
            return toCurrentPeriodStateResponse(template, period, existingCompletion, today);
        }

        try {
            ChorePeriodCompletion saved = chorePeriodCompletionWriter.createCompletion(
                    template.getId(),
                    period.periodStartDate(),
                    period.periodEndDate(),
                    LocalDateTime.now(clock)
            );
            return toCurrentPeriodStateResponse(template, period, saved, today);
        } catch (DataIntegrityViolationException ex) {
            ChorePeriodCompletion concurrentCompletion = chorePeriodCompletionRepository
                    .findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                            template,
                            period.periodStartDate(),
                            period.periodEndDate()
                    )
                    .orElseThrow(() -> ex);
            return toCurrentPeriodStateResponse(template, period, concurrentCompletion, today);
        }
    }

    @Transactional
    public ChoreCurrentPeriodStateResponse uncompleteCurrentPeriod(
            UUID templateId,
            UpdateCurrentPeriodCompletionRequest request,
            Family family
    ) {
        LocalDate today = familyLocalToday(family);
        ChoreTemplate template = requireActiveTemplate(family, templateId, today);
        ResolvedPeriod period = resolveCurrentPeriod(template, today);
        assertFreshPeriod(request, period);

        chorePeriodCompletionRepository
                .findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
                        template,
                        period.periodStartDate(),
                        period.periodEndDate()
                )
                .ifPresent(chorePeriodCompletionRepository::delete);

        return new ChoreCurrentPeriodStateResponse(
                period.scope(),
                period.periodStartDate(),
                period.periodEndDate(),
                toBoardItem(template, null, today)
        );
    }

    private ChoreScopeBoardResponse buildScopeBoard(
            List<ChoreTemplate> activeTemplates,
            ChoreScope scope,
            LocalDate today,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        List<ChoreTemplate> scopeTemplates = activeTemplates.stream()
                .filter(template -> scopeFor(template.getCadence()) == scope)
                .filter(template -> !template.getActiveFrom().isAfter(today))
                .toList();

        Map<UUID, ChorePeriodCompletion> completionsByTemplateId = completionsByTemplateId(
                scopeTemplates,
                today
        );

        List<FamilyMember> assignees = scopeTemplates.stream()
                .map(ChoreTemplate::getAssignedToMember)
                .collect(Collectors.toMap(
                        FamilyMember::getId,
                        Function.identity(),
                        (first, ignored) -> first
                ))
                .values()
                .stream()
                .sorted(memberComparator())
                .toList();

        List<ChoreAssigneeGroupResponse> groups = assignees.stream()
                .map(member -> buildAssigneeGroup(member, scopeTemplates, completionsByTemplateId, today))
                .toList();

        int total = groups.stream().mapToInt(group -> group.summary().total()).sum();
        int completed = groups.stream().mapToInt(group -> group.summary().completed()).sum();

        return new ChoreScopeBoardResponse(
                scope,
                periodStart,
                periodEnd,
                new ChoreScopeBoardResponse.Summary(total, completed, total - completed),
                groups
        );
    }

    private Map<UUID, ChorePeriodCompletion> completionsByTemplateId(
            List<ChoreTemplate> templates,
            LocalDate today
    ) {
        if (templates.isEmpty()) {
            return Map.of();
        }

        Map<PeriodWindow, List<UUID>> idsByPeriod = templates.stream().collect(Collectors.groupingBy(
                template -> {
                    ResolvedPeriod period = resolveCurrentPeriod(template, today);
                    return new PeriodWindow(period.periodStartDate(), period.periodEndDate());
                },
                Collectors.mapping(ChoreTemplate::getId, Collectors.toList())
        ));
        Map<UUID, ChorePeriodCompletion> result = new HashMap<>();
        idsByPeriod.forEach((period, ids) ->
                chorePeriodCompletionRepository.findByTemplateIdsAndPeriod(ids, period.start(), period.end())
                        .forEach(completion -> result.put(completion.getChoreTemplate().getId(), completion)));
        return result;
    }

    private ChoreAssigneeGroupResponse buildAssigneeGroup(
            FamilyMember member,
            List<ChoreTemplate> templates,
            Map<UUID, ChorePeriodCompletion> completionsByTemplateId,
            LocalDate today
    ) {
        List<ChoreBoardItemResponse> chores = templates.stream()
                .filter(template -> template.getAssignedToMember().getId().equals(member.getId()))
                .sorted(templateComparator(completionsByTemplateId))
                .map(template -> toBoardItem(template, completionsByTemplateId.get(template.getId()), today))
                .toList();

        int total = chores.size();
        int completed = (int) chores.stream().filter(ChoreBoardItemResponse::completed).count();

        return new ChoreAssigneeGroupResponse(
                FamilyMemberResponse.toDto(member),
                new ChoreAssigneeGroupResponse.Summary(total, completed, total - completed),
                chores
        );
    }

    private Comparator<ChoreTemplate> templateComparator(Map<UUID, ChorePeriodCompletion> completionsByTemplateId) {
        return Comparator
                .comparing((ChoreTemplate template) -> completionsByTemplateId.containsKey(template.getId()))
                .thenComparing(
                        ChoreTemplate::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                )
                .thenComparing(template -> template.getTitle().toLowerCase(Locale.ROOT))
                .thenComparing(template -> template.getId().toString());
    }

    private Comparator<FamilyMember> memberComparator() {
        return Comparator
                .comparing((FamilyMember member) -> member.getName().toLowerCase(Locale.ROOT))
                .thenComparing(member -> member.getId().toString());
    }

    private ChoreScope scopeFor(ChoreCadence cadence) {
        return switch (cadence) {
            case DAILY -> ChoreScope.TODAY;
            case WEEKLY -> ChoreScope.THIS_WEEK;
            case FORTNIGHTLY -> ChoreScope.THIS_WEEK;
            case MONTHLY -> ChoreScope.THIS_MONTH;
        };
    }

    private ChoreTemplate requireActiveTemplate(Family family, UUID templateId, LocalDate today) {
        ChoreTemplate template = choreTemplateRepository.findByFamilyAndId(family, templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Chore Template", templateId));

        if (template.getArchivedAt() != null || template.getActiveFrom().isAfter(today)) {
            throw new BadRequestException(INACTIVE_TEMPLATE_MESSAGE);
        }

        if (template.getCadence() == ChoreCadence.FORTNIGHTLY
                && today.isBefore(resolveCurrentPeriod(template, today).periodStartDate())) {
            throw new BadRequestException(INACTIVE_TEMPLATE_MESSAGE);
        }

        return template;
    }

    private FamilyMember requireAssignedMember(Family family, UUID memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));

        if (!member.getFamily().getId().equals(family.getId())) {
            throw new AccessDeniedException("Unauthorized");
        }

        return member;
    }

    private ResolvedPeriod resolveCurrentPeriod(ChoreTemplate template, LocalDate today) {
        return switch (template.getCadence()) {
            case DAILY -> new ResolvedPeriod(ChoreScope.TODAY, today, today);
            case WEEKLY -> {
                LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
                yield new ResolvedPeriod(ChoreScope.THIS_WEEK, weekStart, weekStart.plusDays(6));
            }
            case FORTNIGHTLY -> {
                LocalDate anchor = template.getRecurrenceAnchorDate();
                long daysFromAnchor = ChronoUnit.DAYS.between(anchor, today);
                // Due date is centered seven days into each 14-day window.
                // Before the first window, retain the first due date; never invent a past cycle.
                long cycle = Math.max(0, Math.floorDiv(daysFromAnchor + 7, 14));
                LocalDate dueDate = anchor.plusDays(cycle * 14);
                yield new ResolvedPeriod(ChoreScope.THIS_WEEK, dueDate.minusDays(7), dueDate.plusDays(6));
            }
            case MONTHLY -> {
                LocalDate monthStart = today.withDayOfMonth(1);
                yield new ResolvedPeriod(ChoreScope.THIS_MONTH, monthStart, today.withDayOfMonth(today.lengthOfMonth()));
            }
        };
    }

    private void assertFreshPeriod(UpdateCurrentPeriodCompletionRequest request, ResolvedPeriod period) {
        if (request.scope() != period.scope() || !request.periodStartDate().equals(period.periodStartDate())) {
            throw new BadRequestException(STALE_PERIOD_MESSAGE);
        }
    }

    private LocalDate familyLocalToday(Family family) {
        return LocalDate.now(clock.withZone(ZoneId.of(resolveTimezone(family))));
    }

    private String resolveTimezone(Family family) {
        return FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone());
    }

    private ChoreCurrentPeriodStateResponse toCurrentPeriodStateResponse(
            ChoreTemplate template,
            ResolvedPeriod period,
            ChorePeriodCompletion completion,
            LocalDate today
    ) {
        return new ChoreCurrentPeriodStateResponse(
                period.scope(),
                period.periodStartDate(),
                period.periodEndDate(),
                toBoardItem(template, completion, today)
        );
    }

    private ChoreBoardItemResponse toBoardItem(ChoreTemplate template, ChorePeriodCompletion completion,
                                               LocalDate today) {
        LocalDate dueDate = effectiveDueDate(template, today);
        ResolvedPeriod period = resolveCurrentPeriod(template, today);
        boolean completionAvailable = !today.isBefore(period.periodStartDate());
        ChoreDueState dueState = completion != null ? ChoreDueState.COMPLETE
                : dueDate == null ? ChoreDueState.UNSCHEDULED
                : today.isBefore(dueDate) ? ChoreDueState.UPCOMING
                : today.isAfter(dueDate) ? ChoreDueState.OVERDUE : ChoreDueState.DUE;
        return new ChoreBoardItemResponse(
                template.getId(),
                template.getTitle(),
                template.getCadence(),
                template.getAssignedToMember().getId(),
                completion != null,
                completion != null ? completion.getCompletedAt() : null,
                template.getDueWeekday(),
                template.getDueDayOfMonth(),
                dueDate,
                dueState,
                template.getRecurrenceAnchorDate(),
                template.getCadence() == ChoreCadence.FORTNIGHTLY ? period.periodStartDate() : null,
                template.getCadence() == ChoreCadence.FORTNIGHTLY ? period.periodEndDate() : null,
                completionAvailable
        );
    }

    private LocalDate effectiveDueDate(ChoreTemplate template, LocalDate today) {
        return switch (template.getCadence()) {
            case DAILY -> today;
            case WEEKLY -> template.getDueWeekday() == null ? null
                    : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                            .plusDays(template.getDueWeekday().getValue() % 7);
            case FORTNIGHTLY -> resolveCurrentPeriod(template, today).periodStartDate().plusDays(7);
            case MONTHLY -> template.getDueDayOfMonth() == null ? null
                    : today.withDayOfMonth(Math.min(template.getDueDayOfMonth(), today.lengthOfMonth()));
        };
    }

    private void validateSchedule(ChoreCadence cadence, DayOfWeek weekday, Integer dayOfMonth,
                                  LocalDate anchor) {
        if (dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) {
            throw new BadRequestException("Due day of month must be between 1 and 31");
        }
        if ((cadence == ChoreCadence.DAILY && (weekday != null || dayOfMonth != null || anchor != null))
                || (cadence == ChoreCadence.WEEKLY && (dayOfMonth != null || anchor != null))
                || (cadence == ChoreCadence.FORTNIGHTLY && (weekday == null || anchor == null
                    || dayOfMonth != null || anchor.getDayOfWeek() != weekday))
                || (cadence == ChoreCadence.MONTHLY && (weekday != null || anchor != null))) {
            throw new BadRequestException("Schedule does not match chore cadence");
        }
    }

    private ChoreTemplateResponse toTemplateResponse(ChoreTemplate template) {
        return new ChoreTemplateResponse(
                template.getId(),
                template.getTitle(),
                template.getAssignedToMember().getId(),
                template.getCadence(),
                template.getActiveFrom(),
                template.getArchivedAt() != null,
                template.getCreatedAt(),
                template.getUpdatedAt(),
                template.getDueWeekday(),
                template.getDueDayOfMonth(),
                template.getRecurrenceAnchorDate()
        );
    }

    private record ResolvedPeriod(
            ChoreScope scope,
            LocalDate periodStartDate,
            LocalDate periodEndDate
    ) {
    }

    private record PeriodWindow(LocalDate start, LocalDate end) {
    }
}
