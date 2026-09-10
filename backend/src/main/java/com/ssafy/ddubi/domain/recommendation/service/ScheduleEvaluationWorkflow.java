package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.notification.entity.NotificationType;
import com.ssafy.ddubi.domain.notification.service.NotificationCommand;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.evaluation.*;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.*;
import com.ssafy.ddubi.global.exception.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;
import static com.ssafy.ddubi.domain.recommendation.evaluation.RouteTimelineEvaluator.*;

/**
 * 사용자 출발 입력→후보 확보→고정 증거→출발/대안 평가→불변 결과·알림 대기 저장의 실행 흐름이다.
 * 외부 I/O를 DB 트랜잭션 밖에서 수행하며 실패는 저장소가 기록한다. 미래 배차를 만들거나
 * 미승인 ML을 사용하지 않는다. 카카오 요청 주기와 내부 평가 주기는 별도 정책이다.
 */
@Service
public class ScheduleEvaluationWorkflow {
    private final ScheduleEvaluationStore store;
    private final PublicTransitRouteClient routes;
    private final RouteEvidenceAssembler evidence;
    private final DepartureSuggestionService departures;
    private final ScheduleMonitoringPolicy monitoring;
    private final DepartureSuggestionService.SearchPolicy searchPolicy;

    public ScheduleEvaluationWorkflow(ScheduleEvaluationStore store, PublicTransitRouteClient routes,
            RouteEvidenceAssembler evidence, DepartureSuggestionService departures,
            @Value("${ddubi.recommendation.candidate-ttl-seconds:900}") int candidateTtl,
            @Value("${ddubi.recommendation.search-step-seconds:60}") int step,
            @Value("${ddubi.recommendation.max-advance-seconds:3600}") int advance,
            @Value("${ddubi.recommendation.max-evaluations:2000}") int budget) {
        this.store = store; this.routes = routes; this.evidence = evidence; this.departures = departures;
        // 초기 실험 주기이며 검증된 도착 보장 임계값이 아니다. 여유시간으로 가산하지 않는다.
        monitoring = new ScheduleMonitoringPolicy(Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofMinutes(10),
                Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(candidateTtl));
        searchPolicy = new DepartureSuggestionService.SearchPolicy(step, advance, budget);
    }

    /** 후보별 현재 위험은 수치화하지 않고 별도로 표시한다. */
    public record CandidateInfo(String key, List<String> risks, List<RouteStep> steps) { }
    /** 화면에서 변경 경로를 확인하는 최소 정보. 외부 raw 응답은 포함하지 않는다. */
    public record RouteStep(String mode, String fromName, String toName, List<String> vehicles) { }
    /**
     * 계산 시각의 입력·후보·증거를 보존한다. 내부 Instant는 절대시각이고 프론트는 KST로 표시한다.
     * selected는 사용자 경로의 현재 평가, suggestion은 수락 전 대안이다. 원래 입력은 바뀌지 않는다.
     */
    public record Outcome(String evaluationId, long scheduleId, Long recommendationId, Instant plannedDepartAt,
            Instant arriveBy, Instant checkedAt, String selectedKey, Result selected,
            DepartureSuggestionService.Suggestion suggestion, List<CandidateInfo> candidates,
            RouteEvidenceAssembler.Snapshot evidence, TriggerType trigger, String policyVersion, long eventRevision) {
        public Outcome { candidates = List.copyOf(candidates); }
        public Outcome withRecommendationId(Long id) {
            return new Outcome(evaluationId, scheduleId, id, plannedDepartAt, arriveBy, checkedAt, selectedKey,
                    selected, suggestion, candidates, evidence, trigger, policyVersion, eventRevision);
        }
    }

    /** 직접 요청과 scheduler가 같은 흐름을 사용한다. userId=null은 서버 scheduler 전용이며 외부 API는 사용자 ID를 전달한다. */
    public Outcome evaluate(long scheduleId, Long userId, TriggerType trigger, Instant now) {
        var job = store.claim(scheduleId, userId, now);
        try {
            var cache = job.cache();
            boolean refresh = monitoring.refreshCandidates(now, cache == null ? null : cache.search().fetchedAt(), false);
            RouteSearchResult search = refresh ? routes.search(new RouteSearchQuery(job.origin(), job.destination())) : cache.search();
            if (search.candidates().isEmpty()) throw new BusinessException(ErrorCode.ROUTE_NOT_AVAILABLE);
            var snapshot = evidence.read(search.candidates(), now);
            List<Journey> journeys = new ArrayList<>();
            for (RouteCandidate c : search.candidates()) journeys.add(evidence.assemble(c, snapshot, now));
            var suggestion = departures.suggest(journeys, now, job.departure(), job.arriveBy(),
                    now.isAfter(job.departure()) ? job.departure() : now, evidence.closures(snapshot), searchPolicy);
            String selectedKey = selectedKey(job, search);
            Result selected = selected(suggestion, selectedKey);

            // 유효한 후보가 없거나 현재 선택이 지각/통제이면 TTL과 별도로 새 경로를 한 번만 요청한다.
            // 이전 결과도 같은 문제면 매분 재요청하지 않고 후보 TTL까지 기존 후보를 다시 평가한다.
            Outcome previous = store.read(job.previousJson(), Outcome.class);
            boolean newlyAtRisk = selected.assessment() == Assessment.BLOCKED || selected.assessment() == Assessment.LATE_ESTIMATE;
            boolean previousAtRisk = previous != null && (previous.selected().assessment() == Assessment.BLOCKED
                    || previous.selected().assessment() == Assessment.LATE_ESTIMATE);
            if (!refresh && newlyAtRisk && !previousAtRisk) {
                search = routes.search(new RouteSearchQuery(job.origin(), job.destination()));
                if (search.candidates().isEmpty()) throw new BusinessException(ErrorCode.ROUTE_NOT_AVAILABLE);
                snapshot = evidence.read(search.candidates(), now);
                journeys = new ArrayList<>();
                for (RouteCandidate c : search.candidates()) journeys.add(evidence.assemble(c, snapshot, now));
                suggestion = departures.suggest(journeys, now, job.departure(), job.arriveBy(),
                        now.isAfter(job.departure()) ? job.departure() : now, evidence.closures(snapshot), searchPolicy);
                selectedKey = selectedKey(job, search); selected = selected(suggestion, selectedKey); refresh = true;
            }
            // 선택 경로가 이미 정시이면 더 빠르다는 이유만으로 변경 알림을 만들지 않는다.
            if (selected.assessment() == Assessment.ON_TIME_ESTIMATE) {
                suggestion = new DepartureSuggestionService.Suggestion(suggestion.originalCandidates(), selected,
                        suggestion.earliestArrival(), suggestion.evaluatedCount(), suggestion.budgetExhausted());
            }
            var info = new ArrayList<CandidateInfo>();
            for (RouteCandidate c : search.candidates()) info.add(new CandidateInfo(RouteEvidenceAssembler.key(c), evidence.riskReasons(c, snapshot), c.segments().stream().map(s -> new RouteStep(s.mode().name(), s.fromName(), s.toName(), s.vehicles().stream().map(v -> v.name()).toList())).toList()));
            var result = new Outcome(job.token(), scheduleId, null, job.departure(), job.arriveBy(), now,
                    selectedKey, selected, suggestion, info, snapshot, trigger, "sched-v1", job.eventRevision());
            NotificationCommand command = notification(job, result, previous);
            Instant proposed = suggestion.proposed() == null ? null : suggestion.proposed().departureAt();
            return store.complete(job, search, refresh, result, monitoring.nextEvaluation(now, job.departure(), proposed),
                    command == null ? null : store.write(command), command == null ? null : command.suppressKey(), Instant.now());
        } catch (RuntimeException failure) {
            String reason = failure instanceof BusinessException ? "EVALUATION_UNAVAILABLE" : "EVALUATION_FAILED";
            store.fail(job, reason, now);
            throw failure;
        }
    }

    private Result selected(DepartureSuggestionService.Suggestion suggestion, String key) {
        if ("previous-unavailable".equals(key)) {
            return new Result(key, suggestion.originalCandidates().get(0).departureAt(), null, Assessment.BLOCKED,
                    true, List.of(), List.of("SELECTED_ROUTE_UNAVAILABLE"), "timeline-v1");
        }
        return suggestion.originalCandidates().stream().filter(r -> r.journeyKey().equals(key)).findFirst()
                .orElse(suggestion.originalCandidates().get(0));
    }

    /** 캐시 교체 시 차량·방향·정류장열로 기존 선택을 찾는다. 매칭 실패는 선택 소실로 따로 표시할 후속 UI 대상이다. */
    private String selectedKey(ScheduleEvaluationStore.Job job, RouteSearchResult search) {
        if (job.cache() != null) {
            var previous = Optional.ofNullable(job.cache().selectedCandidate());
            if (previous.isPresent()) {
                String signature = signature(previous.get());
                var matches = search.candidates().stream().filter(c -> signature(c).equals(signature)).toList();
                if (matches.size() == 1) return RouteEvidenceAssembler.key(matches.get(0));
            }
            return "previous-unavailable";
        }
        return RouteEvidenceAssembler.key(search.candidates().get(0));
    }

    private String signature(RouteCandidate candidate) {
        return store.write(candidate.segments().stream().map(s -> Arrays.asList(s.mode(), s.providerRouteKey(),
                s.fromName(), s.toName(), s.stopNames(), s.vehicles(), s.geometry())).toList());
    }

    /**
     * 통지 여부는 행동 차이로 정한다. 같은 분·경로의 제안을 계산시각마다 다른 키로 만들지 않는다.
     * 초기 정상 결과는 푸시하지 않는다. 전송은 별도 dispatcher가 저장 성공 후 담당한다.
     */
    private NotificationCommand notification(ScheduleEvaluationStore.Job job, Outcome result, Outcome previous) {
        NotificationType type;
        String body;
        Result proposed = result.suggestion().proposed();
        if (proposed != null && proposed.departureAt().isBefore(job.departure())) {
            type = NotificationType.EARLY_DEPART;
            body = "목표 도착이 어려울 수 있어 " + proposed.departureAt().atZone(ScheduleEvaluationStore.KST).toLocalTime()
                    + " 출발을 제안합니다. 배차와 실제 도착은 달라질 수 있습니다.";
        } else if (proposed != null && !proposed.journeyKey().equals(result.selectedKey())) {
            type = NotificationType.ROUTE_CHANGE; body = "다른 경로의 예상 도착이 더 빠릅니다. 앱에서 경로와 근거를 확인해 주세요.";
        } else if (result.selected().assessment() == Assessment.LATE_ESTIMATE || result.selected().assessment() == Assessment.BLOCKED) {
            type = NotificationType.ON_TIME_UNLIKELY; body = "현재 경로로 목표시간 내 도착이 어려울 수 있습니다. 최신 예측과 대안을 확인해 주세요.";
        } else if (previous != null && result.selected().assessment() == Assessment.INSUFFICIENT_DATA) {
            type = NotificationType.DATA_STALE; body = "최신 교통 정보가 부족해 도착 예측을 확인하기 어렵습니다.";
        } else if (!job.departure().isBefore(result.checkedAt()) && Duration.between(result.checkedAt(), job.departure()).toMinutes() <= 5) {
            type = NotificationType.DEPART_SOON; body = "입력하신 출발시각이 다가옵니다. 최신 교통 상황을 확인해 주세요.";
        } else return null;
        String action = job.scheduleId() + ":" + job.version() + ":" + type + ":"
                + (proposed == null ? "none" : proposed.journeyKey() + ":" + proposed.departureAt().getEpochSecond() / 60);
        return new NotificationCommand(job.userId(), job.scheduleId(), null, type, "일정 이동 안내", body, digest(action));
    }

    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("알림 식별자 생성 실패"); }
    }
}
