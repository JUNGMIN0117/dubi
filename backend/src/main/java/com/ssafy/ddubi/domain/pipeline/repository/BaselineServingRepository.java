package com.ssafy.ddubi.domain.pipeline.repository;

import com.ssafy.ddubi.domain.pipeline.entity.BaselineServing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 조회·정리용. <b>적재는 여기가 아니라 {@code BaselineIngestService} 의 JdbcTemplate 배치</b>가 한다 —
 * 수십만 행을 엔티티로 다루면 영속성 컨텍스트가 터진다.
 */
public interface BaselineServingRepository extends JpaRepository<BaselineServing, Long> {

    long countByDataVersion(String dataVersion);

    /** 저장돼 있는 모든 버전. 정리 대상 판별에 쓴다. */
    @Query("select distinct b.dataVersion from BaselineServing b")
    List<String> findAllDataVersions();

    /**
     * 고아 버전 삭제. 활성·직전 버전은 호출부가 목록에서 빼고 넘긴다.
     *
     * 벌크 삭제라 영속성 컨텍스트를 우회한다 — 어차피 이 트랜잭션에서 다시 읽지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from BaselineServing b where b.dataVersion = :dataVersion")
    int deleteByDataVersion(@Param("dataVersion") String dataVersion);

    /**
     * 그 버전이 마지막으로 만들어진 시각. 고아 버전이 48시간을 넘겼는지 판단한다.
     * 행이 없으면 null 이다.
     */
    @Query("select max(b.createdAt) from BaselineServing b where b.dataVersion = :dataVersion")
    java.time.LocalDateTime findLastCreatedAt(@Param("dataVersion") String dataVersion);
}
