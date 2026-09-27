package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleEventDeletionPersistenceService {
    private final GoogleSyncedCalendarRepository calendars;
    private final CalendarEventRepository events;

    @Transactional
    public void reconcile(Family family, UUID eventId, UUID calendarId, String googleEventId) {
        // Serialize against incremental/full sync persistence for this calendar.
        calendars.findByIdForUpdate(calendarId);
        events.findByFamilyAndId(family, eventId).ifPresent(event -> {
            if (event.getSource() != EventSource.GOOGLE
                    || event.getSyncedCalendar() == null
                    || !event.getSyncedCalendar().getId().equals(calendarId)
                    || !googleEventId.equals(event.getGoogleEventId())) {
                throw new BadRequestException("Google event linkage changed; use Sync Now before retrying");
            }
            events.delete(event);
            events.flush();
        });
    }
}
