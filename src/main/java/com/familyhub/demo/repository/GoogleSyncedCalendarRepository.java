package com.familyhub.demo.repository;

import com.familyhub.demo.model.GoogleSyncedCalendar;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoogleSyncedCalendarRepository extends JpaRepository<GoogleSyncedCalendar, UUID> {
    List<GoogleSyncedCalendar> findByMemberId(UUID memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GoogleSyncedCalendar c where c.member.id = :memberId")
    List<GoogleSyncedCalendar> findByMemberIdForUpdate(@Param("memberId") UUID memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GoogleSyncedCalendar c where c.id = :id")
    Optional<GoogleSyncedCalendar> findByIdForUpdate(@Param("id") UUID id);

    List<GoogleSyncedCalendar> findByMemberIdAndEnabledTrue(UUID memberId);

    Optional<GoogleSyncedCalendar> findByMemberIdAndGoogleCalendarId(UUID memberId, String googleCalendarId);

    void deleteByMemberId(UUID memberId);
}
