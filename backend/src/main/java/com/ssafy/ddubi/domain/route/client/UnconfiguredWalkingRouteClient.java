package com.ssafy.ddubi.domain.route.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 실제 제휴 schema·service 권한을 확보한 어댑터가 없으면 운영 도보 호출을 열지 않는다. */
@Configuration
public class UnconfiguredWalkingRouteClient {
    @Bean
    @ConditionalOnMissingBean(WalkingRouteClient.class)
    public WalkingRouteClient walkingRouteClient() {
        return request -> new WalkingRouteClient.Evidence(null,null,"KAKAO_AFFILIATE_WALKING","WALK_ADAPTER_NOT_CONFIGURED");
    }
}
