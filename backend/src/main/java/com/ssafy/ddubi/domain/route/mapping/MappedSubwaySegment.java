package com.ssafy.ddubi.domain.route.mapping;

/**
 * 공식 지하철 노선에서 실제로 서로 이웃한 두 역 사이의 구간이다.
 * 카카오 승차→하차 SUBWAY step 하나는 이 객체 여러 개로 전개된다.
 * 공용 segment_id 문자열은 계약이 확정된 뒤 별도 Encoder가 공식 ID 조합으로 생성한다.
 */
public record MappedSubwaySegment(
        int sequence,
        String subwayId,
        String lineName,
        String direction,
        String fromStationId,
        String toStationId,
        int fromOrder,
        int toOrder
) {
}
