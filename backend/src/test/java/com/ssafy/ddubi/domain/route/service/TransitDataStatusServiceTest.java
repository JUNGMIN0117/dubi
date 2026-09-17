package com.ssafy.ddubi.domain.route.service;

import com.ssafy.ddubi.domain.route.mapping.*;
import com.ssafy.ddubi.domain.recommendation.evaluation.SubwayTimetable;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static com.ssafy.ddubi.domain.route.mapping.MetropolitanBusFixtures.*;

/** 파일 보유/정적 매핑과 실시간·모델 준비를 하나의 성공 상태로 합치지 않는지 확인한다. */
class TransitDataStatusServiceTest {
    @Test void loadedBusAndAbsentSubwayAreReportedSeparatelyWithoutExposingConfiguration() {
        var subway=mock(SeoulTransitMasterDataProvider.class);when(subway.activeVersion()).thenReturn(Optional.empty());
        var timetable=new SubwayTimetable(new com.fasterxml.jackson.databind.ObjectMapper(),"");
        var service=new TransitDataStatusService(new MetropolitanBusCatalog(master(List.of(regular()))),subway,timetable,
                "http://private-host:8000",false,false);
        var result=service.status(Instant.parse("2026-09-17T00:00:00Z"));
        assertThat(result.get("bus").toString()).contains("TEST","LOADED","SEOUL_BUS");
        assertThat(result.get("subway").toString()).contains("MASTER_DATA_NOT_CONFIGURED");
        assertThat(result.get("subwayTimetable").toString()).contains("SUBWAY_TIMETABLE_NOT_CONFIGURED");
        assertThat(result.get("busMl").toString()).contains("CONFIGURED_NOT_PROBED","productionReady=false");
        assertThat(result.toString()).doesNotContain("private-host");
        when(subway.activeVersion()).thenThrow(new IllegalStateException("private database credentials"));
        assertThat(service.status(Instant.now()).get("subway").toString()).contains("MASTER_LOOKUP_FAILED").doesNotContain("credentials");
    }
}
