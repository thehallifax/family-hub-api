package com.familyhub.demo.service;

import com.familyhub.demo.dto.ChoreBoardItemResponse;
import com.familyhub.demo.dto.ChoreDueState;
import com.familyhub.demo.dto.CreateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateCurrentPeriodCompletionRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.ChoreCadence;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreScope;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.familyhub.demo.TestDataFactory.CHORE_TEMPLATE_ID;
import static com.familyhub.demo.TestDataFactory.MEMBER_ID;
import static com.familyhub.demo.TestDataFactory.createChoreTemplate;
import static com.familyhub.demo.TestDataFactory.createFamily;
import static com.familyhub.demo.TestDataFactory.createFamilyMember;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChoreOneOffTest {
    private final ChoreTemplateRepository templates = mock(ChoreTemplateRepository.class);
    private final ChorePeriodCompletionRepository completions = mock(ChorePeriodCompletionRepository.class);
    private final ChorePeriodCompletionWriter writer = mock(ChorePeriodCompletionWriter.class);
    private final FamilyMemberRepository members = mock(FamilyMemberRepository.class);
    private final Family family = createFamily();
    private final FamilyMember member = createFamilyMember(family);

    @BeforeEach
    void setUp() {
        reset(templates, completions, writer, members);
        family.setTimezone("UTC");
    }

    @Test
    void oneOffRequiresOnlyAnExplicitDueDate() {
        ChoreService service = serviceAt("2026-05-19T12:00:00Z");
        when(members.findById(MEMBER_ID)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> service.createTemplate(new CreateChoreTemplateRequest(
                "Passport", MEMBER_ID, ChoreCadence.ONE_OFF, LocalDate.of(2026, 5, 19),
                null, null, null, null), family)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createTemplate(new CreateChoreTemplateRequest(
                "Passport", MEMBER_ID, ChoreCadence.ONE_OFF, LocalDate.of(2026, 5, 19),
                DayOfWeek.TUESDAY, null, null, LocalDate.of(2026, 5, 20)), family))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.createTemplate(new CreateChoreTemplateRequest(
                "Passport", MEMBER_ID, ChoreCadence.WEEKLY, LocalDate.of(2026, 5, 19),
                DayOfWeek.TUESDAY, null, null, LocalDate.of(2026, 5, 20)), family))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void oneOffStateUsesFixedFamilyLocalDate() {
        ChoreTemplate template = oneOff(LocalDate.of(2026, 5, 20));
        assertThat(item(template, "2026-05-19T12:00:00Z", List.of()).dueState())
                .isEqualTo(ChoreDueState.UPCOMING);
        assertThat(item(template, "2026-05-20T12:00:00Z", List.of()).dueState())
                .isEqualTo(ChoreDueState.DUE);
        assertThat(item(template, "2026-05-21T12:00:00Z", List.of()).dueState())
                .isEqualTo(ChoreDueState.OVERDUE);

        family.setTimezone("America/Los_Angeles");
        assertThat(item(template, "2026-05-21T02:00:00Z", List.of()).dueState())
                .isEqualTo(ChoreDueState.DUE);
    }

    @Test
    void completionIsPermanentAcrossDatesAndDueDateEdits() {
        ChoreTemplate template = oneOff(LocalDate.of(2026, 5, 20));
        ChorePeriodCompletion completion = completion(template, LocalDate.of(2026, 5, 20));
        assertThat(item(template, "2027-09-01T12:00:00Z", List.of(completion)).dueState())
                .isEqualTo(ChoreDueState.COMPLETE);

        template.setOneOffDueDate(LocalDate.of(2027, 12, 31));
        ChoreBoardItemResponse edited = item(template, "2027-09-01T12:00:00Z", List.of(completion));
        assertThat(edited.dueState()).isEqualTo(ChoreDueState.COMPLETE);
        assertThat(edited.dueDate()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void normalCompletionApiUsesTheFixedOneOffPeriod() {
        LocalDate dueDate = LocalDate.of(2026, 5, 20);
        ChoreTemplate template = oneOff(dueDate);
        ChoreService service = serviceAt("2026-05-19T12:00:00Z");
        when(templates.findByFamilyAndId(family, template.getId())).thenReturn(Optional.of(template));
        when(completions.findByChoreTemplate(template)).thenReturn(List.of());
        when(writer.createCompletion(template.getId(), dueDate, dueDate, LocalDateTime.of(2026, 5, 19, 12, 0)))
                .thenReturn(completion(template, dueDate));

        var state = service.completeCurrentPeriod(template.getId(),
                new UpdateCurrentPeriodCompletionRequest(ChoreScope.THIS_MONTH, dueDate), family);

        assertThat(state.item().dueState()).isEqualTo(ChoreDueState.COMPLETE);
        assertThat(state.periodStartDate()).isEqualTo(dueDate);
        assertThat(state.periodEndDate()).isEqualTo(dueDate);
    }

    @Test
    void conversionToAndFromOneOffClearsSchedulesAndCompletionHistory() {
        ChoreTemplate weekly = createChoreTemplate(family, member, "Bins", ChoreCadence.WEEKLY,
                LocalDate.of(2026, 1, 1));
        weekly.setDueWeekday(DayOfWeek.TUESDAY);
        when(templates.findByFamilyAndId(family, weekly.getId())).thenReturn(Optional.of(weekly));
        when(templates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ChoreService service = serviceAt("2026-05-19T12:00:00Z");

        service.updateTemplate(weekly.getId(), new UpdateChoreTemplateRequest(
                null, null, ChoreCadence.ONE_OFF, null, null,
                null, null, null, LocalDate.of(2026, 6, 1)), family);
        assertThat(weekly.getCadence()).isEqualTo(ChoreCadence.ONE_OFF);
        assertThat(weekly.getDueWeekday()).isNull();
        assertThat(weekly.getDueDayOfMonth()).isNull();
        assertThat(weekly.getRecurrenceAnchorDate()).isNull();
        assertThat(weekly.getOneOffDueDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        verify(completions).deleteByChoreTemplate(weekly);

        reset(completions);
        service.updateTemplate(weekly.getId(), new UpdateChoreTemplateRequest(
                null, null, ChoreCadence.MONTHLY, null, null,
                null, 15, null, null), family);
        assertThat(weekly.getCadence()).isEqualTo(ChoreCadence.MONTHLY);
        assertThat(weekly.getDueDayOfMonth()).isEqualTo(15);
        assertThat(weekly.getOneOffDueDate()).isNull();
        verify(completions).deleteByChoreTemplate(weekly);
    }

    private ChoreBoardItemResponse item(ChoreTemplate template, String instant,
                                        List<ChorePeriodCompletion> completionRows) {
        ChoreService service = serviceAt(instant);
        when(templates.findActiveByFamily(family)).thenReturn(List.of(template));
        when(completions.findByChoreTemplateIdIn(List.of(template.getId()))).thenReturn(completionRows);
        return service.getBoard(family).thisMonth().assignees().getFirst().chores().getFirst();
    }

    private ChoreService serviceAt(String instant) {
        return new ChoreService(templates, completions, writer, members,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private ChoreTemplate oneOff(LocalDate dueDate) {
        ChoreTemplate template = createChoreTemplate(family, member, "Passport", ChoreCadence.ONE_OFF,
                LocalDate.of(2026, 1, 1));
        template.setId(CHORE_TEMPLATE_ID);
        template.setOneOffDueDate(dueDate);
        return template;
    }

    private ChorePeriodCompletion completion(ChoreTemplate template, LocalDate periodDate) {
        ChorePeriodCompletion completion = new ChorePeriodCompletion();
        completion.setChoreTemplate(template);
        completion.setPeriodStartDate(periodDate);
        completion.setPeriodEndDate(periodDate);
        completion.setCompletedAt(periodDate.atTime(12, 0));
        return completion;
    }
}
