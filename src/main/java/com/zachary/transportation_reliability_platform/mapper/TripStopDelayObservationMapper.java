package com.zachary.transportation_reliability_platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachary.transportation_reliability_platform.dto.*;
import com.zachary.transportation_reliability_platform.entity.TripStopDelayObservation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

public interface TripStopDelayObservationMapper
        extends BaseMapper<TripStopDelayObservation> {

    /**
     * Performs idempotent persistence in one database statement. Kafka has
     * at-least-once delivery, so a duplicate event is normal rather than an
     * exceptional consumer failure.
     */
    @Insert("""
            INSERT INTO trip_stop_delay_observations (
                event_id,
                feed_version_id,
                trip_id,
                stop_id,
                stop_sequence,
                delay_seconds,
                observed_at
            ) VALUES (
                #{observation.eventId,
                    typeHandler=com.zachary.transportation_reliability_platform.config.mybatis.PostgreSqlUuidTypeHandler},
                #{observation.feedVersionId},
                #{observation.tripId},
                #{observation.stopId},
                #{observation.stopSequence},
                #{observation.delaySeconds},
                #{observation.observedAt}
            )
            ON CONFLICT (event_id) DO NOTHING
            """)
    int insertIfAbsent(
            @Param("observation") TripStopDelayObservation observation
    );

    /**
     * Returns the most recent delay observations for one internal trip ID.
     */
    @Select("""
            SELECT
                observation.event_id,
                observation.trip_id,
                trip.external_trip_id,
                observation.stop_id,
                stop.external_stop_id,
                stop.stop_name,
                observation.stop_sequence,
                observation.delay_seconds,
                observation.observed_at,
                observation.received_at
            FROM trip_stop_delay_observations observation
            INNER JOIN trips trip
                ON trip.id = observation.trip_id
            INNER JOIN stops stop
                ON stop.id = observation.stop_id
            WHERE observation.trip_id = #{tripId}
            ORDER BY observation.observed_at DESC
            LIMIT #{limit}
            """)
    List<TripDelayObservationResponse> findRecentByTripId(
            @Param("tripId") Long tripId,
            @Param("limit") int limit
    );

    /**
     * Calculates aggregate reliability statistics for one Trip.
     *
     * An observation is considered on time when its delay is:
     * - no more than 60 seconds early
     * - no more than 300 seconds late
     */
    @Select("""
        SELECT
            COUNT(*) AS observation_count,

            COALESCE(
                ROUND(AVG(observation.delay_seconds), 2),
                0
            ) AS average_delay_seconds,

            COALESCE(
                MAX(observation.delay_seconds),
                0
            ) AS maximum_delay_seconds,
            COALESCE(
                ROUND(
                    (
                        PERCENTILE_CONT(0.90)
                        WITHIN GROUP (
                            ORDER BY observation.delay_seconds
                        )
                    )::numeric,
                    2
                ),
                0
            ) AS p90_delay_seconds,
            COALESCE(
                ROUND(
                    100.0
                    * COUNT(*) FILTER (
                        WHERE observation.delay_seconds
                        BETWEEN -60 AND 300
                    )
                    / NULLIF(COUNT(*), 0),
                    2
                ),
                0
            ) AS on_time_percentage

        FROM trip_stop_delay_observations observation
        WHERE observation.trip_id = #{tripId}
        """)
    TripReliabilityStatisticsRow calculateReliability(
            @Param("tripId") Long tripId
    );

    /**
     * Calculates reliability using observations from every trip on one route.
     */
    @Select("""
    SELECT
        COUNT(*) AS observation_count,

        COALESCE(
            ROUND(AVG(observation.delay_seconds), 2),
            0
        ) AS average_delay_seconds,

        COALESCE(
            MAX(observation.delay_seconds),
            0
        ) AS maximum_delay_seconds,
        COALESCE(
            ROUND(
                (
                    PERCENTILE_CONT(0.90)
                    WITHIN GROUP (
                        ORDER BY observation.delay_seconds
                    )
                )::numeric,
                2
            ),
            0
        ) AS p90_delay_seconds,
        COALESCE(
            ROUND(
                100.0
                * COUNT(*) FILTER (
                    WHERE observation.delay_seconds
                    BETWEEN -60 AND 300
                )
                / NULLIF(COUNT(*), 0),
                2
            ),
            0
        ) AS on_time_percentage

    FROM trip_stop_delay_observations observation
    INNER JOIN trips trip
        ON trip.id = observation.trip_id
    WHERE trip.route_id = #{routeId}
    """)
    RouteReliabilityStatisticsRow calculateReliabilityForRoute(
            @Param("routeId") Long routeId
    );

    /**
     * Returns routes with the lowest on-time percentage during a time window.
     */
    @Select("""
    SELECT
        route.id AS route_id,
        route.external_route_id,
        route.short_name AS route_short_name,
        route.long_name AS route_long_name,
        COUNT(observation.id) AS observation_count,
        ROUND(AVG(observation.delay_seconds), 2) AS average_delay_seconds,
        ROUND(
            100.0
            * COUNT(*) FILTER (
                WHERE observation.delay_seconds BETWEEN -60 AND 300
            )
            / NULLIF(COUNT(*), 0),
            2
        ) AS reliability_score

    FROM routes route
    INNER JOIN trips trip
        ON trip.route_id = route.id
    INNER JOIN trip_stop_delay_observations observation
        ON observation.trip_id = trip.id

    WHERE route.feed_version_id = #{feedVersionId}
      AND observation.observed_at >= NOW()
          - (#{hours} * INTERVAL '1 hour')

    GROUP BY
        route.id,
        route.external_route_id,
        route.short_name,
        route.long_name

    ORDER BY
        reliability_score ASC,
        average_delay_seconds DESC,
        observation_count DESC

    LIMIT #{limit}
    """)
    List<RouteReliabilityRankingResponse> findLeastReliableRoutes(
            @Param("feedVersionId") Long feedVersionId,
            @Param("hours") int hours,
            @Param("limit") int limit
    );

    /**
     * Calculates dashboard values from recent delay observations.
     */
    @Select("""
    SELECT
        COUNT(observation.id) AS observation_count,
        COUNT(DISTINCT observation.trip_id) AS monitored_trip_count,
        COUNT(DISTINCT trip.route_id) AS monitored_route_count,

        COALESCE(
            ROUND(AVG(observation.delay_seconds), 2),
            0
        ) AS average_delay_seconds,

        COALESCE(
            ROUND(
                100.0
                * COUNT(*) FILTER (
                    WHERE observation.delay_seconds BETWEEN -60 AND 300
                )
                / NULLIF(COUNT(*), 0),
                2
            ),
            0
        ) AS on_time_percentage,

        MAX(observation.observed_at) AS latest_observed_at

    FROM trip_stop_delay_observations observation
    INNER JOIN trips trip
        ON trip.id = observation.trip_id

    WHERE observation.feed_version_id = #{feedVersionId}
      AND observation.observed_at >= NOW()
          - (#{hours} * INTERVAL '1 hour')
    """)
    DashboardStatisticsRow calculateDashboardSummary(
            @Param("feedVersionId") Long feedVersionId,
            @Param("hours") int hours
    );

    /**
     * Returns the stops on one route with the highest average delays.
     */
    @Select("""
    SELECT
        stop.id AS stop_id,
        stop.external_stop_id,
        stop.stop_name,
        stop.latitude,
        stop.longitude,

        COUNT(observation.id) AS observation_count,

        ROUND(
            AVG(observation.delay_seconds),
            2
        ) AS average_delay_seconds,

        MAX(observation.delay_seconds) AS maximum_delay_seconds,
        COALESCE(
            ROUND(
                (
                    PERCENTILE_CONT(0.90)
                    WITHIN GROUP (
                        ORDER BY observation.delay_seconds
                    )
                )::numeric,
                2
            ),
            0
        ) AS p90_delay_seconds,
        ROUND(
            100.0
            * COUNT(*) FILTER (
                WHERE observation.delay_seconds BETWEEN -60 AND 300
            )
            / NULLIF(COUNT(*), 0),
            2
        ) AS on_time_percentage

    FROM trip_stop_delay_observations observation
    INNER JOIN trips trip
        ON trip.id = observation.trip_id
    INNER JOIN stops stop
        ON stop.id = observation.stop_id

    WHERE trip.route_id = #{routeId}
      AND observation.observed_at >= NOW()
          - (#{hours} * INTERVAL '1 hour')

    GROUP BY
        stop.id,
        stop.external_stop_id,
        stop.stop_name,
        stop.latitude,
        stop.longitude

    ORDER BY
        average_delay_seconds DESC,
        maximum_delay_seconds DESC

    LIMIT #{limit}
    """)
    List<RouteStopDelayResponse> findMostDelayedStops(
            @Param("routeId") Long routeId,
            @Param("hours") int hours,
            @Param("limit") int limit
    );

    /**
     * Returns the newest delay observations recorded at one stop.
     */
    @Select("""
        SELECT
            observation.event_id,
            observation.trip_id,
            trip.external_trip_id,

            route.id AS route_id,
            route.external_route_id,
            route.short_name AS route_short_name,
            route.long_name AS route_long_name,

            observation.stop_sequence,
            observation.delay_seconds,
            observation.observed_at,
            observation.received_at

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id
        INNER JOIN routes route
            ON route.id = trip.route_id

        WHERE observation.stop_id = #{stopId}

        ORDER BY observation.observed_at DESC
        LIMIT #{limit}
        """)
    List<StopDelayObservationResponse> findRecentByStopId(
            @Param("stopId") Long stopId,
            @Param("limit") int limit
    );

    /**
     * Groups one route's delay observations into hourly buckets.
     */
    @Select("""
        SELECT
            date_trunc('hour', observation.observed_at) AS bucket_start,

            COUNT(observation.id) AS observation_count,

            ROUND(
                AVG(observation.delay_seconds),
                2
            ) AS average_delay_seconds,

            ROUND(
                100.0
                * COUNT(*) FILTER (
                    WHERE observation.delay_seconds BETWEEN -60 AND 300
                )
                / NULLIF(COUNT(*), 0),
                2
            ) AS on_time_percentage

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id

        WHERE trip.route_id = #{routeId}
          AND observation.observed_at >= NOW()
              - (#{hours} * INTERVAL '1 hour')

        GROUP BY date_trunc('hour', observation.observed_at)

        ORDER BY bucket_start ASC
        """)
    List<RouteDelayTrendResponse> findDelayTrendByRouteId(
            @Param("routeId") Long routeId,
            @Param("hours") int hours
    );

    /**
     * Calculates route reliability using only observations in a selected time window.
     */
    @Select("""
        SELECT
            COUNT(*) AS observation_count,

            COALESCE(
                ROUND(AVG(observation.delay_seconds), 2),
                0
            ) AS average_delay_seconds,

            COALESCE(
                MAX(observation.delay_seconds),
                0
            ) AS maximum_delay_seconds,
            COALESCE(
                ROUND(
                    (
                        PERCENTILE_CONT(0.90)
                        WITHIN GROUP (
                            ORDER BY observation.delay_seconds
                        )
                    )::numeric,
                    2
                ),
                0
            ) AS p90_delay_seconds,
            COALESCE(
                ROUND(
                    100.0
                    * COUNT(*) FILTER (
                        WHERE observation.delay_seconds BETWEEN -60 AND 300
                    )
                    / NULLIF(COUNT(*), 0),
                    2
                ),
                0
            ) AS on_time_percentage

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id

        WHERE trip.route_id = #{routeId}
          AND observation.observed_at >= NOW()
              - (#{hours} * INTERVAL '1 hour')
        """)
    RouteReliabilityStatisticsRow calculateReliabilityForRouteInWindow(
            @Param("routeId") Long routeId,
            @Param("hours") int hours
    );

    /**
     * Measures how much usable delay history exists for one route.
     */
    @Select("""
        SELECT
            route.id AS route_id,
            route.short_name AS route_short_name,

            COUNT(observation.id) AS observation_count,
            COUNT(DISTINCT observation.trip_id) AS unique_trip_count,
            COUNT(DISTINCT observation.stop_id) AS unique_stop_count,
            COUNT(
                DISTINCT date_trunc('hour', observation.observed_at)
            ) AS active_hour_count,
            MIN(observation.observed_at) AS earliest_observed_at,
            MAX(observation.observed_at) AS latest_observed_at,

            COALESCE(
                ROUND(
                    EXTRACT(
                        EPOCH FROM (
                            MAX(observation.observed_at)
                            - MIN(observation.observed_at)
                        )
                    ) / 3600.0,
                    2
                ),
                0
            ) AS coverage_hours

        FROM routes route
        LEFT JOIN trips trip
            ON trip.route_id = route.id
        LEFT JOIN trip_stop_delay_observations observation
            ON observation.trip_id = trip.id

        WHERE route.id = #{routeId}

        GROUP BY route.id, route.short_name
        """)
    RouteTrainingDataStatusRow getTrainingDataStatus(
            @Param("routeId") Long routeId
    );

    /**
     * Returns readiness statistics for every route in the currently active
     * static GTFS feed. Historical feed versions are intentionally excluded:
     * their internal route IDs cannot receive new realtime observations.
     */
    @Select("""
        SELECT
            route.id AS route_id,
            route.short_name AS route_short_name,

            COUNT(observation.id) AS observation_count,
            COUNT(DISTINCT observation.trip_id) AS unique_trip_count,
            COUNT(DISTINCT observation.stop_id) AS unique_stop_count,
            COUNT(
                DISTINCT date_trunc('hour', observation.observed_at)
            ) AS active_hour_count,
            MIN(observation.observed_at) AS earliest_observed_at,
            MAX(observation.observed_at) AS latest_observed_at,

            COALESCE(
                ROUND(
                    EXTRACT(
                        EPOCH FROM (
                            MAX(observation.observed_at)
                            - MIN(observation.observed_at)
                        )
                    ) / 3600.0,
                    2
                ),
                0
            ) AS coverage_hours

        FROM routes route
        INNER JOIN feed_versions feed_version
            ON feed_version.id = route.feed_version_id
            AND feed_version.lifecycle_status = 'ACTIVE'
        LEFT JOIN trips trip
            ON trip.route_id = route.id
        LEFT JOIN trip_stop_delay_observations observation
            ON observation.trip_id = trip.id

        GROUP BY route.id, route.short_name
        ORDER BY COUNT(observation.id) DESC, route.id ASC
        """)
    List<RouteTrainingDataStatusRow> findActiveFeedTrainingDataStatuses();

    /**
     * Returns chronological, labelled samples for a route's delay-prediction
     * dataset. Local Dublin time is used for time-of-day model features.
     */
    @Select("""
        SELECT
            observation.event_id,
            trip.route_id,
            observation.trip_id,
            trip.external_trip_id,
            observation.stop_id,
            stop.external_stop_id,
            observation.stop_sequence,

            stop_time.arrival_seconds AS scheduled_arrival_seconds,
            stop_time.departure_seconds AS scheduled_departure_seconds,

            EXTRACT(
                ISODOW FROM observation.observed_at
                AT TIME ZONE 'Europe/Dublin'
            )::INTEGER AS observed_day_of_week,

            EXTRACT(
                HOUR FROM observation.observed_at
                AT TIME ZONE 'Europe/Dublin'
            )::INTEGER AS observed_hour,

            observation.observed_at,
            observation.delay_seconds AS actual_delay_seconds

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id
        INNER JOIN stops stop
            ON stop.id = observation.stop_id
        INNER JOIN stop_times stop_time
            ON stop_time.trip_id = observation.trip_id
            AND stop_time.stop_id = observation.stop_id
            AND stop_time.stop_sequence = observation.stop_sequence

        WHERE trip.route_id = #{routeId}

        ORDER BY observation.observed_at ASC
        LIMIT #{limit}
        """)
    List<RouteDelayTrainingSampleResponse> findTrainingSamplesByRouteId(
            @Param("routeId") Long routeId,
            @Param("limit") int limit
    );

    /**
     * Returns one stable page from a route's chronological training dataset.
     * Batch training uses pages so a route with more than 10,000 observations
     * is not silently trained from a truncated sample.
     */
    @Select("""
        SELECT
            observation.event_id,
            trip.route_id,
            observation.trip_id,
            trip.external_trip_id,
            observation.stop_id,
            stop.external_stop_id,
            observation.stop_sequence,

            stop_time.arrival_seconds AS scheduled_arrival_seconds,
            stop_time.departure_seconds AS scheduled_departure_seconds,

            EXTRACT(
                ISODOW FROM observation.observed_at
                AT TIME ZONE 'Europe/Dublin'
            )::INTEGER AS observed_day_of_week,

            EXTRACT(
                HOUR FROM observation.observed_at
                AT TIME ZONE 'Europe/Dublin'
            )::INTEGER AS observed_hour,

            observation.observed_at,
            observation.delay_seconds AS actual_delay_seconds

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id
        INNER JOIN stops stop
            ON stop.id = observation.stop_id
        INNER JOIN stop_times stop_time
            ON stop_time.trip_id = observation.trip_id
            AND stop_time.stop_id = observation.stop_id
            AND stop_time.stop_sequence = observation.stop_sequence

        WHERE trip.route_id = #{routeId}

        ORDER BY observation.observed_at ASC, observation.id ASC
        LIMIT #{limit}
        OFFSET #{offset}
        """)
    List<RouteDelayTrainingSampleResponse> findTrainingSamplesPageByRouteId(
            @Param("routeId") Long routeId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * Calculates a stop baseline for the same local weekday and hour as a
     * requested prediction time.
     */
    @Select("""
        SELECT
            COUNT(observation.id) AS sample_count,
            COALESCE(
                ROUND(AVG(observation.delay_seconds), 2),
                0
            ) AS average_delay_seconds,
            COALESCE(
                ROUND(
                    (
                        PERCENTILE_CONT(0.90)
                        WITHIN GROUP (
                            ORDER BY observation.delay_seconds
                        )
                    )::numeric,
                    2
                ),
                0
            ) AS p90_delay_seconds

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id

        WHERE trip.route_id = #{routeId}
          AND observation.stop_id = #{stopId}
          AND observation.observed_at < #{targetTime}
          AND EXTRACT(
                ISODOW FROM observation.observed_at
                AT TIME ZONE 'Europe/Dublin'
              )::INTEGER = #{dayOfWeek}
          AND EXTRACT(
                HOUR FROM observation.observed_at
                AT TIME ZONE 'Europe/Dublin'
              )::INTEGER = #{hour}
        """)
    DelayBaselineStatisticsRow findTimeMatchedStopBaseline(
            @Param("routeId") Long routeId,
            @Param("stopId") Long stopId,
            @Param("dayOfWeek") int dayOfWeek,
            @Param("hour") int hour,
            @Param("targetTime") OffsetDateTime targetTime
    );

    /**
     * Calculates a fallback baseline from all history at one route stop.
     */
    @Select("""
        SELECT
            COUNT(observation.id) AS sample_count,
            COALESCE(
                ROUND(AVG(observation.delay_seconds), 2),
                0
            ) AS average_delay_seconds,
            COALESCE(
                ROUND(
                    (
                        PERCENTILE_CONT(0.90)
                        WITHIN GROUP (
                            ORDER BY observation.delay_seconds
                        )
                    )::numeric,
                    2
                ),
                0
            ) AS p90_delay_seconds

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id

        WHERE trip.route_id = #{routeId}
          AND observation.stop_id = #{stopId}
          AND observation.observed_at < #{targetTime}
        """)
    DelayBaselineStatisticsRow findStopHistoryBaseline(
            @Param("routeId") Long routeId,
            @Param("stopId") Long stopId,
            @Param("targetTime") OffsetDateTime targetTime
    );

    /**
     * Calculates a route-wide fallback baseline using only observations that
     * existed before the prediction target time. This prevents future data
     * from leaking into a historical prediction or evaluation.
     */
    @Select("""
        SELECT
            COUNT(observation.id) AS sample_count,
            COALESCE(
                ROUND(AVG(observation.delay_seconds), 2),
                0
            ) AS average_delay_seconds,
            COALESCE(
                ROUND(
                    (
                        PERCENTILE_CONT(0.90)
                        WITHIN GROUP (
                            ORDER BY observation.delay_seconds
                        )
                    )::numeric,
                    2
                ),
                0
            ) AS p90_delay_seconds

        FROM trip_stop_delay_observations observation
        INNER JOIN trips trip
            ON trip.id = observation.trip_id

        WHERE trip.route_id = #{routeId}
          AND observation.observed_at < #{targetTime}
        """)
    DelayBaselineStatisticsRow findRouteHistoryBaseline(
            @Param("routeId") Long routeId,
            @Param("targetTime") OffsetDateTime targetTime
    );
}
