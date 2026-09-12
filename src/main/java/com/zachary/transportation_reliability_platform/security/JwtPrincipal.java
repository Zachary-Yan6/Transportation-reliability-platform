package com.zachary.transportation_reliability_platform.security;

/** Minimal identity reconstructed from a valid signed JWT. */
public record JwtPrincipal(String email, String role) {
}
