package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.pipeline.entity.BaselineServing;
import com.ssafy.ddubi.domain.pipeline.entity.WeatherBucket;
import com.ssafy.ddubi.domain.pipeline.repository.BaselineServingRepository;
import com.ssafy.ddubi.domain.recommendation.entity.Confidence;
import com.ssafy.ddubi.domain.recommendation.port.BusSegmentPredictionRequest;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePrediction;
import com.ssafy.ddubi.domain.recommendation.port.BusTravelTimePredictor;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStateReader;
import com.ssafy.ddubi.domain.recommendation.port.SegmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 경로의 구간별 소요시간을 정한다. 추천의 숫자는 전부 여기서 나온다.
 *
 * <h3>기본 소요시간은 3단으로 내려간다</h3>
 * <ol>
 *   <li>예측 서비스 — 진입 예상시각 기준. 버스 구간만</li>
 *   <li>{@code baseline_serving} 평소값(p50)</li>
 *   <li>외부 경로 API 시간 — 마지막 보루. 도보는 처음부터 여기다</li>
 * </ol>
 *
 * <h3>실시간 상태는 4단째가 아니라 '보정'이다</h3>
 * Redis {@code segment:status} 는 <b>지금</b>의 상태다. 한 시간 뒤에 지나갈 구간에 지금의
 * 지연을 더하면 예측이 아니라 착각이 된다. ML 결정 문서(2026-09-08)의 "현재 관측을 미래 구간의
 * 실제 상태로 취급하지 않는다" 를 따라, <b>진입 예상시각이 그 값의 유효기간 안에 드는 구간에만</b>
 * 더한다. 그래서 이 클래스는 구간을 순서대로 훑으며 진입 시각을 누적한다.
 *
 * <h3>예측은 왜 두 번 훑고 부르나</h3>
 * 예측 입력에 "진입 예상 날짜·시간대" 가 들어가는데, 그 값은 앞 구간들의 소요시간이 정해져야
 * 나온다. 그렇다고 구간마다 따로 부르면 왕복이 구간 수만큼 쌓인다. 그래서
 * <b>1차로 경로 API 시간을 써서 진입 시각을 잡아 배치 요청을 한 번에 보내고</b>,
 * 2차에서 받은 예측으로 다시 누적한다. 1·2차의 시간대가 갈리는 경우가 있지만, 시간대 경계에
 * 걸친 구간 하나의 오차와 구간 수만큼의 왕복을 맞바꾼 것이다.
 */
@Service
public class TravelTimeEstimator {

    private static final Logger log = LoggerFactory.getLogger(TravelTimeEstimator.class);

    /** 기준선의 요일 구분과 시간대를 뽑는 기준 시간대. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 날씨 구간은 아직 예보를 연결하지 않아 한쪽으로 고정한다. 비 오는 날 값을 임의로
     * 고르면 근거 없이 낙관하거나 비관하는 셈이라, 확정될 때까지 건조를 쓴다.
     */
    private static final WeatherBucket DEFAULT_WEATHER = WeatherBucket.DRY;

    private final BusTravelTimePredictor predictor;
    private final SegmentStateReader segmentStateReader;
    private final BaselineServingRepository baselineRepository;
    private final BaselineVersionProvider baselineVersionProvider;

    public TravelTimeEstimator(BusTravelTimePredictor predictor,
                               SegmentStateReader segmentStateReader,
                               BaselineServingRepository baselineRepository,
                               BaselineVersionProvider baselineVersionProvider) {
        this.predictor = predictor;
        this.segmentStateReader = segmentStateReader;
        this.baselineRepository = baselineRepository;
        this.baselineVersionProvider = baselineVersionProvider;
    }

    /**
     * @param departAt 이 시각에 출발한다고 보고 구간별 진입 시각을 누적한다
     */
    public TravelTimeEstimate estimate(List<SegmentEstimateInput> segments, Instant departAt) {
        Objects.requireNonNull(departAt, "출발 시각이 있어야 진입 시각을 계산할 수 있습니다.");
        if (segments.isEmpty()) {
            return new TravelTimeEstimate(List.of(), 0, Confidence.LOW, true);
        }

        Map<String, BusTravelTimePrediction> predictions = fetchPredictions(segments, departAt);
        Map<String, SegmentStatus> statuses = fetchStatuses(segments);
        BaselineSnapshot baselines = loadBaselines(segments, departAt);

        List<SegmentEstimate> results = new ArrayList<>(segments.size());
        Instant cursor = departAt;

        for (SegmentEstimateInput segment : segments) {
            SegmentEstimate estimate = estimateOne(segment, cursor, predictions, statuses, baselines);
            results.add(estimate);
            cursor = cursor.plusSeconds(estimate.totalSeconds());
        }

        long total = results.stream().mapToLong(SegmentEstimate::totalSeconds).sum();
        return new TravelTimeEstimate(results, total, confidenceOf(results), isFallbackUsed(results));
    }

    // ------------------------------------------------------------------
    // 구간 하나
    // ------------------------------------------------------------------

    private SegmentEstimate estimateOne(SegmentEstimateInput segment,
                                        Instant enteredAt,
                                        Map<String, BusTravelTimePrediction> predictions,
                                        Map<String, SegmentStatus> statuses,
                                        BaselineSnapshot baselines) {
        long base;
        EstimateSource source;
        String note = null;

        BusTravelTimePrediction prediction = segment.isPredictable()
                ? predictions.get(segment.segmentId())
                : null;

        if (prediction != null && prediction.isPredicted()) {
            base = prediction.seconds();
            source = EstimateSource.PREDICTION;
        } else {
            String predictionNote = prediction == null ? null : prediction.reason();
            Long baseline = readBaseline(segment, enteredAt, baselines);
            if (baseline != null) {
                base = baseline;
                source = EstimateSource.BASELINE;
                note = predictionNote;
            } else {
                base = segment.routeApiSeconds();
                source = EstimateSource.ROUTE_API;
                note = segment.isPredictable() ? joinNotes(predictionNote, "기준선 없음") : null;
            }
        }

        long adjustment = adjustmentFor(segment, enteredAt, statuses);
        return new SegmentEstimate(segment.sequence(), segment.segmentId(), enteredAt,
                base, source, adjustment, note);
    }

    /**
     * 실시간 지연 보정. <b>진입 예상시각이 그 값의 유효기간 안일 때만</b> 더한다.
     * 유효기간이 지난 값은 없는 것으로 본다 — 오래된 지연을 미래에 그대로 얹는 것이
     * 보정을 아예 안 하는 것보다 나쁘다.
     */
    private long adjustmentFor(SegmentEstimateInput segment, Instant enteredAt,
                               Map<String, SegmentStatus> statuses) {
        if (segment.segmentId() == null) {
            return 0;
        }
        SegmentStatus status = statuses.get(segment.segmentId());
        if (status == null || !status.isUsableAt(enteredAt)) {
            return 0;
        }
        return Math.max(0, status.delaySeconds());
    }

    /**
     * 기준선은 <b>진입 예상시각</b>의 요일·시간대로 찾는다. 조회 시각으로 찾으면
     * 밤 11시에 다음 날 아침 경로를 물었을 때 심야 기준선이 나온다.
     */
    private Long readBaseline(SegmentEstimateInput segment, Instant enteredAt, BaselineSnapshot baselines) {
        if (segment.segmentId() == null) {
            return null;
        }
        ZonedDateTime entry = enteredAt.atZone(ZONE);
        return baselines.p50(segment.segmentId(),
                (byte) entry.getDayOfWeek().getValue(), (byte) entry.getHour());
    }

    /**
     * 기준선을 <b>한 번의 쿼리로</b> 당겨 온다.
     *
     * <h3>어느 요일·시간대를 당기나</h3>
     * 1차 훑기(경로 API 시간)로 잡은 진입 시각들의 요일·시간대에 <b>앞뒤 한 시간</b>을 더한다.
     * 2차에서 예측·기준선이 1차보다 길거나 짧으면 진입 시각이 밀리는데, 그때 시간대 경계를
     * 넘어가면 스냅샷에 없는 칸을 찾게 된다. 한 시간씩 여유를 두면 그 경우가 덮인다.
     * 자정을 넘나드는 경로를 위해 0시·23시가 걸리면 앞뒤 요일도 함께 넣는다.
     */
    private BaselineSnapshot loadBaselines(List<SegmentEstimateInput> segments, Instant departAt) {
        String activeVersion = baselineVersionProvider.activeVersion();
        if (activeVersion == null) {
            return BaselineSnapshot.empty();
        }

        Set<String> segmentIds = new LinkedHashSet<>();
        Set<Byte> dows = new LinkedHashSet<>();
        Set<Byte> hours = new LinkedHashSet<>();

        Instant cursor = departAt;
        for (SegmentEstimateInput segment : segments) {
            if (segment.segmentId() != null) {
                segmentIds.add(segment.segmentId());
                ZonedDateTime entry = cursor.atZone(ZONE);
                int dow = entry.getDayOfWeek().getValue();
                int hour = entry.getHour();

                dows.add((byte) dow);
                hours.add((byte) hour);
                hours.add((byte) ((hour + 23) % 24));
                hours.add((byte) ((hour + 1) % 24));
                if (hour == 0) {
                    dows.add((byte) previousDow(dow));
                } else if (hour == 23) {
                    dows.add((byte) nextDow(dow));
                }
            }
            cursor = cursor.plusSeconds(segment.routeApiSeconds());
        }

        if (segmentIds.isEmpty()) {
            return BaselineSnapshot.empty();
        }
        List<BaselineServing> rows = baselineRepository.findForEstimation(
                activeVersion, segmentIds, dows, hours, DEFAULT_WEATHER);
        return BaselineSnapshot.of(rows);
    }

    /** ISO 요일(월=1 … 일=7) 기준 순환. */
    private static int previousDow(int dow) {
        return dow == 1 ? 7 : dow - 1;
    }

    private static int nextDow(int dow) {
        return dow == 7 ? 1 : dow + 1;
    }

    // ------------------------------------------------------------------
    // 배치 조회
    // ------------------------------------------------------------------

    /** 1차 훑기 — 경로 API 시간으로 진입 시각을 잡아 예측 요청을 한 번에 만든다. */
    private Map<String, BusTravelTimePrediction> fetchPredictions(List<SegmentEstimateInput> segments,
                                                                  Instant departAt) {
        List<BusSegmentPredictionRequest> requests = new ArrayList<>();
        Instant cursor = departAt;
        for (SegmentEstimateInput segment : segments) {
            if (segment.isPredictable()) {
                ZonedDateTime entry = cursor.atZone(ZONE);
                requests.add(new BusSegmentPredictionRequest(
                        segment.segmentId(),
                        segment.busKey().providerRouteId(),
                        segment.busKey().providerFromStopId(),
                        segment.busKey().providerToStopId(),
                        segment.busKey().fromStopSequence(),
                        entry.toLocalDate(),
                        entry.getHour()));
            }
            cursor = cursor.plusSeconds(segment.routeApiSeconds());
        }
        if (requests.isEmpty()) {
            return Map.of();
        }
        try {
            return predictor.predict(requests);
        } catch (RuntimeException e) {
            // 예측 서비스가 죽어도 추천은 나가야 한다.
            log.warn("예측 서비스 호출 실패 — 기준선·경로 API 로 계산합니다: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, SegmentStatus> fetchStatuses(List<SegmentEstimateInput> segments) {
        Set<String> ids = new LinkedHashSet<>();
        for (SegmentEstimateInput segment : segments) {
            if (segment.segmentId() != null) {
                ids.add(segment.segmentId());
            }
        }
        return segmentStateReader.readAll(ids);
    }

    // ------------------------------------------------------------------
    // 신뢰도
    // ------------------------------------------------------------------

    /** 가장 약한 구간이 전체를 정한다. 도보는 항상 경로 API 라서 판정에서 뺀다. */
    private static Confidence confidenceOf(List<SegmentEstimate> results) {
        boolean anyRouteApi = false;
        boolean anyBaseline = false;
        for (SegmentEstimate estimate : results) {
            if (estimate.segmentId() == null) {
                continue;   // 도보 — 경로 API 가 정해진 출처다
            }
            if (estimate.source() == EstimateSource.ROUTE_API) {
                anyRouteApi = true;
            } else if (estimate.source() == EstimateSource.BASELINE) {
                anyBaseline = true;
            }
        }
        if (anyRouteApi) {
            return Confidence.LOW;
        }
        return anyBaseline ? Confidence.MID : Confidence.HIGH;
    }

    private static boolean isFallbackUsed(List<SegmentEstimate> results) {
        return results.stream()
                .filter(estimate -> estimate.segmentId() != null)
                .anyMatch(estimate -> estimate.source() != EstimateSource.PREDICTION);
    }

    private static String joinNotes(String first, String second) {
        if (first == null) {
            return second;
        }
        return first + " " + second;
    }
}
