package com.zachary.transportation_reliability_platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachary.transportation_reliability_platform.dto.RealtimeStopTimeMatchRow;
import com.zachary.transportation_reliability_platform.dto.StopTimeScheduleRow;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface StopTimeMapper extends BaseMapper<StopTime> {

    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM stop_times stop_time
                INNER JOIN trips trip ON trip.id = stop_time.trip_id
                WHERE trip.feed_version_id = #{feedVersionId}
            )
            """)
    boolean existsForFeedVersionId(@Param("feedVersionId") Long feedVersionId);

    @Select("""
            SELECT
                stop_time.stop_sequence AS stopSequence,
                stop.external_stop_id AS externalStopId,
                stop.stop_name AS stopName,
                stop_time.arrival_seconds AS arrivalSeconds,
                stop_time.departure_seconds AS departureSeconds
            FROM stop_times stop_time
            INNER JOIN stops stop ON stop.id = stop_time.stop_id
            WHERE stop_time.trip_id = #{tripId}
            ORDER BY stop_time.stop_sequence
            """)
    List<StopTimeScheduleRow> findScheduleByTripId(@Param("tripId") Long tripId);

    /**
     * Resolves many live trip/stop identifiers with one joined query.
     *
     * <p>This replaces one Trip query, one Stop query, and one StopTime query
     * for every incoming real-time stop update.</p>
     */
    @Select("""
            <script>
            SELECT
                trip.id AS tripId,
                stop.id AS stopId,
                trip.external_trip_id AS externalTripId,
                stop.external_stop_id AS externalStopId,
                stop_time.stop_sequence AS stopSequence
            FROM trips trip
            INNER JOIN stop_times stop_time ON stop_time.trip_id = trip.id
            INNER JOIN stops stop ON stop.id = stop_time.stop_id
            WHERE trip.feed_version_id = #{feedVersionId}
              AND trip.external_trip_id IN
              <foreach collection="externalTripIds" item="externalTripId"
                       open="(" separator="," close=")">
                  #{externalTripId}
              </foreach>
              AND stop.external_stop_id IN
              <foreach collection="externalStopIds" item="externalStopId"
                       open="(" separator="," close=")">
                  #{externalStopId}
              </foreach>
            </script>
            """)
    List<RealtimeStopTimeMatchRow> findRealtimeMatches(
            @Param("feedVersionId") Long feedVersionId,
            @Param("externalTripIds") List<String> externalTripIds,
            @Param("externalStopIds") List<String> externalStopIds
    );
}
