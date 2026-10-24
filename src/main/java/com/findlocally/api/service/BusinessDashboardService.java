package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.*;
import com.findlocally.api.utility.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.stereotype.Service
public class BusinessDashboardService {
    private final BusinessDashboardRepository repository;
    private final BusinessOnboardingRepository onboardingRepository;
    private final BusinessOnboardingService onboarding;
    private final BookingsRepository bookings;
    private final ReviewsRepository reviews;
    private final ProviderProfileRepository profiles;
    private final NotificationsRepository notifications;
    private final MediaService media;
    private final PublicMediaUrls urls;
    private final BusinessAccess access;
    private final CurrentUser current;
    private final Inputs inputs;
    private final Clock clock;
    private final ZoneId zone;
    public BusinessDashboardService(BusinessDashboardRepository repository, BusinessOnboardingRepository onboardingRepository,
            BusinessOnboardingService onboarding, BookingsRepository bookings, ReviewsRepository reviews,
            ProviderProfileRepository profiles, NotificationsRepository notifications, MediaService media, PublicMediaUrls urls,
            BusinessAccess access, CurrentUser current, Inputs inputs, Clock clock, @Value("${app.bookings.time-zone:Asia/Kolkata}") String zone) {
        this.repository = repository; this.onboardingRepository = onboardingRepository; this.onboarding = onboarding;
        this.bookings = bookings; this.reviews = reviews; this.profiles = profiles; this.notifications = notifications;
        this.media = media; this.urls = urls; this.access = access; this.current = current; this.inputs = inputs;
        this.clock = clock; this.zone = ZoneId.of(zone);
    }
    @Transactional(readOnly = true) public BusinessProfile getBusinessProfile() { return repository.profile(access.id()); }
    @Transactional public BusinessProfile updateBusinessProfile(BusinessProfileUpdate input) {
        UUID business = access.lock(); inputs.valid(input);
        if (input.getBusinessName() != null) inputs.text(input.getBusinessName(), 120);
        if (input.getNeighbourhood() != null) inputs.text(input.getNeighbourhood(), 100);
        String avatar = image(input.getAvatarUploadId(), "AVATAR"), cover = image(input.getCoverUploadId(), "COVER");
        repository.update(business, input, avatar, cover);
        return repository.profile(business);
    }
    @Transactional(readOnly = true) public AvailabilityInput getBusinessAvailability() { return repository.availability(access.id()); }
    @Transactional public AvailabilityInput updateBusinessAvailability(AvailabilityInput input) {
        UUID business = access.lock(); inputs.valid(input); onboarding.validateAvailability(input);
        onboardingRepository.saveAvailability(business, input);
        return repository.availability(business);
    }
    @Transactional(readOnly = true) public DashboardOverview getDashboardOverview() {
        UUID business = access.id();
        var today = LocalDate.now(clock.withZone(zone));
        var result = repository.overview(business, today.atStartOfDay(zone).toOffsetDateTime(), today.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        result.setMonthEarnings(repository.earnings(business, YearMonth.now(clock.withZone(zone)), zone).getTotal());
        result.setNeedsReply(bookings.businessPage(business, BookingStatus.AWAITING_CONFIRMATION, null, null, Pages.of(0, 5)).getContent());
        result.setRecentReviews(reviews.page(business, Pages.of(0, 5)).getContent());
        return result;
    }
    @Transactional(readOnly = true) public List<Booking> getDashboardSchedule(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366)
            throw Inputs.bad("Supply an ordered date range of at most 366 days");
        return bookings.businessSchedule(access.id(), from.atStartOfDay(zone).toOffsetDateTime(), to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }
    @Transactional(readOnly = true) public Earnings getEarnings(String month) {
        YearMonth selected;
        try {
            if (month != null && !month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            selected = month == null ? YearMonth.now(clock.withZone(zone)) : YearMonth.parse(month);
        } catch (DateTimeException | IllegalArgumentException ex) { throw Inputs.bad("Use month in YYYY-MM format"); }
        return repository.earnings(access.id(), selected, zone);
    }
    @Transactional(readOnly = true) public ReviewPage listBusinessReviews(Integer page, Integer size) {
        return reviews.page(access.id(), Pages.of(page, size));
    }
    @Transactional public Review replyToReview(UUID id, ReplyToReviewRequest input) {
        UUID business = access.id(); inputs.valid(input); inputs.text(input.getText(), 1000);
        var result = reviews.reply(business, id, input.getText());
        if (result == null) throw Inputs.missing("Review");
        return result;
    }
    @Transactional(readOnly = true) public MediaPage listOwnMedia(Integer page, Integer size) {
        return profiles.media(access.id(), null, Pages.of(page, size));
    }
    @Transactional public MediaItem addOwnMedia(AddOwnMediaRequest input) {
        UUID business = access.lock(); inputs.valid(input);
        var upload = media.confirm(current.id(), input.getUploadId(), Set.of("GALLERY_PHOTO", "GALLERY_VIDEO"));
        var existing = profiles.findMedia(business, upload.id());
        return existing != null ? existing : profiles.addMedia(business, upload, urls.url(upload.key()), input.getCaption());
    }
    @Transactional public void deleteOwnMedia(UUID id) {
        if (profiles.deleteMedia(access.lock(), id) == 0) throw Inputs.missing("Media item");
    }
    private String image(UUID upload, String purpose) {
        return upload == null ? null : urls.url(media.confirm(current.id(), upload, Set.of(purpose)).key());
    }
}

