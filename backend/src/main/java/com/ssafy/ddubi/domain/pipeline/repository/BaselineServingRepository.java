package com.ssafy.ddubi.domain.pipeline.repository;

import com.ssafy.ddubi.domain.pipeline.entity.BaselineServing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 조회·정리용. <b>적재는 여기가 아니라 {@code BaselineIngestService} 의 JdbcTemplate 배치</b>가 한다 —
 * 수십만 행을 엔티티로 다루면 영속성 컨텍스트가 터진다.
 *
 * <p>아래 메서드는 전부 {@code data_version} 단독 조건이다. UNIQUE 키에서는 그 컬럼이
 * 마지막이라 쓸 수 없어 {@code IX_BASELINE_VERSION} 을 따로 걸어 두었다. 인덱스가 없으면
 * 업로드 청크마다 도는 {@link #countByDataVersion}가 매번 풀 스캔이 된다.
 */
public interface BaselineServingRepository extends JpaRepository<BaselineServing, Long> {

    long countByDataVersion(String dataVersion);

    /**
     * 지울 고아 버전을 <b>한 번의 조회로</b> 고른다.
     *
     * 예전에는 모든 버전을 가져와 버전마다 {@code max(created_at)} 를 다시 물었다(1 + N).
     * 버전 수는 적어도 매 조회가 수백만 행을 훑기 때문에 활성화가 눈에 띄게 느려진다.
     *
     * @param cutoff  이 시각보다 마지막 적재가 오래된 버전만 대상. 방금 올라와 활성화를
     *                기다리는 버전을 지우지 않기 위한 조건이다
     * @param keep    활성·직전 버전. 롤백 여지와 전환 찰나를 위해 보호한다
     */
    @Query("""
            select b.dataVersion
              from BaselineServing b
             where b.dataVersion not in :keep
             group by b.dataVersion
            having max(b.createdAt) < :cutoff
            """)
    List<String> findPurgeableVersions(@Param("cutoff") LocalDateTime cutoff,
                                       @Param("keep") List<String> keep);

    /**
     * 고아 버전 삭제. 벌크 삭제라 영속성 컨텍스트를 우회한다 —
     * 어차피 이 트랜잭션에서 다시 읽지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from BaselineServing b where b.dataVersion = :dataVersion")
    int deleteByDataVersion(@Param("dataVersion") String dataVersion);
}
