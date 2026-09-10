package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.route.mapping.MappedTransitSegment;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

/**
 * 같은 출발시각의 카카오 후보를 버스 ML 평균시간으로 비교하는 검증용 실행기다.
 * API step을 전체 인접 edge의 예측 합으로 교체하고 API 잔여시간은 한 번만 보존한다.
 * 실패한 BUS step은 전체 API 시간으로 복귀한다. 부분 edge 예측과 전체 step을 중복 합산하지 않는다.
 * Redis는 통제/위험 근거만 사용한다. 모델은 실시간 관측을 입력받지 않는 v1 계약이다.
 */
@Service
public class MlRoutePreview {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final BusPreviewClient client;
    private final RouteEvidenceAssembler evidence;
    private final RouteTimelineEvaluator evaluator;

    public MlRoutePreview(BusPreviewClient client, RouteEvidenceAssembler evidence, RouteTimelineEvaluator evaluator) {
        this.client = client; this.evidence = evidence; this.evaluator = evaluator;
    }

    /** 실제 호출 입력과 반환값. applied=false는 응답은 받았지만 BUS step 전체 실패로 적용하지 않은 값이다. */
    public record Prediction(String candidateKey, String stepKey, String segmentId, Instant enteredAt,
                             BusPreviewClient.Input input, BusPreviewClient.Estimate estimate, boolean applied) { }
    /** 후보별 결과·모델 적용량. API_ONLY를 ML 결과로 표시하지 않는다. */
    public record Candidate(Result result, String quality, int mlSteps, int apiSteps) { }
    /** fastest는 반환 후보 중 계산 가능하고 차단되지 않은 최소시간 한 건이다. 실제 최적 경로나 도착 보장이 아니다. */
    public record Comparison(String status, boolean productionReady, Result fastest, List<Candidate> candidates,
                             List<Prediction> predictions, boolean budgetExhausted) {
        public Comparison { candidates = List.copyOf(candidates); predictions = List.copyOf(predictions); }
    }

    /**
     * 외부 호출은 DB lease 안의 계산 구간에서만 수행한다. 한 평가에 64회/20초 한도와 입력별 캐시를 둔다.
     * 다음 edge는 앞 edge의 예측 초를 반영한 KST 날짜/시간대로 조회한다. 시간대가 불명확하면 fallback한다.
     */
    public Comparison compare(List<RouteCandidate> routes, RouteEvidenceAssembler.Snapshot snapshot,
                              Instant asOf, Instant departure, Instant arriveBy) {
        var cache = new HashMap<BusPreviewClient.Input, BusPreviewClient.Estimate>();
        var results = new ArrayList<Candidate>();
        var predictions = new ArrayList<Prediction>();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        boolean exhausted = false;
        for (RouteCandidate route : routes) {
            Journey original = evidence.assemble(route, snapshot, asOf);
            var legs = new ArrayList<Leg>();
            Instant cursor = departure;
            int mlSteps = 0, apiSteps = 0;
            var reasons = new ArrayList<String>();
            for (Leg leg : original.legs()) {
                RouteSegment segment = route.segments().stream().filter(s -> ("step-" + s.sequence()).equals(leg.key())).findFirst().orElse(null);
                Leg chosen = leg;
                var edges = segment == null ? List.<MappedTransitSegment>of() : RouteEvidenceAssembler.edges(segment);
                if (segment != null && segment.mode() == TransportMode.BUS) {
                    if (!supported(edges)) {
                        reasons.add(leg.key() + ":UNVERIFIED_OR_UNSUPPORTED_BUS_MAPPING");
                    } else {
                        Instant edgeCursor = cursor;
                        long total = 0;
                        boolean complete = true;
                        var attempts = new ArrayList<Prediction>();
                        for (MappedTransitSegment edge : edges) {
                            var start = edgeCursor.atZone(KST);
                            var latest = edgeCursor.plusSeconds(leg.entryUncertaintySeconds()).atZone(KST);
                            if (!start.toLocalDate().equals(latest.toLocalDate()) || start.getHour() != latest.getHour()) {
                                reasons.add(leg.key() + ":ENTRY_HOUR_UNCERTAIN"); complete = false; break;
                            }
                            var input = new BusPreviewClient.Input(edge.routeId(), edge.fromNodeId(), edge.toNodeId(),
                                    edge.fromOrder(), start.toLocalDate(), start.getHour());
                            BusPreviewClient.Estimate estimate = cache.get(input);
                            if (estimate == null) {
                                if (cache.size() >= 64 || System.nanoTime() >= deadline) {
                                    exhausted = true; estimate = BusPreviewClient.Estimate.unavailable("ML_BUDGET_EXHAUSTED");
                                } else {
                                    estimate = client.predict(input); cache.put(input, estimate);
                                }
                            }
                            attempts.add(new Prediction(original.key(), leg.key(), edge.segmentId(), edgeCursor, input, estimate, false));
                            if (estimate.seconds() == null) {
                                reasons.add(leg.key() + ":" + estimate.reason()); complete = false; break;
                            }
                            long seconds = (long) Math.ceil(estimate.seconds());
                            total += seconds; edgeCursor = edgeCursor.plusSeconds(seconds);
                        }
                        for (Prediction attempt : attempts) predictions.add(new Prediction(attempt.candidateKey(), attempt.stepKey(),
                                attempt.segmentId(), attempt.enteredAt(), attempt.input(), attempt.estimate(), complete));
                        if (complete) {
                            // 확정된 각 입력의 날짜 범위는 HTTP 응답에서 확인했다. 이 Window는 관측 TTL이 아니라
                            // 이번 고정 입력의 모델 결과를 타임라인 계산기에 전달하는 범위다. 다른 출발 탐색에는 재사용하지 않는다.
                            chosen = new Leg(leg.key(), leg.kind(), leg.segmentIds(), total, total,
                                    new Window("ML_PREVIEW", asOf, asOf, Instant.MAX), List.of(), leg.entryUncertaintySeconds());
                            mlSteps++;
                        }
                    }
                }
                if (chosen == leg) apiSteps++;
                legs.add(chosen); cursor = cursor.plusSeconds(chosen.fallbackSeconds());
            }
            Result raw = evaluator.evaluate(new Journey(original.key(), legs), asOf, departure, arriveBy, evidence.closures(snapshot));
            reasons.addAll(raw.reasons()); reasons.add("VALIDATION_ONLY");
            Result result = new Result(raw.journeyKey(), raw.departureAt(), raw.estimatedArrivalAt(), raw.assessment(), true,
                    raw.steps(), reasons, "ml-prev-v1");
            results.add(new Candidate(result, mlSteps == 0 ? "API_ONLY" : apiSteps == 0 ? "ML_PREVIEW" : "MIXED_PREVIEW", mlSteps, apiSteps));
        }
        Result fastest = results.stream().map(Candidate::result)
                .filter(r -> r.estimatedArrivalAt() != null && (r.assessment() == Assessment.ON_TIME_ESTIMATE || r.assessment() == Assessment.LATE_ESTIMATE))
                .min(Comparator.comparing(Result::estimatedArrivalAt).thenComparing(Result::journeyKey)).orElse(null);
        return new Comparison("PREVIEW", false, fastest, results, predictions, exhausted);
    }

    private boolean supported(List<MappedTransitSegment> edges) {
        if (edges.isEmpty()) return false;
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            if (e.mode() != TransportMode.BUS || !numeric(e.routeId()) || !numeric(e.fromNodeId()) || !numeric(e.toNodeId())
                    || !Objects.equals(e.routeId(), e.realtimeRouteId()) || !Objects.equals(e.fromNodeId(), e.fromRealtimeNodeId())
                    || !Objects.equals(e.toNodeId(), e.toRealtimeNodeId()) || e.fromOrder() < 0 || e.fromOrder() > 10000
                    || e.toOrder() != e.fromOrder() + 1 || e.sequence() != i + 1) return false;
            if (i > 0 && (!edges.get(i-1).routeId().equals(e.routeId()) || !edges.get(i-1).toNodeId().equals(e.fromNodeId())
                    || edges.get(i-1).toOrder() != e.fromOrder())) return false;
        }
        return true;
    }
    private boolean numeric(String value) { return value != null && value.matches("[0-9]{1,30}"); }
}
