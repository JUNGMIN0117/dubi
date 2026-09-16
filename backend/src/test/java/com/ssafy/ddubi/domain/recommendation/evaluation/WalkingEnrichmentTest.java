package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.route.client.*;
import com.ssafy.ddubi.domain.route.model.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** T27~T34: 도보 방향·EXACT 좌표·교체 비용·장애 fallback을 합성 마스터로 재현한다. */
class WalkingEnrichmentTest {
    final Instant now=Instant.parse("2026-09-16T00:00:00Z");
    final Coordinate origin=new Coordinate(127,37.5),board=new Coordinate(127.001,37.501),
            alight=new Coordinate(127.002,37.502),destination=new Coordinate(127.003,37.503);
    RouteSegment walk(int sequence) { return new RouteSegment(sequence,TransportMode.WALK,null,null,null,"A","B",50,60,List.of(),List.of(),List.of(),null); }
    RouteCandidate route(long residual) {
        var original=RouteEvidenceAssemblerTest.candidate(0,600,0).segments().get(0);
        var ride=new RouteSegment(2,original.mode(),original.segmentId(),original.providerRouteKey(),null,"A","B",1000,600,
                original.stopNames(),original.vehicles(),List.of(board,alight),original.mapping());
        return new RouteCandidate(0,RouteType.BUS,1100,720+residual,0,null,0,residual,List.of(walk(1),ride,walk(3)));
    }
    List<WalkingEndpointResolver.MasterPoint> points(String meaning) {
        return List.of(new WalkingEndpointResolver.MasterPoint("2","master",board,meaning,"OFFICIAL_FIXTURE"),
                new WalkingEndpointResolver.MasterPoint("3","master",alight,meaning,"OFFICIAL_FIXTURE"));
    }
    WalkingEnrichmentService service(WalkingRouteClient client) { return new WalkingEnrichmentService(client,new ObjectMapper(),true,4,20,""); }
    @Test void accessAndEgressHaveCorrectDirectionAndReplaceInsteadOfAdding() {
        var seen=new ArrayList<WalkingRouteClient.Request>();
        var output=service(r->{seen.add(r);return new WalkingRouteClient.Evidence(120L,now.plusSeconds(60),"fixture",null);})
                .prepare(List.of(route(0)),origin,destination,now,points("BUS_STOP"));
        assertThat(seen).hasSize(2);assertThat(seen.get(0).origin()).isEqualTo(origin);assertThat(seen.get(0).destination()).isEqualTo(board);
        assertThat(seen.get(1).origin()).isEqualTo(alight);assertThat(seen.get(1).destination()).isEqualTo(destination);
        assertThat(output.candidates().get(0).totalDurationSeconds()).isEqualTo(840);
        assertThat(output.evidence().legs()).extracting(WalkingEnrichmentService.Leg::legRole).containsExactly("ACCESS","RIDE","EGRESS");
    }
    @Test void unknownCostCoverageDoesNotCallOrAddToProviderTotal() {
        var output=service(r->{throw new AssertionError("호출 금지");}).prepare(List.of(route(30)),origin,destination,now,points("BUS_STOP"));
        assertThat(output.candidates().get(0).totalDurationSeconds()).isEqualTo(750);
        assertThat(output.evidence().fallbackReasons()).contains("WALK_COST_COVERAGE_UNRESOLVED");
    }
    @Test void stationCenterIsNotAnEntrance() {
        var output=service(r->{throw new AssertionError("호출 금지");}).prepare(List.of(route(0)),origin,destination,now,points("STATION_CENTER"));
        assertThat(output.evidence().fallbackReasons()).contains("STATION_CENTER_ONLY");
    }
    @Test void provider403429TimeoutRemainCandidateLocalFallback() {
        for(String reason:List.of("WALK_HTTP_403","WALK_HTTP_429","WALK_TIMEOUT")) {
            var output=service(r->new WalkingRouteClient.Evidence(null,null,null,reason)).prepare(List.of(route(0)),origin,destination,now,points("BUS_STOP"));
            assertThat(output.candidates().get(0).totalDurationSeconds()).isEqualTo(720);
            assertThat(output.evidence().fallbackReasons()).contains(reason);
        }
    }
    @Test void conflictingCoordinateSkipsProvider() {
        var points=List.of(new WalkingEndpointResolver.MasterPoint("2","master",destination,"BUS_STOP","fixture"));
        var output=service(r->{throw new AssertionError("호출 금지");}).prepare(List.of(route(0)),origin,destination,now,points);
        assertThat(output.evidence().fallbackReasons()).contains("WALK_ENDPOINT_UNVERIFIED");
    }
    @Test void middleWalkRemainsTransfer() {
        var steps=List.of(route(0).segments().get(1),walk(4),new RouteSegment(5,TransportMode.SUBWAY,null,null,null,"B","C",100,60,List.of(),List.of(),List.of(),null));
        assertThat(new RouteLegRoleClassifier().classify(steps).get(4)).isEqualTo(RouteLegRoleClassifier.Role.TRANSFER);
    }
}
