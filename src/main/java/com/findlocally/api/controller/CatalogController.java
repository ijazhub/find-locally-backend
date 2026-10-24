package com.findlocally.api.controller;

import com.findlocally.api.contract.CatalogApi;
import com.findlocally.api.service.CatalogService;
import java.math.BigDecimal;
import com.findlocally.api.model.Category;
import com.findlocally.api.model.GetProviderCalendar200Response;
import com.findlocally.api.model.HomeResponse;
import java.time.LocalDate;
import com.findlocally.api.model.ProviderDetail;
import com.findlocally.api.model.ProviderPage;
import com.findlocally.api.model.Service;
import com.findlocally.api.model.Slot;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogController implements CatalogApi {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<HomeResponse> getHome(String city) {
        return ResponseEntity.status(200).body(service.getHome(city));
    }

    @Override
    public ResponseEntity<ProviderDetail> getProvider(UUID providerId) {
        return ResponseEntity.status(200).body(service.getProvider(providerId));
    }

    @Override
    public ResponseEntity<GetProviderCalendar200Response> getProviderCalendar(UUID providerId, UUID serviceId, String month) {
        return ResponseEntity.status(200).body(service.getProviderCalendar(providerId, serviceId, month));
    }

    @Override
    public ResponseEntity<List<Slot>> getProviderSlots(UUID providerId, UUID serviceId, LocalDate date) {
        return ResponseEntity.status(200).body(service.getProviderSlots(providerId, serviceId, date));
    }

    @Override
    public ResponseEntity<List<Category>> listCategories() {
        return ResponseEntity.status(200).body(service.listCategories());
    }

    @Override
    public ResponseEntity<List<Service>> listProviderServices(UUID providerId) {
        return ResponseEntity.status(200).body(service.listProviderServices(providerId));
    }

    @Override
    public ResponseEntity<ProviderPage> searchProviders(String category, String q, String city, Double lat, Double lng, BigDecimal maxDistanceKm, BigDecimal minRating, Long maxPrice, Boolean verifiedOnly, String sort, Integer page, Integer size) {
        return ResponseEntity.status(200).body(service.searchProviders(category, q, city, lat, lng, maxDistanceKm, minRating, maxPrice, verifiedOnly, sort, page, size));
    }
}

