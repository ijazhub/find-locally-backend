package com.findlocally.api.service;

import com.findlocally.api.repository.AccountRepository;
import com.findlocally.api.model.*;
import com.findlocally.api.utility.CurrentUser;
import com.findlocally.api.utility.PublicMediaUrls;
import jakarta.validation.Validator;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final AccountRepository repository;
    private final CurrentUser currentUser;
    private final Validator validator;
    private final PublicMediaUrls media;
    private final MediaService uploads;

    public AccountService(AccountRepository repository, CurrentUser currentUser, Validator validator, PublicMediaUrls media, MediaService uploads) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.validator = validator;
        this.media = media;
        this.uploads = uploads;
    }

    @Transactional(readOnly = true)
    public UserProfile getMe() { return requireProfile(currentUser.id()); }

    @Transactional
    public UserProfile updateMe(UserUpdate input) {
        UUID user = currentUser.id();
        lockUser(user);
        if (input == null || !validator.validate(input).isEmpty()) throw badRequest("Invalid profile");
        length(input.getName(), 120, false, "name");
        length(input.getEmail(), 254, false, "email");
        if (input.getEmail() != null) input.setEmail(AuthService.email(input.getEmail()));
        String avatar = null;
        if (input.getAvatarUploadId() != null) {
            String key = repository.avatarKey(user, input.getAvatarUploadId());
            if (key == null) {
                uploads.confirm(user, input.getAvatarUploadId(), java.util.Set.of("AVATAR"));
                key = repository.avatarKey(user, input.getAvatarUploadId());
            }
            if (key == null) throw badRequest("Choose a completed avatar upload belonging to you");
            avatar = media.url(key);
        }
        repository.updateProfile(user, input, avatar);
        return requireProfile(user);
    }

    @Transactional(readOnly = true)
    public List<Address> listAddresses() {
        UUID user = currentUser.id();
        requireProfile(user);
        return repository.addresses(user);
    }

    @Transactional
    public Address createAddress(AddressInput input) {
        UUID user = currentUser.id();
        validateAddress(input);
        lockUser(user);
        if (Boolean.TRUE.equals(input.getIsDefault())) repository.clearDefault(user);
        return repository.createAddress(user, input);
    }

    @Transactional
    public Address updateAddress(UUID addressId, AddressInput input) {
        UUID user = currentUser.id();
        validateAddress(input);
        lockUser(user);
        // Check ownership before clearing the caller's existing default.
        if (addressId == null || repository.address(user, addressId) == null) throw notFound();
        if (Boolean.TRUE.equals(input.getIsDefault())) repository.clearDefault(user);
        return repository.updateAddress(user, addressId, input);
    }

    @Transactional
    public void deleteAddress(UUID addressId) {
        UUID user = currentUser.id();
        lockUser(user);
        if (addressId == null || repository.deleteAddress(user, addressId) == 0) throw notFound();
    }

    private void validateAddress(AddressInput input) {
        if (input == null || !validator.validate(input).isEmpty()) throw badRequest("Invalid address");
        length(input.getLine1(), 200, true, "line1");
        length(input.getCity(), 100, true, "city");
        length(input.getPincode(), 10, true, "pincode");
        length(input.getLabel(), 40, false, "label");
        length(input.getLine2(), 200, false, "line2");
        length(input.getNeighbourhood(), 100, false, "neighbourhood");
        length(input.getState(), 100, false, "state");
        var point = input.getLocation();
        if (point != null && (point.getLat() == null || point.getLng() == null
                || !Double.isFinite(point.getLat()) || !Double.isFinite(point.getLng())
                || Math.abs(point.getLat()) > 90 || Math.abs(point.getLng()) > 180)) throw badRequest("Invalid coordinates");
    }

    private void length(String value, int max, boolean required, String name) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > max)) {
            throw badRequest("Invalid " + name);
        }
    }

    private UserProfile requireProfile(UUID user) {
        UserProfile profile = repository.profile(user);
        if (profile == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found");
        return profile;
    }

    private void lockUser(UUID user) {
        if (!repository.lockUser(user)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found");
    }

    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"); }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

