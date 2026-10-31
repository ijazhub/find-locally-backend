package com.findlocally.api.service;

import com.findlocally.api.model.*;
import com.findlocally.api.repository.*;
import com.findlocally.api.utility.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@org.springframework.stereotype.Service
public class CartService {
    private final CartRepository repository;
    private final BookingsRepository bookings;
    private final BookingsService bookingService;
    private final CurrentUser current;
    private final Inputs inputs;
    private final AuthTokens tokens;
    public CartService(CartRepository repository, BookingsRepository bookings, BookingsService bookingService,
            CurrentUser current, Inputs inputs, AuthTokens tokens) {
        this.repository = repository; this.bookings = bookings; this.bookingService = bookingService;
        this.current = current; this.inputs = inputs; this.tokens = tokens;
    }
    @Transactional public Cart addCartItem(CartItemInput input) {
        UUID user = lock(); inputs.valid(input);
        var existing = repository.items(user);
        if (existing.size() >= 50) throw Inputs.bad("A cart can contain at most 50 items");
        validateOffer(input, existing);
        repository.add(user, input);
        return cart(user);
    }
    @Transactional public Cart updateCartItem(UUID id, CartItemInput input) {
        UUID user = lock();
        var existing = repository.items(user);
        if (existing.stream().noneMatch(item -> item.getId().equals(id))) throw Inputs.missing("Cart item");
        inputs.valid(input);
        validateOffer(input, existing.stream().filter(item -> !item.getId().equals(id)).toList());
        if (repository.update(user, id, input) == 0) throw Inputs.missing("Cart item");
        return cart(user);
    }
    private void validateOffer(CartItemInput input, List<CartItemsInner> otherItems) {
        var offer = bookings.findOffer(input.getProviderId(), input.getServiceId());
        if (offer == null) throw Inputs.missing("Active provider service");
        if (input.getFulfilment() != null && !offer.fulfilment().contains(input.getFulfilment())) throw Inputs.bad("Unsupported fulfilment");
        if (otherItems.stream().anyMatch(item -> !item.getService().getPrice().getCurrency().equals(offer.currency())))
            throw Inputs.bad("Cart items must use the same currency");
    }
    @Transactional(readOnly = true) public Cart getCart() { return cart(current.id()); }
    @Transactional public void clearCart() { repository.clear(lock()); }
    @Transactional public void removeCartItem(UUID id) {
        if (repository.remove(lock(), id) == 0) throw Inputs.missing("Cart item");
    }
    @Transactional public List<Booking> checkoutCart(UUID key) {
        if (key == null) throw Inputs.bad("Idempotency-Key is required");
        UUID user = lock();
        if (!bookings.isCustomerAccount(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A CUSTOMER account is required for checkout");
        String path = "/api/v1/cart/checkout", hash = tokens.hash("cart-checkout:v1");
        var record = bookings.claimKey(user, key, path, hash);
        if (!path.equals(record.path()) || !hash.equals(record.requestHash())) throw Inputs.conflict("Idempotency key was used for a different request");
        if (record.responseBody() != null) return repository.replay(record.responseBody());
        var items = repository.items(user);
        if (items.isEmpty()) throw Inputs.bad("The cart is empty");
        if (items.stream().map(item -> item.getService().getPrice().getCurrency()).distinct().count() > 1)
            throw Inputs.conflict("Cart items must use the same currency");
        var result = new ArrayList<Booking>();
        for (var item : items) {
            if (item.getStartsAt() == null || item.getFulfilment() == null) throw Inputs.bad("Each cart item needs a time and fulfilment before checkout");
            UUID address = null;
            if (item.getFulfilment() == Fulfilment.AT_CLIENT || item.getFulfilment() == Fulfilment.DELIVERY) {
                address = repository.defaultAddress(user);
                if (address == null) throw Inputs.bad("Set a default address before checking out delivery or at-client services");
            }
            UUID itemKey = UUID.nameUUIDFromBytes(("cart:" + key + ":" + item.getId()).getBytes(StandardCharsets.UTF_8));
            result.add(bookingService.createBooking(itemKey, new BookingCreate().providerId(item.getProvider().getId())
                    .serviceId(item.getService().getId()).startsAt(item.getStartsAt()).fulfilment(item.getFulfilment()).addressId(address)));
        }
        repository.clear(user);
        repository.completeKey(user, key, result);
        return result;
    }
    private Cart cart(UUID user) {
        var items = repository.items(user);
        var result = new Cart().items(items);
        if (items.isEmpty()) return result.total(new Money(0L, "INR"));
        if (items.stream().map(item -> item.getService().getPrice().getCurrency()).distinct().count() == 1) {
            long total = 0;
            try { for (var item : items) total = Math.addExact(total, item.getService().getPrice().getAmount()); }
            catch (ArithmeticException ex) { throw Inputs.conflict("Cart total is too large"); }
            result.setTotal(new Money(total, items.getFirst().getService().getPrice().getCurrency()));
        }
        return result;
    }
    private UUID lock() {
        UUID user = current.id();
        if (!repository.lock(user)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found");
        return user;
    }
}

