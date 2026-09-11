package com.zachary.transportation_reliability_platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PublishTripUpdateRequest(
        @NotNull Long feedVersionId,
        @NotBlank String externalTripId,
        @NotBlank String externalStopId,
        @NotNull @Positive Integer stopSequence,
        @NotNull Integer delaySeconds
) {
}