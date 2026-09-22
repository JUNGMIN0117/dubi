package com.ssafy.ddubi.domain.recommendation.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GMS 추천 카드 문장 생성 설정. API 키는 환경변수로만 받고 응답·로그에 노출하지 않는다.
 *
 * @param dailyCallLimit 하루(KST) 동안 GMS 를 실제로 부르는 최대 횟수. 넘으면 그날은 서버 기본 문장만 쓴다. 2026-09-22 에 시간 초과로
 *     버려지는 호출이 반복돼 하루에 크레딧 6 만이 나갔다. 문장 하나에 크레딧을 얼마나 쓰든 이 수를 넘지 못하게 하는 마지막 안전장치다.
 */
@ConfigurationProperties(prefix = "ddubi.recommendation-guide")
public record RecommendationGuideProperties(
        boolean enabled,
        String baseUrl,
        String path,
        String apiKey,
        String model,
        int timeoutMillis,
        int dailyCallLimit) {}
