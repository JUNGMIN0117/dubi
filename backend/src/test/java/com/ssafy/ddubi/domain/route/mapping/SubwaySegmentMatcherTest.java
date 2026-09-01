package com.ssafy.ddubi.domain.route.mapping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubwaySegmentMatcherTest {

    @Test
    void mapsUniqueDirectionAndExpandsEveryAdjacentStationSection() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(
                line("1002", "2호선", "INNER",
                        station("222", "강남", 1),
                        station("220", "선릉", 2),
                        station("216", "잠실", 3)),
                line("1002", "2호선", "OUTER",
                        station("216", "잠실", 1),
                        station("220", "선릉", 2),
                        station("222", "강남", 3))
        ));

        SubwayMappingResult result = matcher.match(new SubwayMappingRequest(
                List.of("수도권 02호선"),
                "강남역",
                "잠실역",
                List.of("강남역", "선릉역", "잠실역")
        ));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.EXACT);
        assertThat(result.officialSubwayId()).isEqualTo("1002");
        assertThat(result.fromStationId()).isEqualTo("222");
        assertThat(result.toStationId()).isEqualTo("216");
        assertThat(result.direction()).isEqualTo("INNER");
        assertThat(result.matchScore()).isEqualTo(1.0);
        assertThat(result.segments()).extracting(MappedSubwaySegment::fromStationId)
                .containsExactly("222", "220");
        assertThat(result.segments()).extracting(MappedSubwaySegment::toStationId)
                .containsExactly("220", "216");
    }

    @Test
    void neverChoosesArbitrarilyWhenDirectionsRemainTied() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(
                line("1002", "2호선", "INNER",
                        station("I1", "출발", 1), station("I2", "도착", 2)),
                line("1002", "2호선", "OUTER",
                        station("O1", "출발", 1), station("O2", "도착", 2))
        ));

        SubwayMappingResult result = matcher.match(
                request("2호선", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.AMBIGUOUS);
        assertThat(result.failureReason()).isEqualTo("MULTIPLE_TOP_CANDIDATES");
        assertThat(result.officialSubwayId()).isNull();
        assertThat(result.direction()).isNull();
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void returnsPartialWhenLineExistsButStationsOrOrderCannotBeConfirmed() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(line(
                "1002", "2호선", "INNER",
                station("S1", "출발", 1), station("S2", "도착", 2))));

        SubwayMappingResult missingStation = matcher.match(
                request("2호선", "출발", "없는역", "출발", "없는역"));
        SubwayMappingResult reverseOrder = matcher.match(
                request("2호선", "도착", "출발", "도착", "출발"));

        assertThat(missingStation.status()).isEqualTo(SubwayMappingStatus.PARTIAL);
        assertThat(missingStation.officialSubwayId()).isEqualTo("1002");
        assertThat(missingStation.failureReason()).isEqualTo("STATION_OR_ORDER_NOT_FOUND");
        assertThat(reverseOrder.status()).isEqualTo(SubwayMappingStatus.PARTIAL);
        assertThat(reverseOrder.segments()).isEmpty();
    }

    @Test
    void returnsPartialWhenIntermediateStationsDoNotFollowOfficialOrder() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(line(
                "1002", "2호선", "INNER",
                station("S1", "출발", 1),
                station("S2", "중간A", 2),
                station("S3", "중간B", 3),
                station("S4", "도착", 4))));

        SubwayMappingResult result = matcher.match(request(
                "2호선", "출발", "도착", "출발", "중간B", "중간A", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.PARTIAL);
        assertThat(result.failureReason()).isEqualTo("PASSING_STATIONS_NOT_CONFIRMED");
        assertThat(result.passingMatchRatio()).isLessThan(0.8);
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void returnsUnmappedWhenOfficialLineNameDoesNotExist() {
        SubwaySegmentMatcher matcher = new SubwaySegmentMatcher(List.of(line(
                "1002", "2호선", "INNER",
                station("S1", "출발", 1), station("S2", "도착", 2))));

        SubwayMappingResult result = matcher.match(
                request("9호선", "출발", "도착", "출발", "도착"));

        assertThat(result.status()).isEqualTo(SubwayMappingStatus.UNMAPPED);
        assertThat(result.failureReason()).isEqualTo("LINE_NOT_FOUND");
    }

    private SubwayMappingRequest request(
            String lineName,
            String from,
            String to,
            String... passingStations
    ) {
        return new SubwayMappingRequest(
                List.of(lineName), from, to, List.of(passingStations));
    }

    private SubwayLineMaster line(
            String subwayId,
            String lineName,
            String direction,
            SubwayStationMaster... stations
    ) {
        return new SubwayLineMaster(subwayId, lineName, direction, List.of(stations));
    }

    private SubwayStationMaster station(String stationId, String stationName, int sequence) {
        return new SubwayStationMaster(stationId, stationName, sequence);
    }
}
