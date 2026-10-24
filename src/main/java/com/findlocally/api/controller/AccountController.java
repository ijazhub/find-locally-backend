package com.findlocally.api.controller;

import com.findlocally.api.contract.AccountApi;
import com.findlocally.api.model.Address;
import com.findlocally.api.model.AddressInput;
import com.findlocally.api.model.UserProfile;
import com.findlocally.api.model.UserUpdate;
import com.findlocally.api.service.AccountService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for the current user's profile and addresses. */
@RestController
@RequestMapping("/api/v1")
public class AccountController implements AccountApi {
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Override
    public ResponseEntity<Address> createAddress(AddressInput addressInput) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAddress(addressInput));
    }

    @Override
    public ResponseEntity<Void> deleteAddress(UUID addressId) {
        accountService.deleteAddress(addressId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<UserProfile> getMe() {
        return ResponseEntity.ok(accountService.getMe());
    }

    @Override
    public ResponseEntity<List<Address>> listAddresses() {
        return ResponseEntity.ok(accountService.listAddresses());
    }

    @Override
    public ResponseEntity<Address> updateAddress(UUID addressId, AddressInput addressInput) {
        return ResponseEntity.ok(accountService.updateAddress(addressId, addressInput));
    }

    @Override
    public ResponseEntity<UserProfile> updateMe(UserUpdate userUpdate) {
        return ResponseEntity.ok(accountService.updateMe(userUpdate));
    }
}

