package com.familyhub.demo.repository;

import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChorePeriodCompletionRepository extends JpaRepository<ChorePeriodCompletion, UUID> {
    @Query("""
            select c
            from ChorePeriodCompletion c
            where c.choreTemplate.id in :templateIds
              and c.periodStartDate = :periodStart
              and c.periodEndDate = :periodEnd
            """)
    List<ChorePeriodCompletion> findByTemplateIdsAndPeriod(
            @Param("templateIds") List<UUID> templateIds,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    Optional<ChorePeriodCompletion> findByChoreTemplateAndPeriodStartDateAndPeriodEndDate(
            ChoreTemplate choreTemplate,
            LocalDate periodStartDate,
            LocalDate periodEndDate
    );

    List<ChorePeriodCompletion> findByChoreTemplateIdIn(List<UUID> templateIds);

    List<ChorePeriodCompletion> findByChoreTemplate(ChoreTemplate choreTemplate);

    void deleteByChoreTemplate(ChoreTemplate choreTemplate);
}
