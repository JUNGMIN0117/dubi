package com.ssafy.ddubi.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.recommendation.client.GmsRecommendationGuideClient;
import com.ssafy.ddubi.domain.recommendation.client.RecommendationGuideProperties;
import com.ssafy.ddubi.domain.recommendation.evaluation.ScheduleEvaluationV2;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** AI가 시간·구조를 바꾸지 않고 서버 계산값 위에 표현 문구만 더하는지 검증한다. */
class RecommendationGuideServiceTest {
    @Test
    void classifiesRouteDepartureEtaAndNoChangeBeforeCallingGms() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 2, "08:00", "07:50", "08:40", "08:35"))
                                .type())
                .isEqualTo("ROUTE_CHANGED");
        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 1, "08:00", "07:50", "08:40", "08:35"))
                                .type())
                .isEqualTo("DEPARTURE_TIME_CHANGED");
        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 1, "08:00", "08:00", "08:40", "08:45"))
                                .type())
                .isEqualTo("ETA_UPDATED");
        assertThat(
                        RecommendationGuideService.change(
                                        evaluation(json, 1, 1, "08:00", "08:00", "08:40", "08:40"))
                                .type())
                .isEqualTo("NO_CHANGE");
    }

    private static ScheduleEvaluationV2 evaluation(
            ObjectMapper json,
            long selected,
            long proposed,
            String planned,
            String proposedDeparture,
            String previousArrival,
            String proposedArrival)
            throws Exception {
        return json.readValue(
                """
                {
                  "selectedRouteId":%d,
                  "proposedRouteId":%d,
                  "plannedDepartAt":"2026-09-22T%s:00",
                  "proposedDepartAt":"2026-09-22T%s:00",
                  "originalEstimatedArrivalAt":"2026-09-22T%s:00",
                  "estimatedArrivalAt":"2026-09-22T%s:00",
                  "causes":[],"segments":[],"candidateEstimates":[]
                }
                """
                        .formatted(
                                selected,
                                proposed,
                                planned,
                                proposedDeparture,
                                previousArrival,
                                proposedArrival),
                ScheduleEvaluationV2.class);
    }

    @Test
    void guideKeepsCalculatedTimesAndReturnsStructuredSteps() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ScheduleEvaluationV2 evaluation =
                json.readValue(
                        """
{
  "schemaVersion":"schedule-evaluation-v2",
  "evaluationId":"evaluation-1",
  "selectedRouteId":1,
  "proposedRouteId":2,
  "proposedDepartAt":"2026-09-21T08:10:00",
  "estimatedArrivalAt":"2026-09-21T08:52:00",
  "durationSeconds":2520,
  "fare":{"minimumWon":1550,"maximumWon":1650},
  "asOf":"2026-09-20T23:00:00Z",
  "validUntil":"2026-09-20T23:10:00Z",
  "causes":[],
  "segments":[
    {"candidateKey":"candidate-0","sequence":1,"mode":"WALK","routeLabel":null,"fromName":"집","toName":"강남역","durationSeconds":300},
    {"candidateKey":"candidate-0","sequence":2,"mode":"SUBWAY","routeLabel":"2호선","fromName":"강남역","toName":"잠실역","durationSeconds":1800}
  ],
  "candidateEstimates":[]
}
""",
                        ScheduleEvaluationV2.class);
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, false));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any()))
                .thenReturn(
                        Optional.of(
                                new GmsRecommendationGuideClient.GeneratedCopy(
                                        "강남역까지 걸은 뒤 잠실역까지 지하철로 이동하세요.", "현재 경로를 유지해도 돼요.", null)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.5-pro",
                        1000,
                        200);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        var result = service.guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.departureAt()).isEqualTo(LocalDateTime.parse("2026-09-21T08:10:00"));
        assertThat(result.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.parse("2026-09-21T08:52:00"));
        assertThat(result.routeSteps()).hasSize(2);
        assertThat(result.routeSteps().get(1).routeLabel()).isEqualTo("2호선");
        assertThat(result.fare().minimumWon()).isEqualTo(1550);
        assertThat(result.routeSummary()).contains("잠실역");
        // 경로가 달라진 계산이라 AI 문장을 부른다. 바뀐 것이 없으면 아래 검사처럼 서버 문장만 쓴다.
        assertThat(result.changeType()).isEqualTo("ROUTE_CHANGED");
        assertThat(result.recommendationReason()).contains("유지");
        assertThat(result.delay().status()).isEqualTo("NO_CONFIRMED_DELAY");
        assertThat(result.generatedBy()).isEqualTo("GMS:gpt-5.5-pro");
    }

    // ------------------------------------------------------------------
    // 크레딧을 지키는 규칙 (2026-09-22 하루 6 만 크레딧 사고)
    // ------------------------------------------------------------------

    private static ScheduleEvaluationV2 unchanged(ObjectMapper json, String validUntil)
            throws Exception {
        return fixture(json, "evaluation-unchanged", 1, validUntil);
    }

    /** 경로가 1 → 2 로 바뀐 계산. GMS 를 부를 만한 변화다. */
    private static ScheduleEvaluationV2 changed(ObjectMapper json, String validUntil)
            throws Exception {
        return fixture(json, "evaluation-changed", 2, validUntil);
    }

    private static ScheduleEvaluationV2 fixture(
            ObjectMapper json, String id, long proposedRouteId, String validUntil)
            throws Exception {
        return json.readValue(
                """
                {
                  "schemaVersion":"schedule-evaluation-v2",
                  "evaluationId":"%s",
                  "selectedRouteId":1,
                  "proposedRouteId":%d,
                  "plannedDepartAt":"2026-09-21T08:10:00",
                  "proposedDepartAt":"2026-09-21T08:10:00",
                  "originalEstimatedArrivalAt":"2026-09-21T08:52:00",
                  "estimatedArrivalAt":"2026-09-21T08:52:00",
                  "durationSeconds":2520,
                  "asOf":"2026-09-20T23:00:00Z",
                  "validUntil":"%s",
                  "causes":[],
                  "segments":[],
                  "candidateEstimates":[]
                }
                """
                        .formatted(id, proposedRouteId, validUntil),
                ScheduleEvaluationV2.class);
    }

    private record Rig(
            RecommendationGuideService service,
            GmsRecommendationGuideClient gms,
            ValueOperations<String, String> values) {}

    private static Rig rig(ObjectMapper json, ScheduleEvaluationV2 evaluation, long callsToday) {
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, false));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any()))
                .thenReturn(
                        Optional.of(
                                new GmsRecommendationGuideClient.GeneratedCopy(
                                        "AI 경로 문장", "AI 사유 문장", null)));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(callsToday);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.4-nano",
                        1000,
                        200);
        return new Rig(
                new RecommendationGuideService(store, gms, properties, redis, json), gms, values);
    }

    /** 오늘 나간 크레딧의 대부분이 "그대로 유지" 계산이었다. 그 문장은 서버가 쓰면 충분하다. */
    @Test
    void unchangedRecommendationUsesTemplateWithoutCallingGms() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, unchanged(json, "2026-09-20T23:10:00Z"), 1L);

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.changeType()).isEqualTo("NO_CHANGE");
        assertThat(result.generatedBy()).isEqualTo("TEMPLATE");
        assertThat(result.recommendationReason()).contains("유지");
        verify(rig.gms(), never()).generate(any());
    }

    /** 상한을 넘긴 날은 무엇이 바뀌었든 부르지 않는다. 마지막 안전장치다. */
    @Test
    void dailyCallLimitStopsGmsEvenWhenRouteChanged() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, changed(json, "2026-09-20T23:10:00Z"), 201L);

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.changeType()).isEqualTo("ROUTE_CHANGED");
        assertThat(result.generatedBy()).isEqualTo("TEMPLATE");
        verify(rig.gms(), never()).generate(any());
    }

    /** 상한 안이면 부른다 — 위 검사와 짝을 이뤄 카운터가 실제로 판단에 쓰이는지 고정한다. */
    @Test
    void routeChangeWithinLimitCallsGmsOnce() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, changed(json, "2026-09-20T23:10:00Z"), 200L);

        var result = rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.generatedBy()).isEqualTo("GMS:gpt-5.4-nano");
        verify(rig.gms()).generate(any());
    }

    /**
     * 유효기간이 30 초 남았어도 5 분은 캐시한다. 예전에는 남은 시간이 0 이면 캐시를 건너뛰어, 출발 직전(유효기간 1 분)에는 만든 문장이 곧장 버려지고 다음 조회가
     * 또 GMS 를 불렀다.
     */
    @Test
    void resultIsCachedForAtLeastFiveMinutesEvenNearExpiry() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var rig = rig(json, unchanged(json, "2026-09-20T23:01:30Z"), 1L);

        rig.service().guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        verify(rig.values())
                .set(
                        eq("recommendation:guide:7:evaluation-unchanged"),
                        anyString(),
                        eq(RecommendationGuideService.MIN_CACHE_TTL));
    }

    @Test
    void guideDoesNotMixSourceFromDifferentCause() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ScheduleEvaluationV2 evaluation =
                json.readValue(
                        """
{
  "schemaVersion":"schedule-evaluation-v2",
  "evaluationId":"evaluation-multiple-causes",
  "proposedDepartAt":"2026-09-21T08:10:00",
  "estimatedArrivalAt":"2026-09-21T08:52:00",
  "durationSeconds":2520,
  "asOf":"2026-09-20T23:00:00Z",
  "validUntil":"2026-09-20T23:10:00Z",
  "causes":[
    {"eventId":"event-potential","source":"EVENT_SOURCE","segmentIds":["segment-1"],"effect":"DELAYED","confirmed":false},
    {"eventId":"event-confirmed","source":"TOPIS_CONFIRMED","segmentIds":["segment-2"],"effect":"BLOCKED","confirmed":true}
  ],
  "segments":[],
  "candidateEstimates":[]
}
""",
                        ScheduleEvaluationV2.class);
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, false));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any())).thenReturn(Optional.empty());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.multiGet(any()))
                .thenReturn(
                        java.util.Arrays.asList(
                                "{\"source\":\"EVENT_DETAIL\",\"contentText\":\"행사 영향"
                                        + " 가능성\",\"validUntil\":\"2026-09-20T23:10:00Z\"}",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.5-pro",
                        1000,
                        200);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        var result = service.guide(7L, 3L, Instant.parse("2026-09-20T23:01:00Z"));

        assertThat(result.delay().status()).isEqualTo("DISRUPTED");
        assertThat(result.delay().source()).isEqualTo("TOPIS_CONFIRMED");
        assertThat(result.delay().reason()).doesNotContain("행사 영향 가능성");
    }

    /**
     * 출발한 뒤에도 출발할 때 본 안내를 다시 볼 수 있어야 한다(2026-09-22 제보).
     *
     * <p>멈춘 일정의 마지막 결과는 유효기간이 이미 지나 있다. 그 결과는 더 갱신되지 않으므로 "지났다" 를 "틀렸다" 로 다루지 않고 그대로 문장화한다. 그리고 매번
     * GMS 를 다시 부르지 않게 고정 시간 동안 캐시한다.
     */
    @Test
    void frozenOutcomeIsServedPastValidUntilAndCachedForAFixedTime() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        ScheduleEvaluationV2 evaluation =
                json.readValue(
                        """
                        {
                          "schemaVersion":"schedule-evaluation-v2",
                          "evaluationId":"evaluation-departed",
                          "proposedDepartAt":"2026-09-21T08:10:00",
                          "estimatedArrivalAt":"2026-09-21T08:52:00",
                          "durationSeconds":2520,
                          "asOf":"2026-09-20T23:00:00Z",
                          "validUntil":"2026-09-20T23:10:00Z",
                          "causes":[],
                          "segments":[],
                          "candidateEstimates":[]
                        }
                        """,
                        ScheduleEvaluationV2.class);
        ScheduleEvaluationStore store = mock(ScheduleEvaluationStore.class);
        when(store.latestOutcomeV2(7L, 3L))
                .thenReturn(new ScheduleEvaluationStore.LatestOutcome(evaluation, true));
        GmsRecommendationGuideClient gms = mock(GmsRecommendationGuideClient.class);
        when(gms.generate(any())).thenReturn(Optional.empty());
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        var properties =
                new RecommendationGuideProperties(
                        true,
                        "https://gms.ssafy.io/gmsapi",
                        "/api.openai.com/v1/responses",
                        "synthetic",
                        "gpt-5.5-pro",
                        1000,
                        200);
        var service = new RecommendationGuideService(store, gms, properties, redis, json);

        // 유효기간(23:10Z)이 한참 지난 시각에 물어도 거절하지 않는다.
        var result = service.guide(7L, 3L, Instant.parse("2026-09-21T02:00:00Z"));

        assertThat(result.departureAt()).isEqualTo(LocalDateTime.parse("2026-09-21T08:10:00"));
        assertThat(result.estimatedArrivalAt())
                .isEqualTo(LocalDateTime.parse("2026-09-21T08:52:00"));
        verify(values)
                .set(
                        eq("recommendation:guide:7:evaluation-departed"),
                        anyString(),
                        eq(RecommendationGuideService.FROZEN_CACHE_TTL));
    }
}
