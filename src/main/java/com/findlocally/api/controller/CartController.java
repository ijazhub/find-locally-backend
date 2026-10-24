package com.findlocally.api.controller;

import com.findlocally.api.contract.CartApi;
import com.findlocally.api.service.CartService;
import com.findlocally.api.model.Booking;
import com.findlocally.api.model.Cart;
import com.findlocally.api.model.CartItemInput;
import java.util.UUID;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CartController implements CartApi {
    private final CartService service;

    @Override
    public ResponseEntity<Cart> updateCartItem(UUID itemId, CartItemInput cartItemInput) {
        return ResponseEntity.ok(service.updateCartItem(itemId, cartItemInput));
    }

    public CartController(CartService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<Cart> addCartItem(CartItemInput cartItemInput) {
        return ResponseEntity.status(201).body(service.addCartItem(cartItemInput));
    }

    @Override
    public ResponseEntity<List<Booking>> checkoutCart(UUID idempotencyKey) {
        return ResponseEntity.status(201).body(service.checkoutCart(idempotencyKey));
    }

    @Override
    public ResponseEntity<Void> clearCart() {
        service.clearCart();
        return ResponseEntity.status(204).build();
    }

    @Override
    public ResponseEntity<Cart> getCart() {
        return ResponseEntity.status(200).body(service.getCart());
    }

    @Override
    public ResponseEntity<Void> removeCartItem(UUID itemId) {
        service.removeCartItem(itemId);
        return ResponseEntity.status(204).build();
    }
}

