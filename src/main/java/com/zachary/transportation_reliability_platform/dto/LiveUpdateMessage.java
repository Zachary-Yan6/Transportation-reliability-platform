package com.zachary.transportation_reliability_platform.dto;

import java.time.Instant;

/** A lightweight invalidation signal; clients fetch fresh state over REST. */
public record LiveUpdateMessage(String type, Instant emittedAt) {
}
