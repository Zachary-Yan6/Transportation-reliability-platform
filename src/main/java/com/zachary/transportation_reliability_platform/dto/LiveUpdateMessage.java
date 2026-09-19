package com.zachary.transportation_reliability_platform.dto;

import java.time.Instant;

/**
 * Public WebSocket invalidation schema for {@code /ws/live}.
 *
 * <p>This endpoint is deliberately unauthenticated, so this record is limited
 * to a non-sensitive resource type and an emission timestamp. It must never
 * carry coordinates, delays, driver/account details, tokens, or other business
 * data. Clients receive this signal and fetch protected data through REST.
 * Adding a field is a security-design change and requires an authenticated
 * WebSocket design review.</p>
 */
public record LiveUpdateMessage(String type, Instant emittedAt) {
}
