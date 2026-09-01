package com.ssafy.ddubi.domain.route.mapping;

import java.util.Comparator;
import java.util.List;

/**
 * 공식 지하철 호선의 한 운행 방향과 전체 역 순서를 묶은 읽기 모델이다.
 * 같은 subwayId라도 상행·하행을 별도 객체로 제공해야 역 순서로 방향을 판별할 수 있다.
 * 순환선은 실제 운행 방향별 순환 순서를 각각 제공하며, 두 후보가 모두 성립하면 매칭기는
 * 임의 선택하지 않고 AMBIGUOUS를 반환한다.
 */
public record SubwayLineMaster(
        String subwayId,
        String lineName,
        String direction,
        List<SubwayStationMaster> stations
) {
    public SubwayLineMaster {
        // 입력 파일의 행 순서와 무관하게 공식 sequence를 유일한 운행 순서 기준으로 사용한다.
        stations = stations == null
                ? List.of()
                : stations.stream()
                .sorted(Comparator.comparingInt(SubwayStationMaster::sequence))
                .toList();
    }
}
