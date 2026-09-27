package com.familyhub.demo.service;

import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.GoogleSyncedCalendar;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleEventDeletionPersistenceServiceTest {
    @Mock GoogleSyncedCalendarRepository calendars;
    @Mock CalendarEventRepository events;
    @InjectMocks GoogleEventDeletionPersistenceService service;

    @Test
    void removesOnlyTheExpectedFamilyCalendarAndGoogleIdentity() {
        Family family = new Family();
        family.setId(UUID.randomUUID());
        GoogleSyncedCalendar calendar = new GoogleSyncedCalendar();
        calendar.setId(UUID.randomUUID());
        CalendarEvent event = new CalendarEvent();
        event.setId(UUID.randomUUID());
        event.setFamily(family);
        event.setSource(EventSource.GOOGLE);
        event.setSyncedCalendar(calendar);
        event.setGoogleEventId("google-1");
        when(calendars.findByIdForUpdate(calendar.getId())).thenReturn(Optional.of(calendar));
        when(events.findByFamilyAndId(family, event.getId())).thenReturn(Optional.of(event));

        service.reconcile(family, event.getId(), calendar.getId(), "google-1");

        verify(events).delete(event);
        verify(events).flush();
    }
}
