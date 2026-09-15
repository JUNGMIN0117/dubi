package com.ssafy.ddubi.domain.route.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.entity.RouteProvider;
import com.ssafy.ddubi.domain.route.entity.RouteRequest;
import com.ssafy.ddubi.domain.route.entity.SavedRoute;
import com.ssafy.ddubi.domain.route.entity.SavedRouteSegment;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import com.ssafy.ddubi.domain.route.repository.RouteRequestRepository;
import com.ssafy.ddubi.domain.route.repository.SavedRouteRepository;
import com.ssafy.ddubi.domain.schedule.dto.request.LocationRequest;
import com.ssafy.ddubi.domain.schedule.dto.request.ScheduleCreateRequest;
import com.ssafy.ddubi.domain.schedule.entity.Schedule;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.security.JwtTokenProvider;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 경로 목록·상세·지도 조회를 HTTP 로 확인한다.
 *
 * <p>경로는 저장소에 직접 넣는다. 평가 흐름을 태우면 좌표가 카카오 응답에서 오는 값이라
 * <b>무엇이 돌아와야 하는지 테스트가 알 수 없다.</b> 여기서 검증하려는 것은 계산이 아니라
 * "저장된 것이 그대로 나오는가" 다.
 *
 * <p>소유권을 특히 본다. {@code routeId} 는 순번이라 남의 것을 찍어 볼 수 있고, 경로에는
 * 사용자가 직접 달려 있지 않아 일정을 거쳐야 한다 — 빠뜨리기 쉬운 자리다.
 */
@AutoConfigureMockMvc
class RouteQueryApiTest extends IntegrationTestSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 구간 하나의 폴리라인. 저장한 것이 그대로 돌아오는지 보려고 값을 고정한다. */
    private static final String GEOMETRY = """
            [{"longitude":127.0276,"latitude":37.4979},
             {"longitude":127.0310,"latitude":37.4990},
             {"longitude":127.0364,"latitude":37.5006}]
            """;

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository schedules;
    @Autowired RouteRequestRepository routeRequests;
    @Autowired SavedRouteRepository savedRoutes;
    @Autowired JwtTokenProvider tokens;

    private long owner;
    private String bearer;
    private long scheduleId;
    private long selectedRouteId;

    @BeforeEach
    void fixture() {
        owner = users.save(User.builder().email("route-api@example.test").nickname("경로 조회").build()).getId();
        bearer = "Bearer " + tokens.createAccessToken(owner);
        scheduleId = newSchedule("경로 조회");

        Schedule schedule = schedules.findById(scheduleId).orElseThrow();
        RouteRequest request = routeRequests.save(RouteRequest.builder().schedule(schedule)
                .provider(RouteProvider.KAKAO).rawResponse(null).requestedAt(LocalDateTime.now(KST)).build());

        // 1순위: 도보 → 버스 두 구간. 버스 구간에만 좌표가 있다.
        SavedRoute selected = SavedRoute.builder().schedule(schedule).routeRequest(request)
                .providerPathId("path-1").baseDurationMin(25).selected(true).rankNo(1).build();
        selected.addSegment(SavedRouteSegment.builder().seq(1).mode(TransportMode.WALK)
                .fromName("집").toName("강남역").baseDurationMin(5).geometry(null).build());
        selected.addSegment(SavedRouteSegment.builder().seq(2).mode(TransportMode.BUS)
                .providerRouteKey("146").fromName("강남역").toName("역삼역").baseDurationMin(20)
                .geometry(GEOMETRY).build());
        selectedRouteId = savedRoutes.save(selected).getId();

        SavedRoute alternative = SavedRoute.builder().schedule(schedule).routeRequest(request)
                .providerPathId("path-2").baseDurationMin(31).selected(false).rankNo(2).build();
        alternative.addSegment(SavedRouteSegment.builder().seq(1).mode(TransportMode.SUBWAY)
                .providerRouteKey("2호선").fromName("강남역").toName("역삼역").baseDurationMin(31)
                .geometry(null).build());
        savedRoutes.save(alternative);
    }

    private long newSchedule(String title) {
        return scheduleService.create(owner, new ScheduleCreateRequest(title,
                new LocationRequest("강남", new BigDecimal("37.4979"), new BigDecimal("127.0276")),
                new LocationRequest("역삼", new BigDecimal("37.5006"), new BigDecimal("127.0364")),
                LocalDateTime.now(KST).plusHours(3), null, null, null)).id();
    }

    @Test
    @DisplayName("목록은 순위 순으로 요약만 준다")
    void listsCandidatesByRank() throws Exception {
        http.perform(get("/api/v1/schedules/{id}/routes", scheduleId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].routeId").value(selectedRouteId))
                .andExpect(jsonPath("$.data[0].rankNo").value(1))
                .andExpect(jsonPath("$.data[0].selected").value(true))
                .andExpect(jsonPath("$.data[0].baseDurationMin").value(25))
                .andExpect(jsonPath("$.data[0].fromName").value("집"))
                .andExpect(jsonPath("$.data[0].toName").value("역삼역"))
                .andExpect(jsonPath("$.data[0].segmentCount").value(2))
                .andExpect(jsonPath("$.data[0].modes").value(org.hamcrest.Matchers.contains("WALK", "BUS")))
                .andExpect(jsonPath("$.data[1].rankNo").value(2))
                .andExpect(jsonPath("$.data[1].selected").value(false))
                // 목록은 구간 전체를 담지 않는다 — 후보가 늘면 응답만 커지고 목록 화면은 안 쓴다.
                .andExpect(jsonPath("$.data[0].segments").doesNotExist());
    }

    @Test
    @DisplayName("상세는 구간을 주고 좌표는 주지 않는다")
    void detailHasSegmentsWithoutGeometry() throws Exception {
        http.perform(get("/api/v1/routes/{id}", selectedRouteId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeId").value(selectedRouteId))
                .andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
                .andExpect(jsonPath("$.data.providerPathId").value("path-1"))
                .andExpect(jsonPath("$.data.segments.length()").value(2))
                .andExpect(jsonPath("$.data.segments[0].seq").value(1))
                .andExpect(jsonPath("$.data.segments[0].mode").value("WALK"))
                .andExpect(jsonPath("$.data.segments[1].mode").value("BUS"))
                .andExpect(jsonPath("$.data.segments[1].providerRouteKey").value("146"))
                .andExpect(jsonPath("$.data.segments[1].baseDurationMin").value(20))
                .andExpect(jsonPath("$.data.segments[1].coordinates").doesNotExist())
                .andExpect(jsonPath("$.data.segments[1].geometry").doesNotExist());
    }

    @Test
    @DisplayName("지도는 저장한 좌표를 그대로 주고 bounds 를 계산한다")
    void mapReturnsStoredCoordinatesAndBounds() throws Exception {
        http.perform(get("/api/v1/routes/{id}/map", selectedRouteId).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeId").value(selectedRouteId))
                .andExpect(jsonPath("$.data.segments.length()").value(2))
                // 좌표가 없는 구간은 빈 배열이다. 없는 좌표를 지어내지 않는다.
                .andExpect(jsonPath("$.data.segments[0].mode").value("WALK"))
                .andExpect(jsonPath("$.data.segments[0].coordinates").isEmpty())
                .andExpect(jsonPath("$.data.segments[1].coordinates.length()").value(3))
                .andExpect(jsonPath("$.data.segments[1].coordinates[0].longitude").value(127.0276))
                .andExpect(jsonPath("$.data.segments[1].coordinates[0].latitude").value(37.4979))
                .andExpect(jsonPath("$.data.segments[1].coordinates[2].longitude").value(127.0364))
                .andExpect(jsonPath("$.data.bounds.minLat").value(37.4979))
                .andExpect(jsonPath("$.data.bounds.minLng").value(127.0276))
                .andExpect(jsonPath("$.data.bounds.maxLat").value(37.5006))
                .andExpect(jsonPath("$.data.bounds.maxLng").value(127.0364));
    }

    @Test
    @DisplayName("좌표가 하나도 없으면 bounds 는 null 이다")
    void boundsIsNullWhenNothingToDraw() throws Exception {
        String listed = http.perform(get("/api/v1/schedules/{id}/routes", scheduleId).header("Authorization", bearer))
                .andReturn().getResponse().getContentAsString();
        long withoutGeometry = json.readTree(listed).path("data").get(1).path("routeId").asLong();

        http.perform(get("/api/v1/routes/{id}/map", withoutGeometry).header("Authorization", bearer))
                .andExpect(status().isOk())
                // 0,0 으로 채우면 화면이 아프리카 앞바다를 비춘다.
                .andExpect(jsonPath("$.data.bounds").doesNotExist())
                .andExpect(jsonPath("$.data.segments[0].coordinates").isEmpty());
    }

    @Test
    @DisplayName("남의 경로는 볼 수 없다 — routeId 는 순번이라 찍어 볼 수 있다")
    void routesAreOwnedByTheScheduleOwner() throws Exception {
        long stranger = users.save(User.builder().email("stranger@example.test").nickname("남").build()).getId();
        String theirs = "Bearer " + tokens.createAccessToken(stranger);

        http.perform(get("/api/v1/routes/{id}", selectedRouteId).header("Authorization", theirs))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/routes/{id}/map", selectedRouteId).header("Authorization", theirs))
                .andExpect(status().isForbidden());
        http.perform(get("/api/v1/schedules/{id}/routes", scheduleId).header("Authorization", theirs))
                .andExpect(status().isForbidden());

        http.perform(get("/api/v1/routes/{id}", selectedRouteId)).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/routes/{id}/map", selectedRouteId)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("없는 경로는 404, 평가 전 일정은 빈 배열 — 둘은 다른 상황이다")
    void missingRouteIs404ButUnevaluatedScheduleIsEmpty() throws Exception {
        http.perform(get("/api/v1/routes/{id}", 999_999L).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        http.perform(get("/api/v1/routes/{id}/map", 999_999L).header("Authorization", bearer))
                .andExpect(status().isNotFound());
        http.perform(get("/api/v1/schedules/{id}/routes", 999_999L).header("Authorization", bearer))
                .andExpect(status().isNotFound());

        long fresh = newSchedule("평가 전");
        String empty = http.perform(get("/api/v1/schedules/{id}/routes", fresh).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(empty).path("success").asBoolean())
                .as("경로가 없는 것은 실패가 아니다 — 아직 계산 전일 뿐이다").isTrue();
    }
}
