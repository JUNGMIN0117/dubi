package com.ssafy.ddubi.domain.pipeline.service;

import com.ssafy.ddubi.domain.pipeline.dto.request.BaselineRowsRequest;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineActivateResponse;
import com.ssafy.ddubi.domain.pipeline.dto.response.BaselineUploadResponse;
import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfig;
import com.ssafy.ddubi.domain.pipeline.entity.PipelineConfigKey;
import com.ssafy.ddubi.domain.pipeline.repository.BaselineServingRepository;
import com.ssafy.ddubi.domain.pipeline.repository.PipelineConfigRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.global.util.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 기준선 적재·활성화 (internal-api.yaml).
 *
 * <h3>왜 백엔드를 거치나</h3>
 * Spark 가 MySQL 에 직접 써도 되지만 2026-08-31 회의에서 이쪽으로 정했다 —
 * <b>서비스 DB 쓰기는 전부 Spring 단일 관문을 지난다.</b> 스키마를 두 곳이 알지 않아도 되고,
 * 값 검증과 버전 전환을 한 곳에서 책임진다.
 *
 * <h3>업로드와 활성화를 나눈 이유</h3>
 * 기준선은 수십만 행이라 한 번에 못 올리고 청크로 나눈다. 중간에 배치가 죽으면
 * 절반만 들어간 상태가 되는데, <b>{@code activate} 가 오지 않으므로 이전 버전이 계속 서빙된다.</b>
 * 반쯤 들어간 데이터로 추천이 나가는 사고가 구조적으로 막힌다.
 */
@Service
public class BaselineIngestService {

    private static final Logger log = LoggerFactory.getLogger(BaselineIngestService.class);

    /**
     * 활성화되지 못한 채 이만큼 지난 버전은 다음 활성화 때 함께 지운다.
     * 실패한 배치가 남긴 찌꺼기가 디스크를 계속 먹는 것을 막는다.
     */
    private static final Duration ORPHAN_RETENTION = Duration.ofHours(48);

    private static final String UPSERT_SQL = """
            INSERT INTO baseline_serving
                (segment_id, dow, hour_slot, weather_bucket, p50, p80, p95, sample_count, data_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                p50 = VALUES(p50),
                p80 = VALUES(p80),
                p95 = VALUES(p95),
                sample_count = VALUES(sample_count)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final BaselineServingRepository baselineRepository;
    private final PipelineConfigRepository configRepository;
    private final int batchSize;

    public BaselineIngestService(JdbcTemplate jdbcTemplate,
                                 BaselineServingRepository baselineRepository,
                                 PipelineConfigRepository configRepository,
                                 @Value("${ddubi.pipeline.baseline-batch-size:1000}") int batchSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.baselineRepository = baselineRepository;
        this.configRepository = configRepository;
        this.batchSize = batchSize;
    }

    // ------------------------------------------------------------------
    // 업로드
    // ------------------------------------------------------------------

    /**
     * 청크 하나를 적재한다.
     *
     * <b>업서트인 이유</b>: 같은 청크를 다시 보내도 안전해야 청크 단위 재시도가 성립한다.
     * {@code UK_BASELINE_KEY(segment_id, dow, hour_slot, weather_bucket, data_version)} 가
     * 중복 판정 기준이다.
     */
    @Transactional
    public BaselineUploadResponse upload(String dataVersion, BaselineRowsRequest request) {
        validateVersion(dataVersion);
        rejectIfActive(dataVersion);

        List<BaselineRowsRequest.Row> rows = request.rows();
        validateRows(rows);

        for (int from = 0; from < rows.size(); from += batchSize) {
            List<BaselineRowsRequest.Row> slice = rows.subList(from, Math.min(from + batchSize, rows.size()));
            jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    BaselineRowsRequest.Row row = slice.get(i);
                    ps.setString(1, row.segmentId());
                    ps.setByte(2, row.dow().byteValue());
                    ps.setByte(3, row.hourSlot().byteValue());
                    ps.setString(4, row.weatherBucket().name());
                    ps.setInt(5, row.p50());
                    ps.setInt(6, row.p80());
                    ps.setInt(7, row.p95());
                    ps.setInt(8, row.sampleCount());
                    ps.setString(9, dataVersion);
                }

                @Override
                public int getBatchSize() {
                    return slice.size();
                }
            });
        }

        long total = baselineRepository.countByDataVersion(dataVersion);
        log.info("기준선 업로드 version={} received={} versionTotal={}", dataVersion, rows.size(), total);
        return new BaselineUploadResponse(dataVersion, rows.size(), total);
    }

    // ------------------------------------------------------------------
    // 활성화
    // ------------------------------------------------------------------

    /**
     * 그 버전을 서빙 대상으로 전환한다. 여기까지 와야 추천이 새 값을 본다.
     *
     * 전환 후 고아 버전을 정리하지만 <b>활성·직전 버전은 건드리지 않는다</b> —
     * 조회와 전환이 겹치는 찰나를 견디고, 문제가 생기면 직전 버전으로 되돌릴 수 있어야 한다.
     */
    @Transactional
    public BaselineActivateResponse activate(String dataVersion) {
        validateVersion(dataVersion);

        long rowCount = baselineRepository.countByDataVersion(dataVersion);
        if (rowCount == 0) {
            // 행이 하나도 없는 버전을 활성화하면 추천이 통째로 기준선을 잃는다.
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "행이 없는 버전은 활성화할 수 없습니다: " + dataVersion);
        }

        String currentActive = readConfig(PipelineConfigKey.BASELINE_ACTIVE_VERSION).orElse(null);
        LocalDateTime activatedAt = BusinessTime.nowMicros();

        if (dataVersion.equals(currentActive)) {
            // 같은 버전 재활성화는 멱등하게 넘긴다 — 배치 재시도가 여기서 실패하면 안 된다.
            writeConfig(PipelineConfigKey.BASELINE_ACTIVATED_AT, activatedAt.toString());
            log.info("기준선 재활성화(변경 없음) version={}", dataVersion);
            return new BaselineActivateResponse(dataVersion,
                    readConfig(PipelineConfigKey.BASELINE_PREVIOUS_VERSION).orElse(null),
                    rowCount, activatedAt, List.of());
        }

        if (currentActive != null) {
            writeConfig(PipelineConfigKey.BASELINE_PREVIOUS_VERSION, currentActive);
        }
        writeConfig(PipelineConfigKey.BASELINE_ACTIVE_VERSION, dataVersion);
        writeConfig(PipelineConfigKey.BASELINE_ACTIVATED_AT, activatedAt.toString());

        List<String> purged = purgeOrphans(dataVersion, currentActive);

        log.info("기준선 활성화 version={} previous={} rows={} purged={}",
                dataVersion, currentActive, rowCount, purged);
        return new BaselineActivateResponse(dataVersion, currentActive, rowCount, activatedAt, purged);
    }

    /**
     * 활성·직전 버전이 아니면서 {@link #ORPHAN_RETENTION} 을 넘긴 버전을 지운다.
     *
     * 시간 조건을 두는 이유: 방금 업로드가 끝나 활성화를 기다리는 버전을 지워 버리면
     * 정상적인 배치를 망친다.
     */
    private List<String> purgeOrphans(String activeVersion, String previousVersion) {
        LocalDateTime cutoff = BusinessTime.nowMicros().minus(ORPHAN_RETENTION);
        List<String> keep = previousVersion == null
                ? List.of(activeVersion)
                : List.of(activeVersion, previousVersion);

        List<String> purgeable = baselineRepository.findPurgeableVersions(cutoff, keep);
        for (String version : purgeable) {
            int deleted = baselineRepository.deleteByDataVersion(version);
            log.info("고아 기준선 버전 삭제 version={} rows={}", version, deleted);
        }
        return purgeable;
    }

    // ------------------------------------------------------------------

    /**
     * 활성 버전에 덮어쓰는 것을 막는다.
     *
     * 서빙 중인 버전을 고치면 추천이 요청마다 다른 값을 보게 된다. 새 버전으로 올리고
     * activate 하라는 것이 계약의 전제다.
     */
    private void rejectIfActive(String dataVersion) {
        readConfig(PipelineConfigKey.BASELINE_ACTIVE_VERSION)
                .filter(dataVersion::equals)
                .ifPresent(v -> {
                    throw new BusinessException(ErrorCode.INVALID_STATE,
                            "서빙 중인 버전에는 덮어쓸 수 없습니다. 새 dataVersion 으로 올려 주세요: " + v);
                });
    }

    private void validateVersion(String dataVersion) {
        // data_version 은 VARCHAR(20). 넘치면 DB 가 자르거나 거절하는데 원인이 안 보인다.
        if (dataVersion == null || dataVersion.isBlank() || dataVersion.length() > 20) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "dataVersion 은 1~20자여야 합니다.");
        }
    }

    private void validateRows(List<BaselineRowsRequest.Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isPercentileOrdered()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "p50 ≤ p80 ≤ p95 여야 합니다. rows[" + i + "] segmentId=" + rows.get(i).segmentId());
            }
        }
    }

    private Optional<String> readConfig(String key) {
        return configRepository.findById(key).map(PipelineConfig::getConfigValue);
    }

    private void writeConfig(String key, String value) {
        configRepository.findById(key)
                .ifPresentOrElse(c -> c.changeValue(value),
                        () -> configRepository.save(PipelineConfig.of(key, value)));
    }
}
