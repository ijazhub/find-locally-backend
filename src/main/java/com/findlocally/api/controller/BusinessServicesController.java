package com.findlocally.api.controller;

import com.findlocally.api.contract.BusinessServicesApi;
import com.findlocally.api.service.BusinessServicesService;
import com.findlocally.api.model.Service;
import com.findlocally.api.model.ServiceInput;
import com.findlocally.api.model.SetServiceActiveRequest;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BusinessServicesController implements BusinessServicesApi {
    private final BusinessServicesService service;

    public BusinessServicesController(BusinessServicesService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Service> createService(ServiceInput serviceInput) {
        return ResponseEntity.status(201).body(service.createService(serviceInput));
    }

    @Override
    public ResponseEntity<Void> deleteService(UUID serviceId) {
        service.deleteService(serviceId);
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<List<Service>> listOwnServices() {
        return ResponseEntity.status(200).body(service.listOwnServices());
    }

    @Override
    public ResponseEntity<Service> setServiceActive(UUID serviceId, SetServiceActiveRequest setServiceActiveRequest) {
        return ResponseEntity.status(200).body(service.setServiceActive(serviceId, setServiceActiveRequest));
    }

    @Override
    public ResponseEntity<Service> updateService(UUID serviceId, ServiceInput serviceInput) {
        return ResponseEntity.status(200).body(service.updateService(serviceId, serviceInput));
    }
}

