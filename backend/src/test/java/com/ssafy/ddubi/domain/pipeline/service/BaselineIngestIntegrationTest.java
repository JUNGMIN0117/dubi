package com.ssafy.ddubi.domain.pipeline.service;

import com.ssafy.ddubi.domain.pipeline.dto.request.BaselineRowsRequest;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineActivateResponse;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineUploadResponse;
import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfig;
import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfigKey;
import com.ssafy.ddubi.domain.pipeline.entity.WeatherBucket;
import com.ssafy.ddubi.domain.pipeline.repository.BaselineServingRepository;
import com.ssafy.ddubi.domain.pipeline.repository.PipelineConfigRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기준선 적재·활성화 (internal-api.yaml).
 *
 * 고정하려는 것:
 * <ul>
 *   <li>청크 재전송이 안전할 것 (업서트) — 재시도가 성립하는 근거</li>
 *   <li>activate 전에는 서빙 버전이 바뀌지 않을 것 — 절반만 올라간 데이터가 나가지 않는다</li>
 *   <li>활성·직전 버전은 정리에서 보호될 것 — 롤백 여지</li>
 *   <li>백분위 역전·행 0건 활성화를 거절할 것</li>
 * </ul>
 */
class BaselineIngestIntegrationTest extends IntegrationTestSupport {

    private static final String SEGMENT_A = "BUS:100100609:123456:123789";
    private static final String SEGMENT_B = "SUB:2:0222:0223";

    @Autowired
    private BaselineIngestService service;
    @Autowired
    private BaselineServingRepository baselineRepository;
    @Autowired
    private PipelineConfigRepository configRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static BaselineRowsRequest.Row row(String segmentId, int hour, int p50) {
        return new BaselineRowsRequest.Row(segmentId, 3, hour, WeatherBucket.DRY,
                p50, p50 + 60, p50 + 120, 42);
    }

    private static BaselineRowsRequest rows(BaselineRowsRequest.Row... rows) {
        return new BaselineRowsRequest(List.of(rows));
    }

    private String activeVersion() {
        return configRepository.findById(PipelineConfigKey.BASELINE_ACTIVE_VERSION)
                .map(PipelineConfig::getConfigValue).orElse(null);
    }

    // ------------------------------------------------------------------
    // 업로드
    // ------------------------------------------------------------------

    @Test
    @DisplayName("행을 올리면 그 버전에 쌓이고, 아직 서빙 버전은 바뀌지 않는다")
    void uploadDoesNotActivate() {
        BaselineUploadResponse response = service.upload("2026-09-01",
                rows(row(SEGMENT_A, 8, 1200), row(SEGMENT_B, 8, 600)));

        assertThat(response.received()).isEqualTo(2);
        assertThat(response.versionTotal()).isEqualTo(2);
        assertThat(activeVersion()).isNull();   // activate 전이므로 서빙 대상이 없다
    }

    @Test
    @DisplayName("같은 청크를 다시 보내도 행이 늘지 않고 값만 갱신된다 — 청크 재시도의 근거")
    void reuploadUpserts() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        BaselineUploadResponse second = service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1500)));

        assertThat(second.versionTotal()).isEqualTo(1);
        Integer p50 = jdbcTemplate.queryForObject(
                "select p50 from baseline_serving where segment_id = ? and data_version = ?",
                Integer.class, SEGMENT_A, "2026-09-01");
        assertThat(p50).isEqualTo(1500);
    }

    @Test
    @DisplayName("서빙 중인 버전에는 덮어쓸 수 없다 — 추천이 요청마다 다른 값을 보게 된다")
    void cannotOverwriteActiveVersion() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01");

        assertThatThrownBy(() -> service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 999))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("백분위가 뒤집히면 400 — 여유를 크게 잡을수록 빨라진다는 결론이 나온다")
    void rejectsUnorderedPercentiles() {
        BaselineRowsRequest bad = new BaselineRowsRequest(List.of(
                new BaselineRowsRequest.Row(SEGMENT_A, 3, 8, WeatherBucket.DRY, 900, 600, 1200, 10)));

        assertThatThrownBy(() -> service.upload("2026-09-01", bad))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    @DisplayName("dataVersion 이 20자를 넘으면 400 — 컬럼이 VARCHAR(20) 이라 조용히 잘린다")
    void rejectsTooLongVersion() {
        assertThatThrownBy(() -> service.upload("x".repeat(21), rows(row(SEGMENT_A, 8, 1200))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    // ------------------------------------------------------------------
    // 활성화
    // ------------------------------------------------------------------

    @Test
    @DisplayName("활성화하면 서빙 버전이 바뀌고 직전 버전이 기록된다")
    void activateSwitchesVersion() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01");

        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1300)));
        BaselineActivateResponse response = service.activate("2026-09-02");

        assertThat(response.activeVersion()).isEqualTo("2026-09-02");
        assertThat(response.previousVersion()).isEqualTo("2026-09-01");
        assertThat(response.rowCount()).isEqualTo(1);
        assertThat(activeVersion()).isEqualTo("2026-09-02");
    }

    @Test
    @DisplayName("행이 0건인 버전은 활성화할 수 없다 — 추천이 기준선을 통째로 잃는다")
    void cannotActivateEmptyVersion() {
        assertThatThrownBy(() -> service.activate("2026-09-09"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("같은 버전 재활성화는 멱등 — 배치 재시도가 여기서 실패하면 안 된다")
    void reactivateIsIdempotent() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01");

        BaselineActivateResponse again = service.activate("2026-09-01");

        assertThat(again.activeVersion()).isEqualTo("2026-09-01");
        assertThat(again.purgedVersions()).isEmpty();
        assertThat(activeVersion()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("활성·직전 버전은 정리에서 보호된다 — 롤백할 곳이 남아야 한다")
    void activeAndPreviousSurvivePurge() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01");
        service.upload("2026-09-02", rows(row(SEGMENT_A, 8, 1300)));
        service.activate("2026-09-02");

        assertThat(baselineRepository.countByDataVersion("2026-09-01")).isEqualTo(1);
        assertThat(baselineRepository.countByDataVersion("2026-09-02")).isEqualTo(1);
    }

    @Test
    @DisplayName("48시간이 안 지난 고아 버전은 지우지 않는다 — 활성화를 기다리는 중일 수 있다")
    void freshOrphanIsKept() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.upload("2026-08-31", rows(row(SEGMENT_B, 8, 700)));   // 방금 올린 고아
        service.activate("2026-09-01");

        assertThat(baselineRepository.countByDataVersion("2026-08-31")).isEqualTo(1);
    }

    @Test
    @DisplayName("48시간 지난 고아 버전은 활성화할 때 함께 지운다")
    void staleOrphanIsPurged() {
        service.upload("2026-08-01", rows(row(SEGMENT_B, 8, 700)));   // 실패한 배치가 남긴 찌꺼기
        // created_at 을 과거로 밀어 48시간 경과를 만든다. 시간을 기다릴 수는 없다.
        jdbcTemplate.update(
                "update baseline_serving set created_at = created_at - interval 5 day where data_version = ?",
                "2026-08-01");

        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        BaselineActivateResponse response = service.activate("2026-09-01");

        assertThat(response.purgedVersions()).containsExactly("2026-08-01");
        assertThat(baselineRepository.countByDataVersion("2026-08-01")).isZero();
    }

    @Test
    @DisplayName("활성화 시각이 기록된다 — 기준선이 낡았는지 판단하는 기준이다")
    void activationTimeIsRecorded() {
        service.upload("2026-09-01", rows(row(SEGMENT_A, 8, 1200)));
        service.activate("2026-09-01");

        assertThat(configRepository.findById(PipelineConfigKey.BASELINE_ACTIVATED_AT))
                .isPresent();
    }
}
