package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

/**
 * 명시적으로 띄운 로컬 v13 서버와 학습 서버 golden을 실제 HTTP로 대조한다.
 * 기본 CI에서는 외부 모델 파일을 요구하지 않는다. 실제 실행 때 묶음 경로/localhost URL을
 * 시스템 속성으로 주입하며, 모델 버전·공식 마스터·학습 관측 수와 시험 적용 경계를 검증한다.
 */
@EnabledIfSystemProperty(named="ddubi.ml.static-smoke-url", matches="http://127\\.0\\.0\\.1:[0-9]+")
class BusStaticLiveModelTest {
    @Test void actualStaticModelMatchesServerGoldenWithoutHistoryLookups() throws Exception {
        var json = new ObjectMapper();
        var root = Path.of(System.getProperty("ddubi.ml.static-bundle"));
        var manifest = json.readTree(root.resolve("serving_manifest.json").toFile());
        var golden = json.readTree(root.resolve("golden.json").toFile());
        var client = new BusPreviewClient(json, System.getProperty("ddubi.ml.static-smoke-url"), 5000);
        ReflectionTestUtils.setField(client, "apiVersion", "v2");
        ReflectionTestUtils.setField(client, "staticTestApply", true);
        ReflectionTestUtils.setField(client, "staticModelVersion", manifest.path("model_version").asText());
        ReflectionTestUtils.setField(client, "staticMasterVersion", manifest.path("master_version").asText());
        assertThat(golden.path("requests").size()).isGreaterThan(0);
        for (int i = 0; i < golden.path("requests").size(); i++) {
            var row = golden.path("requests").get(i);
            var input = new BusPreviewClient.Input(row.path("provider_route_id").asText(),
                    row.path("provider_from_stop_id").asText(), row.path("provider_to_stop_id").asText(),
                    row.path("from_stop_sequence").asInt(), LocalDate.parse(row.path("service_date").asText()),
                    row.path("hour_slot").asInt());
            var value = client.predict(input);
            assertThat(value.applicationFailure()).isNull();
            assertThat(value.reason()).isEqualTo("ML_TEST_APPLY");
            assertThat(value.seconds()).isCloseTo(golden.path("predictions").get(i).asDouble(), within(0.000001));
            assertThat(value.trainingSampleCount()).isPositive();
            assertThat(value.historySampleCount()).isNull();
            assertThat(value.masterVersion()).isEqualTo(manifest.path("master_version").asText());
        }
    }
}
