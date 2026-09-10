package com.ssafy.ddubi.domain.recommendation.service;

/** 구간 소요시간을 어디서 얻었는가. 추천 응답과 {@code reasons} JSON 에 그대로 남는다. */
public enum EstimateSource {

    /** 예측 서비스(Python). 진입 예상시각 기준의 버스 구간 소요시간. */
    PREDICTION,

    /** {@code baseline_serving} 의 평소값. 예측이 없거나 그 구간을 못 맞출 때. */
    BASELINE,

    /**
     * 외부 경로 API 가 준 구간 시간. 마지막 보루다.
     *
     * <p>도보는 <b>항상</b> 여기다 — ML 결정 문서(2026-09-08)가 일반 도보를 학습 대상에서
     * 빼고 경로 API 시간을 쓰기로 확정했다. 폴백이 아니라 정해진 출처다.
     */
    ROUTE_API
}
