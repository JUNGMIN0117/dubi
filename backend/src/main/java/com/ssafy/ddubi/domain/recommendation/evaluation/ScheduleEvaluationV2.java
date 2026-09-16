package com.ssafy.ddubi.domain.recommendation.evaluation;

import java.time.*;
import java.util.*;

/**
 * FE 공통 schedule-evaluation-v2 DTO. 조회 전용 variant에서는 scheduleId/inputVersion/DB routeId가 null이다.
 * source가 미확보된 버전은 추측하지 않고 missingInputs에 보존한다. preview는 저장 가능하지만 수락 불가다.
 */
public record ScheduleEvaluationV2(String schemaVersion, String variant, String evaluationId, Long scheduleId,
        Long inputVersion, long eventRevision, String state, String decision, String triggerReason,
        Instant userInputDepartAt, Instant plannedDepartAt, Instant effectiveDepartAt, Instant arriveBy,
        Instant originalEstimatedArrivalAt, Instant targetArriveAt, int appliedBufferSeconds,
        Instant proposedDepartAt, Instant estimatedArrivalAt, Long durationSeconds, Long latenessSeconds,
        Boolean arrivesBeforeSchedule, Boolean meetsAdjustedTarget, boolean departureMissed,
        Long selectedRouteId, Long proposedRouteId, Quality quality, ConditionalDepartureEvaluator.Search search,
        String bestKnownDecision, List<Cause> causes, Instant computedAt, Instant asOf, Instant validUntil,
        Instant nextEvaluationAt, Map<String,String> versions,
        List<com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Leg> segments) {
    /** 사건 자체와 경로 영향이 확인된 경우만 confirmed=true다. 단순 위험 신호를 지연 원인으로 단정하지 않는다. */
    public record Cause(String eventId,String source,List<String> segmentIds,String effect,boolean confirmed) { }
    public record Quality(boolean preview, boolean productionEligible, boolean realtimeApplied, boolean walkingApiApplied,
            String walkingApiCoverage, String costCoverage, List<String> missingInputs, List<String> fallbackReasons) { }

    /** 평가의 실제 적용 근거에서 품질을 만든다. 도보 API 미확보는 성공으로 표시하지 않는다. */
    public static ScheduleEvaluationV2 from(ConditionalDepartureEvaluator.Evaluation e, String id, Long schedule,
            Long revision, long eventRevision, String trigger, Instant userInput, Instant planned, Instant deadline,
            Instant now, Instant validUntil, Instant nextAt, Long selectedRouteId, Long proposedRouteId,
            List<String> missing,RouteEvidenceAssembler.Snapshot snapshot, List<com.ssafy.ddubi.domain.route.model.RouteCandidate> routes) {
        var p=e.proposed();
        Instant eta=p==null?null:p.estimatedArrivalAt();
        var comparison=e.proposedComparison();
        var absent=new ArrayList<String>(missing);
        absent.add("CROSSWALK_VERSION_NOT_REPORTED"); absent.add("FEATURE_SNAPSHOT_VERSION_NOT_REPORTED");
        var reasons=new ArrayList<String>(missing);
        if(p!=null) reasons.addAll(p.reasons());
        if(e.walking()!=null) reasons.addAll(e.walking().fallbackReasons());
        var legs=e.walking()==null||p==null?List.<com.ssafy.ddubi.domain.route.client.WalkingEnrichmentService.Leg>of():
                e.walking().legs().stream().filter(l->l.candidateKey().equals(p.journeyKey())).toList();
        boolean access=legs.stream().anyMatch(l->l.legRole().equals("ACCESS")&&l.durationSource().equals("KAKAO_AFFILIATE_WALKING"));
        boolean egress=legs.stream().anyMatch(l->l.legRole().equals("EGRESS")&&l.durationSource().equals("KAKAO_AFFILIATE_WALKING"));
        boolean realtime=comparison.waitPredictions().stream().anyMatch(w -> w.applied() && w.eventId()!=null)
                || comparison.subwayPredictions().stream().anyMatch(s -> s.applied() && s.estimate().arrivalEventId()!=null);
        var causes=new ArrayList<Cause>();
        var checked=e.originalComparison().candidates().stream().map(MlRoutePreview.Candidate::result).toList();
        var impacted=new HashSet<String>();
        checked.forEach(r->r.reasons().stream().filter(x->x.startsWith("CLOSURE:")).forEach(x->impacted.add(x.substring(8))));
        if(snapshot!=null) snapshot.states().forEach((segment,node)->{
            String event=node.path("eventId").asText();
            if(!event.isBlank()&&impacted.contains(event)&&!node.path("source").asText().isBlank())
                causes.add(new Cause(event,node.path("source").asText(),List.of(segment),"BLOCKED",true));
        });
        if(snapshot!=null) for(var info:snapshot.roads().values()) for(var path:info.path("paths")) for(var event:path.path("events")) {
            String idValue=event.path("eventId").asText();
            if(!idValue.isBlank()&&checked.stream().anyMatch(r->r.reasons().contains("ROAD_POTENTIAL_IMPACT:"+idValue))
                    && causes.stream().noneMatch(c->c.eventId().equals(idValue)))
                causes.add(new Cause(idValue,event.path("source").asText("ROAD_FEED"),List.of(info.path("segmentId").asText()),"POTENTIAL_IMPACT",false));
        }
        var versions=new LinkedHashMap<String,String>(); versions.put("policy",ConditionalDeparturePolicy.VERSION);
        if(routes!=null) routes.stream().flatMap(r->r.segments().stream()).map(x->x.mapping()).filter(Objects::nonNull)
                .map(x->x.masterDataVersion()).filter(Objects::nonNull).distinct().reduce((a,b)->a.equals(b)?a:"MULTIPLE")
                .ifPresent(v->versions.put("master",v));
        if(!versions.containsKey("master")) absent.add("MASTER_VERSION_UNAVAILABLE");
        comparison.predictions().stream().map(x->x.estimate().modelVersion()).filter(Objects::nonNull).distinct()
                .reduce((a,b)->a.equals(b)?a:"MIXED_REJECTED").ifPresent(v->versions.put("model",v));
        comparison.subwayPredictions().stream().map(x->x.estimate().timetableVersion()).filter(Objects::nonNull).distinct()
                .reduce((a,b)->a.equals(b)?a:"MIXED_REJECTED").ifPresent(v->versions.put("timetable",v));
        return new ScheduleEvaluationV2("schedule-evaluation-v2",schedule==null?"READ_ONLY":"SCHEDULE",id,schedule,
                revision,eventRevision,"READY",e.decision().name(),trigger,userInput,planned,planned.isBefore(now)?now:planned,
                deadline,e.original()==null?null:e.original().estimatedArrivalAt(),e.targetArriveAt(),e.appliedBufferSeconds(),
                p==null?null:p.departureAt(),eta,eta==null?null:Duration.between(p.departureAt(),eta).getSeconds(),
                eta==null?null:Math.max(0,Duration.between(deadline,eta).getSeconds()),eta==null?null:eta.isBefore(deadline),
                e.targetArriveAt()==null||eta==null?null:!eta.isAfter(e.targetArriveAt()),e.departureMissed(),selectedRouteId,proposedRouteId,
                new Quality(true,false,realtime,access||egress,access&&egress?"BOTH":access?"ACCESS_ONLY":egress?"EGRESS_ONLY":"NONE",p==null||p.steps().stream().anyMatch(x->x.key().equals("provider-total"))?"UNKNOWN":"PARTIAL",List.copyOf(absent),reasons.stream().distinct().toList()),
                e.search(),e.bestKnownDecision()==null?null:e.bestKnownDecision().name(),List.copyOf(causes),now,now,validUntil,nextAt,Map.copyOf(versions),legs);
    }
}
