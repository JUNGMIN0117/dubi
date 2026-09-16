package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 고정시각 실제 DTO 직렬화 산출물을 FE용 JSON Schema 검증 단계에 전달한다. */
class ScheduleEvaluationV2ContractTest {
    @Test void readonlyVariantPreservesNullIdsAndNoBuffer() throws Exception {
        var fixture=new ConditionalDepartureEvaluatorTest();
        var result=fixture.engine(256).evaluate(1,fixture.now,fixture.p,fixture.d,List.of(),
                at->fixture.comparison(at,fixture.d.minusSeconds(1)));
        var dto=ScheduleEvaluationV2.from(result,"fixture-evaluation",null,null,0,"READ_ONLY",fixture.p,fixture.p,fixture.d,
                fixture.now,fixture.now.plusSeconds(60),null,null,null,List.of("NO_VERIFIED_SEGMENT_MAPPING"),null,List.of());
        ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var value=json.valueToTree(dto);
        assertThat(value.path("schemaVersion").asText()).isEqualTo("schedule-evaluation-v2");
        assertThat(value.path("decision").asText()).isEqualTo("KEEP_DEPARTURE");
        assertThat(value.path("scheduleId").isNull()).isTrue();assertThat(value.path("inputVersion").isNull()).isTrue();
        assertThat(value.path("targetArriveAt").isNull()).isTrue();assertThat(value.path("appliedBufferSeconds").asInt()).isZero();
        assertThat(value.path("quality").path("productionEligible").asBoolean()).isFalse();
        Path file=Path.of("target/conditional-departure-readonly.json");Files.createDirectories(file.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),dto);
    }
}
