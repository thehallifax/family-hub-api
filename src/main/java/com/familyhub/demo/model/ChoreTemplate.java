package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.UUID;

@Entity
@Table(name = "chore_template")
@Getter
@Setter
public class ChoreTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @ManyToOne
    @JoinColumn(name = "assigned_to_member_id", nullable = false)
    private FamilyMember assignedToMember;

    @Column(nullable = false, length = 100)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChoreCadence cadence;

    @Enumerated(EnumType.STRING)
    @Column(name = "due_weekday", length = 9)
    private DayOfWeek dueWeekday;

    @Column(name = "due_day_of_month")
    private Integer dueDayOfMonth;

    @Column(name = "recurrence_anchor_date")
    private LocalDate recurrenceAnchorDate;

    @Column(nullable = false)
    private LocalDate activeFrom;

    private LocalDateTime archivedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
