package com.zachary.transportation_reliability_platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.response.NtaServiceAlertSyncResponse;
import com.zachary.transportation_reliability_platform.entity.NtaServiceAlert;
import com.zachary.transportation_reliability_platform.service.NtaServiceAlertService;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.websocket.LiveUpdateBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the NTA GTFS-Realtime Alerts feed and makes its current disruptions
 * queryable by the dashboard. A successful empty feed is meaningful: alerts
 * that disappeared from that feed are marked RESOLVED rather than deleted.
 */
@Service
@RequiredArgsConstructor
public class NtaServiceAlertIngestionService {

    private static final String ACTIVE = "ACTIVE";
    private static final String RESOLVED = "RESOLVED";

    private final NtaGtfsRealtimeClient ntaGtfsRealtimeClient;
    private final NtaServiceAlertService ntaServiceAlertService;
    private final ObjectMapper objectMapper;
    private final LiveUpdateBroadcaster liveUpdateBroadcaster;

    @Transactional
    public NtaServiceAlertSyncResponse synchronizeAlerts() {
        String rawFeed = ntaGtfsRealtimeClient.fetchRawAlertsFeed();

        try {
            JsonNode root = objectMapper.readTree(rawFeed);
            Instant feedTimestamp = timestamp(root.path("header").path("timestamp"), Instant.now());
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            List<ParsedAlert> alerts = parseAlerts(root.path("entity"));

            // The source is a snapshot, not an append-only event stream. First
            // resolve every prior active announcement; parsed items below then
            // reactivate the alerts NTA still publishes in this snapshot.
            Set<String> previouslyActiveAlertIds = new LinkedHashSet<>(ntaServiceAlertService.lambdaQuery()
                    .eq(NtaServiceAlert::getStatus, ACTIVE)
                    .list()
                    .stream()
                    .map(NtaServiceAlert::getExternalAlertId)
                    .toList());
            ntaServiceAlertService.lambdaUpdate()
                    .set(NtaServiceAlert::getStatus, RESOLVED)
                    .set(NtaServiceAlert::getUpdatedAt, now)
                    .eq(NtaServiceAlert::getStatus, ACTIVE)
                    .update();

            for (ParsedAlert parsed : alerts) {
                upsert(parsed, now);
                previouslyActiveAlertIds.remove(parsed.externalAlertId());
            }

            liveUpdateBroadcaster.signalChange("service-alerts");

            return new NtaServiceAlertSyncResponse(
                    feedTimestamp,
                    alerts.size(),
                    alerts.size(),
                    previouslyActiveAlertIds.size()
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA Alerts API returned invalid JSON"
            );
        }
    }

    private void upsert(ParsedAlert parsed, OffsetDateTime now) {
        NtaServiceAlert alert = ntaServiceAlertService.lambdaQuery()
                .eq(NtaServiceAlert::getExternalAlertId, parsed.externalAlertId())
                .one();

        if (alert == null) {
            alert = new NtaServiceAlert();
            alert.setExternalAlertId(parsed.externalAlertId());
            alert.setCreatedAt(now);
        }

        alert.setHeaderText(parsed.headerText());
        alert.setDescriptionText(parsed.descriptionText());
        alert.setCause(parsed.cause());
        alert.setEffect(parsed.effect());
        alert.setSeverityLevel(parsed.severityLevel());
        alert.setActiveFrom(parsed.activeFrom());
        alert.setActiveUntil(parsed.activeUntil());
        alert.setExternalRouteIds(String.join(",", parsed.externalRouteIds()));
        alert.setExternalStopIds(String.join(",", parsed.externalStopIds()));
        alert.setStatus(ACTIVE);
        alert.setLastSeenAt(now);
        alert.setUpdatedAt(now);

        if (alert.getId() == null) {
            ntaServiceAlertService.save(alert);
        } else {
            ntaServiceAlertService.updateById(alert);
        }
    }

    private List<ParsedAlert> parseAlerts(JsonNode entities) {
        List<ParsedAlert> alerts = new ArrayList<>();

        for (JsonNode entity : entities) {
            JsonNode alert = entity.path("alert");
            String externalAlertId = text(entity, "id");

            if (alert.isMissingNode() || externalAlertId == null || externalAlertId.isBlank()) {
                continue;
            }

            String header = translatedText(alert.path("header_text"));
            String description = translatedText(alert.path("description_text"));
            if (header == null || header.isBlank()) {
                header = description == null || description.isBlank()
                        ? "NTA service alert " + externalAlertId
                        : description;
            }

            Set<String> routeIds = new LinkedHashSet<>();
            Set<String> stopIds = new LinkedHashSet<>();
            for (JsonNode informedEntity : alert.path("informed_entity")) {
                addIfPresent(routeIds, text(informedEntity, "route_id"));
                addIfPresent(stopIds, text(informedEntity, "stop_id"));
            }

            OffsetDateTime activeFrom = null;
            OffsetDateTime activeUntil = null;
            for (JsonNode period : alert.path("active_period")) {
                OffsetDateTime start = asUtc(timestamp(period.path("start"), null));
                OffsetDateTime end = asUtc(timestamp(period.path("end"), null));
                if (start != null && (activeFrom == null || start.isBefore(activeFrom))) {
                    activeFrom = start;
                }
                if (end != null && (activeUntil == null || end.isAfter(activeUntil))) {
                    activeUntil = end;
                }
            }

            alerts.add(new ParsedAlert(
                    externalAlertId,
                    header,
                    description,
                    text(alert, "cause"),
                    text(alert, "effect"),
                    text(alert, "severity_level"),
                    activeFrom,
                    activeUntil,
                    List.copyOf(routeIds),
                    List.copyOf(stopIds)
            ));
        }

        return alerts;
    }

    private String translatedText(JsonNode node) {
        for (JsonNode translation : node.path("translation")) {
            String value = text(translation, "text");
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Instant timestamp(JsonNode value, Instant fallback) {
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return fallback;
        }
        try {
            long epochSeconds = Long.parseLong(value.asText());
            return epochSeconds > 0 ? Instant.ofEpochSecond(epochSeconds) : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private OffsetDateTime asUtc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private record ParsedAlert(
            String externalAlertId,
            String headerText,
            String descriptionText,
            String cause,
            String effect,
            String severityLevel,
            OffsetDateTime activeFrom,
            OffsetDateTime activeUntil,
            List<String> externalRouteIds,
            List<String> externalStopIds
    ) { }
}
