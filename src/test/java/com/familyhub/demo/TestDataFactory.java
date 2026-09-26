package com.familyhub.demo;

import com.familyhub.demo.dto.*;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.ChoreCadence;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.ListCategory;
import com.familyhub.demo.model.ListCategoryDisplayMode;
import com.familyhub.demo.model.ListKind;
import com.familyhub.demo.model.Recipe;
import com.familyhub.demo.model.SharedList;
import com.familyhub.demo.model.SharedListItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TestDataFactory {

    public static final UUID FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID CHORE_TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    public static final UUID LIST_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    public static final UUID LIST_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    public static final UUID LIST_CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    public static final UUID RECIPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    public static final UUID OTHER_FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private TestDataFactory() {
    }

    // --- Entity factories ---

    public static Family createFamily() {
        Family family = new Family();
        family.setId(FAMILY_ID);
        family.setName("Test Family");
        family.setUsername("testfamily");
        family.setPasswordHash("$2a$10$dummyhashfortesting");
        family.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        family.setUpdatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        return family;
    }

    public static Family createOtherFamily() {
        Family family = new Family();
        family.setId(OTHER_FAMILY_ID);
        family.setName("Other Family");
        family.setUsername("otherfamily");
        family.setPasswordHash("$2a$10$dummyhashfortesting");
        family.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        family.setUpdatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        return family;
    }

    public static FamilyMember createFamilyMember(Family family) {
        FamilyMember member = new FamilyMember();
        member.setId(MEMBER_ID);
        member.setName("Test Member");
        member.setColor(FamilyColor.CORAL);
        member.setEmail("member@test.com");
        member.setFamily(family);
        return member;
    }

    public static CalendarEvent createCalendarEvent(Family family, FamilyMember member) {
        CalendarEvent event = new CalendarEvent();
        event.setId(EVENT_ID);
        event.setTitle("Test Event");
        event.setStartTime(LocalTime.of(9, 0));
        event.setEndTime(LocalTime.of(10, 0));
        event.setDate(LocalDate.of(2025, 6, 15));
        event.setFamily(family);
        event.getAudienceMembers().add(member);
        event.setAllDay(false);
        event.setLocation("Test Location");
        return event;
    }

    // --- DTO factories ---

    public static RegisterRequest createRegisterRequest() {
        return new RegisterRequest(
                "testfamily",
                "password123",
                "Test Family",
                List.of(createFamilyMemberRequest()),
                "America/Los_Angeles"
        );
    }

    public static LoginRequest createLoginRequest() {
        return new LoginRequest("testfamily", "password123");
    }

    public static FamilyMemberRequest createFamilyMemberRequest() {
        return new FamilyMemberRequest(
                "Test Member",
                FamilyColor.CORAL,
                "member@test.com",
                null
        );
    }

    public static CalendarEventRequest createCalendarEventRequest(UUID memberId) {
        return new CalendarEventRequest(
                "Test Event",
                "9:00 AM",
                "10:00 AM",
                LocalDate.of(2025, 6, 15),
                memberId,
                false,
                "Test Location",
                null,
                null,
                null
        );
    }

    public static CalendarEventRequest createMultiDayCalendarEventRequest(UUID memberId) {
        return new CalendarEventRequest(
                "Vacation",
                "12:00 AM",
                "12:00 AM",
                LocalDate.of(2025, 3, 7),
                memberId,
                true,
                null,
                LocalDate.of(2025, 3, 9),
                null,
                null
        );
    }

    public static CalendarEventRequest createRecurringCalendarEventRequest(UUID memberId) {
        return new CalendarEventRequest(
                "Preschool",
                "9:00 AM",
                "12:00 PM",
                LocalDate.of(2025, 6, 3),
                memberId,
                false,
                "School",
                null,
                "FREQ=WEEKLY;BYDAY=TU,TH,FR",
                null
        );
    }

    public static CalendarEvent createRecurringCalendarEvent(Family family, FamilyMember member) {
        CalendarEvent event = new CalendarEvent();
        event.setId(UUID.randomUUID());
        event.setTitle("Preschool");
        event.setStartTime(LocalTime.of(9, 0));
        event.setEndTime(LocalTime.of(12, 0));
        event.setDate(LocalDate.of(2025, 6, 3));
        event.setFamily(family);
        event.getAudienceMembers().add(member);
        event.setAllDay(false);
        event.setLocation("School");
        event.setRecurrenceRule("FREQ=WEEKLY;BYDAY=TU,TH,FR");
        return event;
    }

    public static CalendarEvent createMultiDayCalendarEvent(Family family, FamilyMember member) {
        CalendarEvent event = new CalendarEvent();
        event.setId(UUID.randomUUID());
        event.setTitle("Vacation");
        event.setStartTime(LocalTime.MIDNIGHT);
        event.setEndTime(LocalTime.MIDNIGHT);
        event.setDate(LocalDate.of(2025, 3, 7));
        event.setEndDate(LocalDate.of(2025, 3, 9));
        event.setFamily(family);
        event.getAudienceMembers().add(member);
        event.setAllDay(true);
        return event;
    }

    public static FamilyRequest createFamilyRequest() {
        return new FamilyRequest("Updated Family", "testfamily", null);
    }

    public static FamilyResponse createFamilyResponse(Family family) {
        return new FamilyResponse(
                family.getId(),
                family.getName(),
                family.getTimezone(),
                family.getFamilyMembers() != null
                        ? family.getFamilyMembers().stream()
                        .map(m -> new FamilyMemberResponse(
                                m.getId(),
                                m.getName(),
                                m.getColor(),
                                m.getEmail(),
                                m.getAvatarUrl()
                        ))
                        .toList()
                        : List.of(),
                family.getCreatedAt()
        );
    }

    public static FamilyMemberResponse createFamilyMemberResponse() {
        return new FamilyMemberResponse(
                MEMBER_ID,
                "Test Member",
                FamilyColor.CORAL,
                "member@test.com",
                null
        );
    }

    public static CalendarEventResponse createCalendarEventResponse() {
        return CalendarEventResponse.builder()
                .id(EVENT_ID)
                .title("Test Event")
                .startTime("9:00 AM")
                .endTime("10:00 AM")
                .date(LocalDate.of(2025, 6, 15))
                .memberId(MEMBER_ID)
                .isAllDay(false)
                .location("Test Location")
                .endDate(null)
                .recurrenceRule(null)
                .recurringEventId(null)
                .isRecurring(false)
                .source("NATIVE")
                .description(null)
                .htmlLink(null)
                .build();
    }

    public static ChoreTemplate createChoreTemplate(
            Family family,
            FamilyMember member,
            String title,
            ChoreCadence cadence,
            LocalDate activeFrom
    ) {
        ChoreTemplate template = new ChoreTemplate();
        template.setId(UUID.randomUUID());
        template.setFamily(family);
        template.setAssignedToMember(member);
        template.setTitle(title);
        template.setCadence(cadence);
        template.setActiveFrom(activeFrom);
        template.setCreatedAt(activeFrom.atStartOfDay());
        template.setUpdatedAt(activeFrom.atStartOfDay());
        return template;
    }

    public static SharedList createGroceryList(Family family) {
        SharedList list = new SharedList();
        list.setId(LIST_ID);
        list.setFamily(family);
        list.setName("Trader Joe's Run");
        list.setKind(ListKind.GROCERY);
        list.setCategoryDisplayMode(ListCategoryDisplayMode.GROUPED);
        list.setShowCompletedOverride(null);
        list.setItems(new ArrayList<>());
        list.setCreatedAt(LocalDateTime.of(2026, 5, 6, 9, 0));
        list.setUpdatedAt(LocalDateTime.of(2026, 5, 6, 9, 0));
        return list;
    }

    public static SharedList createGeneralList(Family family) {
        SharedList list = createGroceryList(family);
        list.setKind(ListKind.GENERAL);
        list.setCategoryDisplayMode(ListCategoryDisplayMode.FLAT);
        list.setName("Movies to Watch");
        return list;
    }

    public static ListCategory createListCategory(Family family, ListKind kind, String name, int sortOrder) {
        ListCategory category = new ListCategory();
        category.setId(LIST_CATEGORY_ID);
        category.setFamily(family);
        category.setKind(kind);
        category.setName(name);
        category.setSortOrder(sortOrder);
        category.setCreatedAt(LocalDateTime.of(2026, 5, 6, 9, 0));
        category.setUpdatedAt(LocalDateTime.of(2026, 5, 6, 9, 0));
        return category;
    }

    public static SharedListItem createListItem(
            SharedList list,
            UUID id,
            String text,
            ListCategory category,
            boolean completed,
            LocalDateTime completedAt
    ) {
        SharedListItem item = new SharedListItem();
        item.setId(id);
        item.setList(list);
        item.setCategory(category);
        item.setText(text);
        item.setCompleted(completed);
        item.setCompletedAt(completedAt);
        item.setCreatedAt(LocalDateTime.of(2026, 5, 6, 9, 0));
        item.setUpdatedAt(LocalDateTime.of(2026, 5, 6, 9, 0));
        return item;
    }

    public static SharedList createListWithCompletedItems(Family family) {
        SharedList list = createGroceryList(family);
        ListCategory produceCategory = createListCategory(family, ListKind.GROCERY, "Produce", 0);
        SharedListItem bananas = createListItem(
                list,
                LIST_ITEM_ID,
                "Bananas",
                produceCategory,
                true,
                LocalDateTime.of(2026, 5, 6, 10, 0)
        );
        SharedListItem spinach = createListItem(
                list,
                UUID.fromString("00000000-0000-0000-0000-000000000008"),
                "Spinach",
                produceCategory,
                true,
                LocalDateTime.of(2026, 5, 6, 10, 5)
        );
        SharedListItem yogurt = createListItem(
                list,
                UUID.fromString("00000000-0000-0000-0000-000000000009"),
                "Greek yogurt",
                null,
                false,
                null
        );
        list.setItems(new ArrayList<>(List.of(bananas, spinach, yogurt)));
        return list;
    }

    public static Recipe createRecipe(Family family, String title) {
        Recipe recipe = new Recipe();
        recipe.setId(UUID.randomUUID());
        recipe.setFamily(family);
        recipe.setTitle(title);
        recipe.setImageUrl(null);
        recipe.setNote(null);
        recipe.setSourceUrl(null);
        recipe.setFavorite(false);
        recipe.setCreatedAt(LocalDateTime.of(2026, 6, 2, 9, 0));
        recipe.setUpdatedAt(LocalDateTime.of(2026, 6, 2, 9, 0));
        return recipe;
    }

    public static ListSummaryResponse sampleListSummaryResponse() {
        return new ListSummaryResponse(
                LIST_ID,
                "Trader Joe's Run",
                ListKind.GROCERY,
                3,
                1
        );
    }

    public static ListCategoryOption sampleListCategoryOption() {
        return new ListCategoryOption(
                LIST_CATEGORY_ID,
                ListKind.GROCERY,
                "Produce",
                0
        );
    }

    public static ListItemResponse sampleListItemResponse() {
        return new ListItemResponse(
                LIST_ITEM_ID,
                "Bananas",
                false,
                null,
                LIST_CATEGORY_ID,
                LocalDateTime.of(2026, 5, 6, 9, 0),
                LocalDateTime.of(2026, 5, 6, 9, 0)
        );
    }

    public static ListDetailResponse sampleListDetailResponse() {
        return new ListDetailResponse(
                LIST_ID,
                "Trader Joe's Run",
                ListKind.GROCERY,
                ListCategoryDisplayMode.GROUPED,
                null,
                List.of(sampleListCategoryOption()),
                List.of(sampleListItemResponse()),
                LocalDateTime.of(2026, 5, 6, 9, 0),
                LocalDateTime.of(2026, 5, 6, 9, 0)
        );
    }

    public static RecipeSummaryResponse sampleRecipeSummaryResponse() {
        return new RecipeSummaryResponse(
                RECIPE_ID,
                "Sheet Pan Gnocchi",
                "https://cdn.example.com/gnocchi.jpg",
                true,
                List.of("dinner", "weeknight"),
                LocalDateTime.of(2026, 6, 2, 9, 0)
        );
    }

    public static RecipeDetailResponse sampleRecipeDetailResponse() {
        return new RecipeDetailResponse(
                RECIPE_ID,
                "Sheet Pan Gnocchi",
                "https://cdn.example.com/gnocchi.jpg",
                List.of("1 lb shelf-stable gnocchi", "2 cups cherry tomatoes"),
                List.of("Heat oven to 425F", "Roast for 20 minutes"),
                "Weeknight favorite",
                "https://example.com/gnocchi",
                List.of("dinner", "weeknight"),
                true,
                LocalDateTime.of(2026, 6, 2, 9, 0)
        );
    }
}
