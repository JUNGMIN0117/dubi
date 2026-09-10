package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.route.model.TransportMode;

/**
 * 산정기에 넣는 구간 하나.
 *
 * @param segmentId       공용 인접구간 ID. 도보이거나 매핑 실패면 null
 * @param routeApiSeconds 외부 경로 API 가 준 이 구간의 시간(초). <b>항상 있어야 한다</b> —
 *                        마지막 폴백이라 이게 없으면 그 구간은 계산 자체가 불가능하다
 * @param busKey          예측에 쓸 제공자 ID. 버스이고 매핑에 성공했을 때만 채운다
 */
public record SegmentEstimateInput(
        int sequence,
        TransportMode mode,
        String segmentId,
        long routeApiSeconds,
        BusProviderKey busKey
) {

    /** 예측을 시도해 볼 수 있는 구간인가. 모델이 커버하는 건 매핑된 버스 구간뿐이다. */
    public boolean isPredictable() {
        return mode == TransportMode.BUS && busKey != null && segmentId != null;
    }
}
