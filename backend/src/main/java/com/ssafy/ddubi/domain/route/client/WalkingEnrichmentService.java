package com.ssafy.ddubi.domain.route.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.model.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import static com.ssafy.ddubi.domain.route.model.RouteLegRoleClassifier.Role;

/**
 * 접근·이탈 WALK를 검증된 제휴 결과로 교체한다. 환승 WALK와 provider 총시간은 중복 가산하지 않는다.
 * 공식 좌표 artifact/권한/schema가 없는 기본 환경은 기존 WALK와 명시적 실패 근거를 반환한다.
 * 캐시는 한 평가 안에서만 유지하고 좌표 반올림/장기 TTL은 실측 전 적용하지 않는다.
 */
@Service
public class WalkingEnrichmentService {
    private final WalkingRouteClient client;
    private final boolean enabled;
    private final int budget;
    private final double maxMeters;
    private final List<WalkingEndpointResolver.MasterPoint> points;
    public WalkingEnrichmentService(WalkingRouteClient client,ObjectMapper json,
            @Value("${ddubi.walking.enabled:false}") boolean enabled,
            @Value("${ddubi.walking.max-calls:4}") int budget,
            @Value("${ddubi.walking.endpoint-max-meters:0}") double maxMeters,
            @Value("${ddubi.walking.master-coordinate-path:}") String path) {
        if(budget<0||budget>32||maxMeters<0||!Double.isFinite(maxMeters)) throw new IllegalArgumentException("도보 정책 범위 오류");
        this.client=client;this.enabled=enabled;this.budget=budget;this.maxMeters=maxMeters;
        List<WalkingEndpointResolver.MasterPoint> loaded=List.of();
        if(path!=null&&!path.isBlank()) try {
            if(Files.size(Path.of(path))>32*1024*1024) throw new IllegalArgumentException();
            try(var input=Files.newInputStream(Path.of(path))) {
                loaded=List.of(json.readValue(input,WalkingEndpointResolver.MasterPoint[].class));
            }
        } catch(Exception invalid) { loaded=List.of(); }
        points=List.copyOf(loaded);
    }
    public record Leg(String candidateKey,int sequence,String legRole,long durationSeconds,String durationSource,String endpointEvidence) { }
    public record Evidence(List<Leg> legs,List<String> fallbackReasons,int apiCalls) { }
    public record Prepared(List<RouteCandidate> candidates,Evidence evidence) { }

    public Prepared prepare(List<RouteCandidate> routes,Coordinate origin,Coordinate destination,Instant asOf) {
        return prepare(routes,origin,destination,asOf,points);
    }
    /** fake fixture도 운영과 같은 EXACT·비용 검사 경로를 통과한다. API 장애는 후보별로 격리한다. */
    public Prepared prepare(List<RouteCandidate> routes,Coordinate origin,Coordinate destination,Instant asOf,
            List<WalkingEndpointResolver.MasterPoint> master) {
        var output=new ArrayList<RouteCandidate>();var metadata=new ArrayList<Leg>();var reasons=new LinkedHashSet<String>();
        var cache=new HashMap<WalkingRouteClient.Request,WalkingRouteClient.Evidence>();
        int calls=0;
        for(var route:routes) {
            var roles=new RouteLegRoleClassifier().classify(route.segments());
            var transit=route.segments().stream().filter(s->s.mode()!=TransportMode.WALK).toList();
            boolean complete=route.unassignedDurationSeconds()==0 && route.totalDurationSeconds()==route.segments().stream().mapToLong(RouteSegment::durationSeconds).sum();
            long delta=0;var steps=new ArrayList<RouteSegment>();
            for(var step:route.segments()) {
                Role role=roles.get(step.sequence());long seconds=step.durationSeconds();String source="KAKAO_TRANSIT";String endpoint=null;
                if(role==Role.ACCESS||role==Role.EGRESS) {
                    boolean access=role==Role.ACCESS;
                    if(!enabled) endpoint="WALK_DISABLED";
                    else if(!complete||roles.values().stream().filter(r->r==role).count()!=1) endpoint="WALK_COST_COVERAGE_UNRESOLVED";
                    else {
                        var resolved=new WalkingEndpointResolver().resolve(access?transit.get(0):transit.get(transit.size()-1),access,master,maxMeters);
                        endpoint=resolved.evidence();
                        if(resolved.verified()) {
                            var request=new WalkingRouteClient.Request(access?origin:resolved.coordinate(),access?resolved.coordinate():destination,"DISTANCE","walk-endpoint-v1");
                            var found=cache.get(request);
                            if(found==null && calls<budget) {
                                calls++;
                                try { found=client.route(request); }
                                catch(RuntimeException failure) { found=new WalkingRouteClient.Evidence(null,null,"KAKAO_AFFILIATE_WALKING","WALK_PROVIDER_UNAVAILABLE"); }
                                if(found==null) found=new WalkingRouteClient.Evidence(null,null,null,"WALK_EMPTY_RESPONSE");
                                cache.put(request,found);
                            }
                            if(found!=null && found.seconds()!=null && found.seconds()>0 && found.seconds()<=86400
                                    && found.validUntil()!=null && found.validUntil().isAfter(asOf)) {
                                seconds=found.seconds();source="KAKAO_AFFILIATE_WALKING";
                            } else endpoint=found==null?"WALK_CALL_BUDGET_EXHAUSTED":found.reason()==null?"WALK_INVALID_RESPONSE":found.reason();
                        }
                    }
                    if(!"KAKAO_AFFILIATE_WALKING".equals(source)) reasons.add(endpoint);
                }
                delta+=seconds-step.durationSeconds();
                steps.add(new RouteSegment(step.sequence(),step.mode(),step.segmentId(),step.providerRouteKey(),step.guidance(),step.fromName(),step.toName(),step.distanceMeters(),seconds,step.stopNames(),step.vehicles(),step.geometry(),step.mapping()));
                metadata.add(new Leg("candidate-"+route.providerCandidateIndex(),step.sequence(),role.name(),seconds,source,endpoint));
            }
            output.add(new RouteCandidate(route.providerCandidateIndex(),route.type(),route.totalDistanceMeters(),route.totalDurationSeconds()+delta,
                    route.transfers(),route.fare(),route.unassignedDistanceMeters(),route.unassignedDurationSeconds(),steps));
        }
        return new Prepared(List.copyOf(output),new Evidence(List.copyOf(metadata),List.copyOf(reasons),calls));
    }
}
