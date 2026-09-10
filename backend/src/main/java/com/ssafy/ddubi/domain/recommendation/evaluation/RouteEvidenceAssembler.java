package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import static com.ssafy.ddubi.domain.recommendation.evaluation.JourneyEvidence.*;

/**
 * 카카오 후보와 Spark 구간 상태를 한 번의 평가 증거로 고정한다. Redis는 읽기만 하며,
 * 미매핑·만료·형식 오류·연결 실패를 원본 fallback과 진단으로 남긴다. 현재 미승인 ML은 호출하지 않는다.
 * 대기 포함 여부가 확정되지 않은 카카오 step에는 도착 API의 대기를 임의 추가하지 않는다.
 */
@Component
public class RouteEvidenceAssembler {
    private static final String PREFIX = "segment:status:";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RouteEvidenceAssembler(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** 외부 원본을 노출하지 않는 고정 스냅샷. 검증된 상태 JSON과 오류 이유로 Replay할 수 있다. */
    public record Snapshot(Map<String, JsonNode> states, List<String> reasons) {
        public Snapshot { states = Map.copyOf(states); reasons = List.copyOf(reasons); }
    }

    /** 서버 매핑 결과에서 하나로 확정된 canonical 경로만 반환한다. 여러 버스 대안을 임의로 고르지 않는다. */
    public static List<MappedTransitSegment> edges(RouteSegment segment) {
        var mapping = segment.mapping();
        if (mapping == null) return List.of();
        if (mapping.status() == TransitMappingStatus.EXACT && !mapping.segments().isEmpty()) return mapping.segments();
        if (segment.mode() == TransportMode.BUS && mapping.busAlternatives().size() == 1
                && mapping.busAlternatives().get(0).candidates().size() == 1) {
            try { return BusCanonicalPath.select(mapping, 0, 0).edges(); }
            catch (IllegalArgumentException ignored) { return List.of(); }
        }
        return List.of();
    }

    /** Redis MGET 한 번으로 중복 ID를 합친다. 원본 예외에 URL/비밀정보가 있어도 응답·로그에 복제하지 않는다. */
    public Snapshot read(List<RouteCandidate> candidates, Instant asOf) {
        var ids = candidates.stream().flatMap(c -> c.segments().stream()).flatMap(s -> edges(s).stream())
                .map(MappedTransitSegment::segmentId).distinct().sorted().toList();
        var states = new LinkedHashMap<String, JsonNode>();
        var reasons = new ArrayList<String>();
        if (ids.isEmpty()) return new Snapshot(states, List.of("NO_VERIFIED_SEGMENT_MAPPING"));
        List<String> values;
        try { values = redis.opsForValue().multiGet(ids.stream().map(id -> PREFIX + id).toList()); }
        catch (RuntimeException failure) { return new Snapshot(states, List.of("REDIS_UNAVAILABLE")); }
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            try {
                String raw = values == null || values.size() <= i ? null : values.get(i);
                if (raw == null) { reasons.add(id + ":MISSING"); continue; }
                JsonNode node = json.readTree(raw);
                Instant computed = Instant.parse(node.path("computedAt").asText());
                Instant until = Instant.parse(node.path("validUntil").asText());
                String state = node.path("status").asText();
                if (!id.equals(node.path("segmentId").asText()) || computed.isAfter(asOf)
                        || !until.isAfter(asOf) || !until.isAfter(computed)
                        || !Set.of("normal", "slow", "delayed", "blocked", "unknown").contains(state)) {
                    reasons.add(id + ":STALE_OR_INVALID"); continue;
                }
                states.put(id, node);
            } catch (Exception invalid) { reasons.add(id + ":INVALID_PAYLOAD"); }
        }
        return new Snapshot(states, reasons);
    }

    /**
     * 카카오 총시간과 step 합의 차이는 위치 미확정 시간으로 한 번만 보존한다. 각 step 진입은
     * 이 잔여시간만큼의 불확실성을 가지며, 그 범위 전체를 덮는 관측만 사용한다.
     * step 합이 총시간을 초과하면 분해를 신뢰하지 않고 전체 API 예상시간으로 fallback한다.
     */
    public Journey assemble(RouteCandidate candidate, Snapshot snapshot, Instant asOf) {
        long sum = candidate.segments().stream().mapToLong(RouteSegment::durationSeconds).sum();
        long unassigned = candidate.totalDurationSeconds() - sum;
        if (unassigned < 0 || candidate.segments().isEmpty()) {
            return new Journey(key(candidate), List.of(new Leg("provider-total", Kind.PROVIDER_TOTAL, List.of(),
                    candidate.totalDurationSeconds(), null, null, List.of())));
        }
        var legs = new ArrayList<Leg>();
        for (RouteSegment segment : candidate.segments()) {
            var ids = edges(segment).stream().map(MappedTransitSegment::segmentId).toList();
            // 전체 edge의 실제 이동 관측이 있을 때만 이동 step을 통째로 교체한다. delaySeconds를 다시 더하지 않는다.
            // 응답에 분리되지 않은 대기/환승 합계는 뒤에서 한 번만 보존하고, 실제 진입은 [cursor,cursor+unassigned]로 본다.
            Long observed = null;
            Window usable = null;
            if (!ids.isEmpty() && segment.mode() != TransportMode.WALK) {
                double seconds = 0;
                Instant available = Instant.MIN, expires = Instant.MAX;
                boolean complete = true;
                for (String id : ids) {
                    JsonNode state = snapshot.states().get(id);
                    if (state == null || !Set.of("normal", "slow", "delayed").contains(state.path("status").asText())
                            || !state.path("observedSeconds").isNumber() || state.path("observationCount").asInt(0) < 1) {
                        complete = false; break;
                    }
                    double value = state.path("observedSeconds").asDouble();
                    if (!Double.isFinite(value) || value <= 0) { complete = false; break; }
                    try {
                        Instant computed = Instant.parse(state.path("computedAt").asText());
                        Instant observation = Instant.parse(state.path("observedAt").asText());
                        Instant until = Instant.parse(state.path("validUntil").asText());
                        if (observation.isAfter(computed) || observation.isBefore(asOf.minusSeconds(300))) { complete = false; break; }
                        if (computed.isAfter(available)) available = computed;
                        if (until.isBefore(expires)) expires = until;
                        seconds += value;
                    } catch (RuntimeException badTime) { complete = false; break; }
                }
                if (complete && seconds < Long.MAX_VALUE && expires.isAfter(available)) {
                    observed = (long) Math.ceil(seconds);
                    usable = new Window("SEGMENT_OBSERVATION", available, available, expires);
                }
            }
            legs.add(new Leg("step-" + segment.sequence(), segment.mode() == TransportMode.WALK ? Kind.WALK : Kind.RIDE,
                    ids, segment.durationSeconds(), observed, usable, List.of(), unassigned));
        }
        if (unassigned > 0) legs.add(new Leg("unassigned", Kind.UNASSIGNED, List.of(), unassigned, null, null, List.of()));
        return new Journey(key(candidate), legs);
    }

    /** 만료되지 않은 공식 blocked 판정만 통제로 변환한다. 미래에는 validUntil 바깥까지 연장하지 않는다. */
    public List<Closure> closures(Snapshot snapshot) {
        var closures = new ArrayList<Closure>();
        snapshot.states().forEach((id, state) -> {
            if ("blocked".equals(state.path("status").asText())) {
                Instant at = Instant.parse(state.path("computedAt").asText());
                closures.add(new Closure(state.path("eventId").asText(id + ":" + at), List.of(id),
                        new Window(state.path("source").asText("segment.status"), at, at,
                                Instant.parse(state.path("validUntil").asText()))));
            }
        });
        return List.copyOf(closures);
    }

    /** 영향 구간의 현재 악화는 시간 가산 대신 근거로 공개한다. 위치 미확정/미래 이용도 확정 지연으로 바꾸지 않는다. */
    public List<String> riskReasons(RouteCandidate candidate, Snapshot snapshot) {
        return candidate.segments().stream().flatMap(s -> edges(s).stream()).map(MappedTransitSegment::segmentId)
                .distinct().filter(snapshot.states()::containsKey).map(id -> id + ":" + snapshot.states().get(id).path("status").asText())
                .filter(s -> !s.endsWith(":normal")).toList();
    }

    public static String key(RouteCandidate candidate) { return "candidate-" + candidate.providerCandidateIndex(); }
}
