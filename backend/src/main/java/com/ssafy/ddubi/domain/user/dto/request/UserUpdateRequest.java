package com.ssafy.ddubi.domain.user.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * USER-002 내 정보 변경. 명세서: nickname, notifyLeadMin, alarmEnabled, historyConsent
 * 중 <b>변경할 필드만</b> 전달한다.
 *
 * 전부 래퍼 타입인 이유: null 은 "안 보냈다", 값이 있으면 "이걸로 바꿔라"를 구분해야 한다.
 * 원시 타입이면 boolean 이 false 로, int 가 0 으로 들어와 의도치 않은 변경이 일어난다.
 */
public record UserUpdateRequest(
        @Size(min = 1, max = 50, message = "닉네임은 1~50자여야 합니다.")
        String nickname,

        @Min(value = 0, message = "알림 리드타임은 0분 이상이어야 합니다.")
        @Max(value = 180, message = "알림 리드타임은 180분 이하여야 합니다.")
        Integer notifyLeadMin,

        Boolean alarmEnabled,

        /** true 면 지금 시각으로 동의, false 면 철회(NULL). 이미 동의한 상태에서 true 면 시각을 유지한다. */
        Boolean historyConsent
) {

    /** 변경할 게 하나도 없으면 굳이 UPDATE 를 돌리지 않는다. */
    public boolean isEmpty() {
        return nickname == null && notifyLeadMin == null
                && alarmEnabled == null && historyConsent == null;
    }
}
