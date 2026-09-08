package com.ssafy.ddubi.domain.pipeline.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 활성화 요청 (internal-api.yaml activateBaselineVersion).
 *
 * <h3>왜 행 수를 같이 받나 — 체크섬이다</h3>
 * "업로드가 다 끝났다" 는 배치 쪽 판단이다. 그런데 청크 하나가 조용히 유실됐는데
 * 배치는 성공했다고 믿는 경우가 있다. 그대로 activate 하면 <b>반쪽 기준선이 서빙된다.</b>
 *
 * <p>업로드와 활성화를 나눈 것만으로는 "배치가 <b>죽는</b> 경우"만 막힌다.
 * "배치가 <b>성공했다고 착각하는</b> 경우"는 이 값이 잡는다.
 *
 * @param totalRows 배치가 보냈다고 주장하는 총 행 수. 서버에 저장된 수와 다르면 409
 */
public record BaselineActivateRequest(
        @NotNull(message = "totalRows 는 필수입니다. 업로드한 총 행 수를 보내 주세요.")
        @PositiveOrZero(message = "totalRows 는 0 이상이어야 합니다.")
        Long totalRows
) {
}
