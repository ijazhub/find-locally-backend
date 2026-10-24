package com.findlocally.api.service;

import com.findlocally.api.repository.CatalogRepository;
import com.findlocally.api.repository.CatalogRepository.*;
import com.findlocally.api.repository.BusinessServicesRepository;
import com.findlocally.api.model.*;
import com.findlocally.api.utility.CurrentUser;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
public class CatalogService {
    private final CatalogRepository repository;
    private final CategoryService categoryService;
    private final BusinessServicesRepository services;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final ZoneId zone;
    private final int slotInterval;

    public CatalogService(CatalogRepository repository, CategoryService categoryService, BusinessServicesRepository services,
            CurrentUser currentUser, Clock clock, @Value("${app.bookings.time-zone:Asia/Kolkata}") String timeZone,
            @Value("${app.catalog.slot-interval-minutes:30}") int slotInterval) {
        this.repository = repository;
        this.categoryService = categoryService;
        this.services = services;
        this.currentUser = currentUser;
        this.clock = clock;
        this.zone = ZoneId.of(timeZone);
        if (slotInterval < 5 || slotInterval > 120) throw new IllegalArgumentException("Slot interval must be between 5 and 120 minutes");
        this.slotInterval = slotInterval;
    }

    @Transactional(readOnly = true)
    public HomeResponse getHome(String city) {
        return new HomeResponse().categories(listCategories())
                .featured(searchProviders(null, null, city, null, null, null, null, null, false, "RECOMMENDED", 0, 8).getContent());
    }

    @Transactional(readOnly = true)
    public ProviderDetail getProvider(UUID providerId) {
        var row = requireProvider(providerId);
        var p = row.summary();
        var detail = new ProviderDetail().id(p.getId()).businessName(p.getBusinessName()).ownerName(p.getOwnerName())
                .category(p.getCategory()).neighbourhood(p.getNeighbourhood()).tags(p.getTags()).badges(p.getBadges())
                .coverImageUrl(p.getCoverImageUrl()).rating(p.getRating()).reviewCount(p.getReviewCount())
                .startingPrice(p.getStartingPrice()).saved(p.getSaved()).followed(row.followed()).bio(row.bio())
                .services(activeServices(providerId));
        if (row.avatar() != null) detail.setAvatarUrl(URI.create(row.avatar()));
        if (row.workMode() != null) detail.setWorkMode(ProviderDetail.WorkModeEnum.fromValue(row.workMode()));
        return detail;
    }

    @Transactional(readOnly = true)
    public List<Category> listCategories() { return categoryService.fetchCategories(); }

    @Transactional(readOnly = true)
    public List<com.findlocally.api.model.Service> listProviderServices(UUID providerId) {
        requireProvider(providerId);
        return activeServices(providerId);
    }

    @Transactional(readOnly = true)
    public ProviderPage searchProviders(String category, String q, String city, Double lat, Double lng,
            BigDecimal maxDistanceKm, BigDecimal minRating, Long maxPrice, Boolean verifiedOnly, String sort, Integer page, Integer size) {
        String order = sort == null ? "RECOMMENDED" : sort;
        int selectedPage = page == null ? 0 : page, selectedSize = size == null ? 20 : size;
        if (!List.of("RECOMMENDED", "NEAREST", "RATING", "PRICE_LOW", "PRICE_HIGH").contains(order)
                || selectedPage < 0 || selectedSize < 1 || selectedSize > 50) throw badRequest("Invalid sort or pagination");
        if ((lat == null) != (lng == null) || (lat != null &&
                (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180))) {
            throw badRequest("Supply valid latitude and longitude together");
        }
        if ((order.equals("NEAREST") || maxDistanceKm != null) && lat == null) throw badRequest("Distance queries require coordinates");
        if ((maxDistanceKm != null && maxDistanceKm.signum() < 0)
                || (minRating != null && (minRating.signum() < 0 || minRating.compareTo(BigDecimal.valueOf(5)) > 0))
                || (maxPrice != null && maxPrice < 0)) throw badRequest("Invalid distance, rating or price filter");
        var search = new Search(text(category, 50), text(q, 200), text(city, 100), lat, lng, maxDistanceKm,
                minRating, maxPrice, Boolean.TRUE.equals(verifiedOnly), order, selectedPage, selectedSize, currentUser.optionalId());
        long count = repository.count(search);
        return new ProviderPage().page(selectedPage).size(selectedSize).totalElements(count)
                .totalPages(Math.toIntExact((count + selectedSize - 1) / selectedSize)).content(repository.search(search));
    }

    @Transactional(readOnly = true)
    public List<Slot> getProviderSlots(UUID providerId, UUID serviceId, LocalDate date) {
        if (date == null) throw badRequest("date is required");
        var offer = requireOffer(providerId, serviceId);
        var schedule = schedule(providerId, date, date);
        return slots(date, offer, schedule);
    }

    @Transactional(readOnly = true)
    public GetProviderCalendar200Response getProviderCalendar(UUID providerId, UUID serviceId, String month) {
        YearMonth selected;
        try {
            if (month == null || !month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            selected = YearMonth.parse(month);
        } catch (java.time.DateTimeException | IllegalArgumentException exception) { throw badRequest("Use month in YYYY-MM format"); }
        var offer = requireOffer(providerId, serviceId);
        var start = selected.atDay(1);
        var schedule = schedule(providerId, start, selected.atEndOfMonth());
        var dates = start.datesUntil(selected.plusMonths(1).atDay(1))
                .filter(date -> slots(date, offer, schedule).stream().anyMatch(slot -> Boolean.TRUE.equals(slot.getAvailable()))).toList();
        return new GetProviderCalendar200Response().availableDates(dates);
    }

    private record Schedule(List<Hours> hours, java.util.Set<LocalDate> blocked, List<Busy> busy) {}

    private Schedule schedule(UUID provider, LocalDate first, LocalDate last) {
        return new Schedule(repository.hours(provider), repository.blocked(provider, first, last),
                repository.busy(provider, first.atStartOfDay(zone).toOffsetDateTime(),
                        last.plusDays(1).atStartOfDay(zone).toOffsetDateTime()));
    }

    private List<Slot> slots(LocalDate date, Offer offer, Schedule schedule) {
        // Appointment-only providers arrange times directly; they do not advertise automatic slots.
        if (offer.appointmentOnly() || date.isBefore(LocalDate.now(clock.withZone(zone))) || schedule.blocked().contains(date)) return List.of();
        var hours = schedule.hours().stream().filter(day -> day.day().equals(date.getDayOfWeek().name().substring(0, 3)))
                .findFirst().orElse(null);
        if (hours == null || !hours.open() || hours.from() == null || hours.to() == null) return List.of();
        var close = date.atTime(hours.to()).atZone(zone);
        var result = new java.util.ArrayList<Slot>();
        for (var start = date.atTime(hours.from()).atZone(zone); !start.plusMinutes(offer.duration()).isAfter(close);
                start = start.plusMinutes(slotInterval)) {
            if (!start.toInstant().isAfter(clock.instant())) continue;
            var end = start.plusMinutes(offer.duration());
            Instant from = start.toInstant(), to = end.toInstant();
            boolean available = schedule.busy().stream().noneMatch(busy -> from.isBefore(busy.end()) && to.isAfter(busy.start()));
            result.add(new Slot().startsAt(start.toOffsetDateTime()).endsAt(end.toOffsetDateTime()).available(available));
        }
        return result;
    }

    private List<com.findlocally.api.model.Service> activeServices(UUID provider) {
        return services.findAll(provider).stream().filter(service -> Boolean.TRUE.equals(service.getActive())).toList();
    }

    private ProviderRow requireProvider(UUID provider) {
        if (provider == null) throw badRequest("providerId is required");
        var row = repository.provider(provider, currentUser.optionalId());
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found");
        return row;
    }

    private Offer requireOffer(UUID provider, UUID service) {
        if (provider == null || service == null) throw badRequest("providerId and serviceId are required");
        var offer = repository.offer(provider, service);
        if (offer == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active provider service not found");
        return offer;
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max) throw badRequest("Search value is too long");
        return value.trim();
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

