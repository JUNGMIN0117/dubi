package com.ssafy.ddubi.domain.route.mapping;

/**
 * 공식 지하철 노선의 한 방향 안에서 순서가 확정된 역이다.
 *
 * @param stationId 서울시 등 공식 제공자가 발급한 역 ID
 * @param stationName 공식 역명
 * @param sequence 해당 방향의 운행 순번
 */
public record SubwayStationMaster(
        String stationId,
        String stationName,
        int sequence
) {
}
