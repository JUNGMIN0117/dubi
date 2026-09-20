package com.ssafy.ddubi.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 서비스 권역 변경이 계산식 유무와 무관하게 진입 단계에서 회귀하지 않는지 검증한다. */
class SupportedServiceAreaTest {

    @Test
    void acceptsCapitalAreaAndBusanButRejectsUnsupportedRegion() {
        assertThat(SupportedServiceArea.contains(37.5665, 126.9780)).isTrue(); // 서울
        assertThat(SupportedServiceArea.contains(37.2636, 127.0286)).isTrue(); // 경기
        assertThat(SupportedServiceArea.contains(37.4563, 126.7052)).isTrue(); // 인천
        assertThat(SupportedServiceArea.contains(35.1796, 129.0756)).isTrue(); // 부산
        assertThat(SupportedServiceArea.contains(36.3504, 127.3845)).isFalse(); // 대전
    }

    @Test
    void rejectsNullAndNonFiniteCoordinates() {
        assertThat(SupportedServiceArea.contains(null, BigDecimal.ONE)).isFalse();
        assertThat(SupportedServiceArea.contains(Double.NaN, 127.0)).isFalse();
        assertThat(SupportedServiceArea.contains(37.5, Double.POSITIVE_INFINITY)).isFalse();
    }

    @Test
    void exposesSeparateSearchRectangles() {
        assertThat(SupportedServiceArea.areas())
                .extracting(SupportedServiceArea.Area::code)
                .containsExactly("CAPITAL", "BUSAN");
        assertThat(SupportedServiceArea.areas())
                .extracting(SupportedServiceArea.Area::kakaoRect)
                .containsExactly("125.8,36.85,127.9,38.3", "128.7,34.8,129.4,35.45");
    }
}
