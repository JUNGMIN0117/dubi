package com.ssafy.ddubi.domain.recommendation.service;

import java.time.Instant;

/**
 * 구간 하나의 산정 결과. <b>왜 이 숫자가 나왔는지</b>를 같이 들고 다닌다 —
 * 추천 화면의 "이유" 와 재현성 스냅샷이 이 값으로 만들어진다.
 *
 * @param baseSeconds       출처에서 얻은 기본 소요시간
 * @param adjustmentSeconds 실시간 상태로 더한 지연(초). 보정을 안 썼으면 0
 * @param note              폴백했다면 그 이유. 정상 경로면 null
 */
public record SegmentEstimate(
        int sequence,
        String segmentId,
        Instant enteredAt,
        long baseSeconds,
        EstimateSource source,
        long adjustmentSeconds,
        String note
) {

    public long totalSeconds() {
        return baseSeconds + adjustmentSeconds;
    }

    public boolean isAdjusted() {
        return adjustmentSeconds != 0;
    }
}
