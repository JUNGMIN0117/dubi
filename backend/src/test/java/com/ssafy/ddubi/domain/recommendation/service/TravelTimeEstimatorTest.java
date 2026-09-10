package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.pipeline.entity.BaselineServing;
import com.ssafy.ddubi.domain.pipeline.repository.BaselineServingRepository;
import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.port.BusSegmentPredictionRequest;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePrediction;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePredictor;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStateReader;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStatus;
import com.ssafy.ddubi.domain.route.model.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 소요시간 산정기. 고정하려는 것:
 *
 * <ul>
 *   <li>3단 폴백이 순서대로 내려갈 것 (예측 → 기준선 → 경로 API)</li>
 *   <li>폴백한 이유가 결과에 남을 것 — 조용한 폴백은 나중에 설명할 수 없다</li>
 *   <li>실시간 보정은 <b>유효기간 안의 구간에만</b> 붙을 것</li>
 *   <li>진입 시각이 앞 구간들의 소요시간만큼 밀릴 것</li>
 *   <li>예측 호출이 구간 수와 무관하게 <b>한 번</b>일 것</li>
 *   <li>Redis·예측 서비스가 죽어도 결과가 나올 것</li>
 * </ul>
 */
class TravelTimeEstimatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String SEG_A = "BUS:100100609:123456:123789";
    private static final String SEG_B = "BUS:100100609:123789:123900";
    private static final String VERSION = "2026-09-01";

    /** 2026-09-10(목) 08:00 KST. 요일 4, 시간대 8. */
    private static final Instant DEPART_AT =
            LocalDateTime.of(2026, 9, 10, 8, 0).atZone(SEOUL).toInstant();

    private FakePredictor predictor;
    private FakeStateReader stateReader;
    private BaselineServingRepository baselineRepository;
    private BaselineVersionProvider versionProvider;

    @BeforeEach
    void setUp() {
        predictor = new FakePredictor();
        stateReader = new FakeStateReader();
        baselineRepository = mock(BaselineServingRepository.class);
        versionProvider = mock(BaselineVersionProvider.class);
        when(versionProvider.activeVersion()).thenReturn(VERSION);
        givenBaselines();
    }

    private TravelTimeEstimator estimator() {
        return new TravelTimeEstimator(predictor, stateReader, baselineRepository, versionProvider);
    }

    // ------------------------------------------------------------------
    // 3단 폴백
    // ------------------------------------------------------------------

    @Test
    @DisplayName("예측이 있으면 예측을 쓰고 신뢰도는 HIGH")
    void usesPredictionWhenAvailable() {
        predictor.predicted(SEG_A, 600);

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.source()).isEqualTo(EstimateSource.PREDICTION);
                    assertThat(segment.baseSeconds()).isEqualTo(600);
                });
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("예측 불가면 기준선으로 내려가고 신뢰도는 MID")
    void fallsBackToBaseline() {
        predictor.unavailable(SEG_A, "모델 입력 없음");
        givenBaselines(baseline(SEG_A, (byte) 4, (byte) 8, 700));

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.source()).isEqualTo(EstimateSource.BASELINE);
                    assertThat(segment.baseSeconds()).isEqualTo(700);
                });
        assertThat(result.confidence()).isEqualTo(Confidence.MID);
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("기준선도 없으면 경로 API 시간을 쓰고 신뢰도는 LOW — 지금 실제 상태다")
    void fallsBackToRouteApi() {
        predictor.unavailable(SEG_A, "예측 서비스가 연결되지 않았습니다.");

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments()).singleElement()
                .satisfies(segment -> {
                    assertThat(segment.source()).isEqualTo(EstimateSource.ROUTE_API);
                    assertThat(segment.baseSeconds()).isEqualTo(900);
                });
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("폴백한 이유가 결과에 남는다 — 조용히 내려가면 나중에 설명할 수 없다")
    void recordsWhyItFellBack() {
        predictor.unavailable(SEG_A, "모델 입력 없음");

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments().get(0).note())
                .contains("모델 입력 없음")
                .contains("기준선 없음");
    }

    @Test
    @DisplayName("도보는 항상 경로 API 이고 신뢰도 판정에 끼지 않는다")
    void walkDoesNotDragConfidenceDown() {
        predictor.predicted(SEG_A, 600);

        TravelTimeEstimate result = estimator().estimate(
                List.of(walkSegment(1, 300), busSegment(2, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments().get(0).source()).isEqualTo(EstimateSource.ROUTE_API);
        assertThat(result.confidence()).isEqualTo(Confidence.HIGH);   // 도보 때문에 LOW 가 되면 안 된다
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("가장 약한 구간이 전체 신뢰도를 정한다 — 아홉 개가 예측이어도 하나가 경로 API 면 LOW")
    void weakestSegmentDecidesConfidence() {
        predictor.predicted(SEG_A, 600);
        predictor.unavailable(SEG_B, "학습 범위 밖");

        TravelTimeEstimate result = estimator().estimate(
                List.of(busSegment(1, SEG_A, 900), busSegment(2, SEG_B, 800)), DEPART_AT);

        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    // ------------------------------------------------------------------
    // 실시간 보정 — 이 산정기의 핵심 판단
    // ------------------------------------------------------------------

    @Test
    @DisplayName("진입 예상시각이 유효기간 안이면 지연을 더한다")
    void appliesAdjustmentWhenStatusIsStillValid() {
        predictor.predicted(SEG_A, 600);
        stateReader.put(SEG_A, 120, DEPART_AT.plusSeconds(600));   // 10분 뒤까지 유효

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments().get(0).adjustmentSeconds()).isEqualTo(120);
        assertThat(result.totalSeconds()).isEqualTo(720);
    }

    @Test
    @DisplayName("유효기간이 지난 구간에는 지연을 더하지 않는다 — 지금의 정체를 한 시간 뒤에 얹지 않는다")
    void ignoresAdjustmentForSegmentsEnteredAfterValidUntil() {
        // 첫 구간 1시간, 그 다음에야 SEG_B 에 진입한다
        predictor.predicted(SEG_A, 3600);
        predictor.predicted(SEG_B, 600);
        stateReader.put(SEG_B, 300, DEPART_AT.plusSeconds(300));   // 5분 뒤면 만료

        TravelTimeEstimate result = estimator().estimate(
                List.of(busSegment(1, SEG_A, 3600), busSegment(2, SEG_B, 600)), DEPART_AT);

        assertThat(result.segments().get(1).adjustmentSeconds())
                .as("한 시간 뒤 지나갈 구간에 지금의 지연을 더하면 예측이 아니라 착각이다")
                .isZero();
        assertThat(result.totalSeconds()).isEqualTo(4200);
    }

    @Test
    @DisplayName("보정은 기준선·경로 API 위에도 얹힌다 — 출처와 무관하다")
    void adjustmentAppliesRegardlessOfSource() {
        predictor.unavailable(SEG_A, "예측 없음");
        stateReader.put(SEG_A, 60, DEPART_AT.plusSeconds(3600));

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments().get(0).source()).isEqualTo(EstimateSource.ROUTE_API);
        assertThat(result.segments().get(0).adjustmentSeconds()).isEqualTo(60);
    }

    // ------------------------------------------------------------------
    // 진입 시각 누적
    // ------------------------------------------------------------------

    @Test
    @DisplayName("진입 시각이 앞 구간들의 소요시간만큼 밀린다")
    void entryTimeAccumulates() {
        predictor.predicted(SEG_A, 600);
        predictor.predicted(SEG_B, 300);

        TravelTimeEstimate result = estimator().estimate(
                List.of(walkSegment(1, 180), busSegment(2, SEG_A, 900), busSegment(3, SEG_B, 400)),
                DEPART_AT);

        assertThat(result.segments().get(0).enteredAt()).isEqualTo(DEPART_AT);
        assertThat(result.segments().get(1).enteredAt()).isEqualTo(DEPART_AT.plusSeconds(180));
        assertThat(result.segments().get(2).enteredAt()).isEqualTo(DEPART_AT.plusSeconds(780));
        assertThat(result.totalSeconds()).isEqualTo(1080);
    }

    // ------------------------------------------------------------------
    // 배치 · 장애 내성
    // ------------------------------------------------------------------

    @Test
    @DisplayName("예측 호출은 구간이 몇 개든 한 번뿐이다")
    void predictsInOneBatch() {
        predictor.predicted(SEG_A, 600);
        predictor.predicted(SEG_B, 300);

        estimator().estimate(
                List.of(busSegment(1, SEG_A, 900), walkSegment(2, 120), busSegment(3, SEG_B, 400)),
                DEPART_AT);

        assertThat(predictor.callCount).isEqualTo(1);
        assertThat(predictor.lastRequests).hasSize(2);   // 도보는 예측 대상이 아니다
    }

    @Test
    @DisplayName("예측 요청에는 조회 시각이 아니라 진입 예상 날짜·시간대가 들어간다")
    void predictionRequestCarriesEntryTime() {
        predictor.predicted(SEG_B, 300);

        // 08:00 출발 + 2시간짜리 첫 구간 → SEG_B 진입은 10시
        estimator().estimate(
                List.of(walkSegment(1, 7200), busSegment(2, SEG_B, 400)), DEPART_AT);

        BusSegmentPredictionRequest request = predictor.lastRequests.get(0);
        assertThat(request.serviceDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(request.hourSlot()).isEqualTo(10);
    }

    @Test
    @DisplayName("예측 서비스가 터져도 추천은 나간다")
    void survivesPredictorFailure() {
        predictor.explode = true;

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.totalSeconds()).isEqualTo(900);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("실시간 상태를 못 읽어도 추천은 나간다 — 보정만 빠진다")
    void survivesStateReaderFailure() {
        predictor.predicted(SEG_A, 600);
        stateReader.explode = true;

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.totalSeconds()).isEqualTo(600);
        assertThat(result.segments().get(0).adjustmentSeconds()).isZero();
    }

    @Test
    @DisplayName("활성 기준선이 없으면 조회조차 하지 않는다 — 지금이 그 상태다")
    void skipsBaselineQueryWhenNothingActivated() {
        when(versionProvider.activeVersion()).thenReturn(null);
        predictor.unavailable(SEG_A, "예측 없음");

        TravelTimeEstimate result = estimator().estimate(List.of(busSegment(1, SEG_A, 900)), DEPART_AT);

        assertThat(result.segments().get(0).source()).isEqualTo(EstimateSource.ROUTE_API);
        org.mockito.Mockito.verify(baselineRepository, org.mockito.Mockito.never())
                .findForEstimation(anyString(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 픽스처
    // ------------------------------------------------------------------

    private void givenBaselines(BaselineServing... rows) {
        when(baselineRepository.findForEstimation(anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(rows));
    }

    private static BaselineServing baseline(String segmentId, byte dow, byte hourSlot, int p50) {
        BaselineServing row = mock(BaselineServing.class);
        when(row.getSegmentId()).thenReturn(segmentId);
        when(row.getDow()).thenReturn(dow);
        when(row.getHourSlot()).thenReturn(hourSlot);
        when(row.getP50()).thenReturn(p50);
        return row;
    }

    private static SegmentEstimateInput busSegment(int sequence, String segmentId, long routeApiSeconds) {
        return new SegmentEstimateInput(sequence, TransportMode.BUS, segmentId, routeApiSeconds,
                new BusProviderKey("100100609", "123456", "123789", 3));
    }

    private static SegmentEstimateInput walkSegment(int sequence, long routeApiSeconds) {
        return new SegmentEstimateInput(sequence, TransportMode.WALK, null, routeApiSeconds, null);
    }

    /** 예측 서비스 대역. 호출 횟수를 세는 것이 목적이라 목 대신 손으로 만든다. */
    private static final class FakePredictor implements BusTravelTimePredictor {
        private final Map<String, BusTravelTimePrediction> answers = new LinkedHashMap<>();
        private List<BusSegmentPredictionRequest> lastRequests = List.of();
        private int callCount;
        private boolean explode;

        void predicted(String segmentId, long seconds) {
            answers.put(segmentId, BusTravelTimePrediction.predicted(seconds));
        }

        void unavailable(String segmentId, String reason) {
            answers.put(segmentId, BusTravelTimePrediction.unavailable(reason));
        }

        @Override
        public Map<String, BusTravelTimePrediction> predict(List<BusSegmentPredictionRequest> requests) {
            callCount++;
            lastRequests = new ArrayList<>(requests);
            if (explode) {
                throw new IllegalStateException("예측 서비스 응답 없음");
            }
            Map<String, BusTravelTimePrediction> results = new LinkedHashMap<>();
            for (BusSegmentPredictionRequest request : requests) {
                results.put(request.segmentId(), answers.getOrDefault(request.segmentId(),
                        BusTravelTimePrediction.unavailable("대역에 등록되지 않은 구간")));
            }
            return results;
        }
    }

    /** 실시간 상태 대역. */
    private static final class FakeStateReader implements SegmentStateReader {
        private final Map<String, SegmentStatus> statuses = new HashMap<>();
        private boolean explode;

        void put(String segmentId, int delaySeconds, Instant validUntil) {
            statuses.put(segmentId, new SegmentStatus(segmentId, "delayed", delaySeconds,
                    0.8, Instant.now(), validUntil, "spark.segment-status"));
        }

        @Override
        public Map<String, SegmentStatus> readAll(Collection<String> segmentIds) {
            if (explode) {
                return Map.of();   // 어댑터가 예외를 삼키고 빈 결과를 준다
            }
            Map<String, SegmentStatus> found = new LinkedHashMap<>();
            for (String id : segmentIds) {
                SegmentStatus status = statuses.get(id);
                if (status != null) {
                    found.put(id, status);
                }
            }
            return found;
        }
    }
}
