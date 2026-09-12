package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarDateImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsRouteImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopTimeImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsTripImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.zachary.transportation_reliability_platform.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Covers all multipart GTFS import contracts without parsing a real archive. */
@WebMvcTest(
        controllers = GtfsImportController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@AutoConfigureMockMvc(addFilters = false)
class GtfsImportControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GtfsRouteImportService routeImporter;
    @MockBean
    private GtfsStopImportService stopImporter;
    @MockBean
    private GtfsTripImportService tripImporter;
    @MockBean
    private GtfsStopTimeImportService stopTimeImporter;
    @MockBean
    private GtfsCalendarImportService calendarImporter;
    @MockBean
    private GtfsCalendarDateImportService calendarDateImporter;

    @Test
    void everyImportEndpointAcceptsMultipartGtfsDataAndDelegatesToItsImporter() throws Exception {
        when(routeImporter.importRoutes(any(), eq("https://nta.example/feed.zip"),
                eq(LocalDate.of(2026, 9, 10)))).thenReturn(1);
        when(stopImporter.importStops(any(), eq(4L))).thenReturn(1);
        when(tripImporter.importTrips(any(), eq(4L))).thenReturn(1);
        when(stopTimeImporter.importStopTimes(any(), eq(4L))).thenReturn(1);

        mockMvc.perform(multipart("/api/v1/gtfs/routes/import")
                        .file(file())
                        .param("sourceUri", "https://nta.example/feed.zip")
                        .param("effectiveFrom", "2026-09-10"))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/gtfs/stops/import")
                        .file(file()).param("feedVersionId", "4"))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/gtfs/trips/import")
                        .file(file()).param("feedVersionId", "4"))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/gtfs/stop-times/import")
                        .file(file()).param("feedVersionId", "4"))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/gtfs/calendars/import")
                        .file(file()).param("feedVersionId", "4"))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/gtfs/calendar-dates/import")
                        .file(file()).param("feedVersionId", "4"))
                .andExpect(status().isCreated());

        verify(routeImporter).importRoutes(any(), eq("https://nta.example/feed.zip"),
                eq(LocalDate.of(2026, 9, 10)));
        verify(stopImporter).importStops(any(), eq(4L));
        verify(tripImporter).importTrips(any(), eq(4L));
        verify(stopTimeImporter).importStopTimes(any(), eq(4L));
        verify(calendarImporter).importCalendars(any(MultipartFile.class), eq(4L));
        verify(calendarDateImporter).importCalendarDates(
                any(MultipartFile.class),
                eq(4L)
        );
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile(
                "file", "feed.zip", "application/zip", new byte[]{1, 2, 3}
        );
    }
}
