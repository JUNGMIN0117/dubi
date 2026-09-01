package com.ssafy.ddubi.domain.route.mapping;

import com.ssafy.ddubi.domain.route.model.Coordinate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusSegmentMatcherTest {

    @Test
    void mapsUniqueRouteAndExpandsEveryAdjacentStopSection() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "107000008",
                "422",
                stop("S17", "성남역", 17, 127.1180, 37.3940),
                stop("S18", "중간정류장A", 18, 127.1200, 37.3960),
                stop("S19", "중간정류장B", 19, 127.1220, 37.3980),
                stop("S20", "성남시청전면", 20, 127.1240, 37.4000)
        )));

        BusMappingResult result = matcher.match(new BusMappingRequest(
                List.of("422번(일반)"),
                "성남역(판교방면)",
                "성남시청전면",
                List.of("성남역", "중간정류장A", "중간정류장B", "성남시청전면"),
                List.of(
                        new Coordinate(127.1180, 37.3940),
                        new Coordinate(127.1240, 37.4000)
                )
        ));

        assertThat(result.status()).isEqualTo(BusMappingStatus.EXACT);
        assertThat(result.officialRouteId()).isEqualTo("107000008");
        assertThat(result.fromStopId()).isEqualTo("S17");
        assertThat(result.toStopId()).isEqualTo("S20");
        assertThat(result.startOrder()).isEqualTo(17);
        assertThat(result.endOrder()).isEqualTo(20);
        assertThat(result.direction()).isEqualTo("FORWARD");
        assertThat(result.matchScore()).isEqualTo(1.0);
        assertThat(result.segments()).extracting(MappedBusSegment::fromStopId)
                .containsExactly("S17", "S18", "S19");
        assertThat(result.segments()).extracting(MappedBusSegment::toStopId)
                .containsExactly("S18", "S19", "S20");
    }

    @Test
    void neverChoosesArbitrarilyWhenTopCandidatesAreTied() {
        BusRouteMaster first = route(
                "ROUTE-A", "100", stop("A1", "출발", 1), stop("A2", "도착", 2));
        BusRouteMaster second = route(
                "ROUTE-B", "100", stop("B1", "출발", 1), stop("B2", "도착", 2));
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(first, second));

        BusMappingResult result = matcher.match(request("100", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(BusMappingStatus.AMBIGUOUS);
        assertThat(result.failureReason()).isEqualTo("MULTIPLE_TOP_CANDIDATES");
        assertThat(result.officialRouteId()).isNull();
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void returnsPartialWhenRouteExistsButStopsOrOrderCannotBeConfirmed() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "ROUTE-A", "100", stop("A1", "출발", 1), stop("A2", "도착", 2))));

        BusMappingResult missingStop = matcher.match(
                request("100", "출발", "없는정류장", "출발", "없는정류장"));
        BusMappingResult reverseOrder = matcher.match(
                request("100", "도착", "출발", "도착", "출발"));

        assertThat(missingStop.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(missingStop.officialRouteId()).isEqualTo("ROUTE-A");
        assertThat(missingStop.failureReason()).isEqualTo("STOP_OR_ORDER_NOT_FOUND");
        assertThat(reverseOrder.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(reverseOrder.segments()).isEmpty();
    }

    @Test
    void returnsPartialWhenGeometryRunsInTheOppositeDirection() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "ROUTE-A",
                "100",
                stop("A1", "출발", 1, 127.0, 37.0),
                stop("A2", "도착", 2, 128.0, 38.0)
        )));

        BusMappingResult result = matcher.match(new BusMappingRequest(
                List.of("100"),
                "출발",
                "도착",
                List.of("출발", "도착"),
                List.of(new Coordinate(128.0, 38.0), new Coordinate(127.0, 37.0))
        ));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("GEOMETRY_DIRECTION_MISMATCH");
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void returnsUnmappedWhenOfficialRouteNameDoesNotExist() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "ROUTE-A", "100", stop("A1", "출발", 1), stop("A2", "도착", 2))));

        BusMappingResult result = matcher.match(request("999", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(BusMappingStatus.UNMAPPED);
        assertThat(result.failureReason()).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void doesNotCollapseHyphenatedRouteNamesIntoDifferentRouteNumbers() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(
                route("ROUTE-A", "119-1", stop("A1", "출발", 1), stop("A2", "도착", 2)),
                route("ROUTE-B", "1191", stop("B1", "출발", 1), stop("B2", "도착", 2))
        ));

        BusMappingResult result = matcher.match(
                request("119-1", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(BusMappingStatus.EXACT);
        assertThat(result.officialRouteId()).isEqualTo("ROUTE-A");
    }

    @Test
    void appliesOnlyExplicitlyVerifiedAliasesBeforeMatching() {
        TransitNameNormalizer normalizer = new TransitNameNormalizer(
                Map.of("9003", "9003성남"),
                Map.of("서울역", "서울")
        );
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "ROUTE-A", "9003성남", stop("A1", "서울", 1), stop("A2", "도착", 2)
        )), normalizer);

        BusMappingResult result = matcher.match(
                request("9003", "서울역", "도착", "서울역", "도착"));

        assertThat(result.status()).isEqualTo(BusMappingStatus.EXACT);
        assertThat(result.officialRouteId()).isEqualTo("ROUTE-A");
    }

    @Test
    void returnsPartialWhenIntermediateStopsDoNotFollowTheOfficialOrder() {
        BusSegmentMatcher matcher = new BusSegmentMatcher(List.of(route(
                "ROUTE-A",
                "100",
                stop("A1", "출발", 1),
                stop("A2", "중간A", 2),
                stop("A3", "중간B", 3),
                stop("A4", "도착", 4)
        )));

        BusMappingResult result = matcher.match(request(
                "100", "출발", "도착", "출발", "중간B", "중간A", "도착"));

        assertThat(result.status()).isEqualTo(BusMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("PASSING_STOPS_NOT_CONFIRMED");
        assertThat(result.passingMatchRatio()).isLessThan(0.8);
        assertThat(result.segments()).isEmpty();
    }

    private BusMappingRequest request(
            String routeName,
            String from,
            String to,
            String... passingStops
    ) {
        return new BusMappingRequest(
                List.of(routeName), from, to, List.of(passingStops), List.of());
    }

    private BusRouteMaster route(
            String routeId,
            String routeName,
            BusStopMaster... stops
    ) {
        return new BusRouteMaster(routeId, routeName, List.of(stops));
    }

    private BusStopMaster stop(String stopId, String stopName, int sequence) {
        return new BusStopMaster(stopId, stopName, sequence, null, null);
    }

    private BusStopMaster stop(
            String stopId,
            String stopName,
            int sequence,
            double longitude,
            double latitude
    ) {
        return new BusStopMaster(stopId, stopName, sequence, longitude, latitude);
    }
}
