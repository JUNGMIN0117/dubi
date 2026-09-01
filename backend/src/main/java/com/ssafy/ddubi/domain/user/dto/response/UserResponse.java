package com.ssafy.ddubi.domain.user.dto.response;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.user.entity.User;

import java.time.LocalDateTime;

/**
 * USER-001 / USER-002 응답. Notion API 명세서 v2.1.1 기준.
 *
 * 명세서 응답 필드: id, provider, email, nickname, notifyLeadMin, alarmEnabled,
 * historyConsentedAt, withdrawnAt, createdAt, updatedAt
 *
 * <p><b>withdrawnAt 은 담지 않는다.</b> ERD_master 에 해당 컬럼이 없다 —
 * 탈퇴는 하드 삭제라 "탈퇴한 사용자"라는 상태 자체가 존재하지 않는다(도메인 규칙 4).
 * 명세서에서 이 필드를 빼는 것이 맞고, 팀 확인 대기 중이다.
 *
 * <p>provider 는 이 계정에 붙은 인증 수단 중 하나다. 계정 통합으로 LOCAL·KAKAO 가
 * 함께 있을 수 있어 LOCAL 을 우선으로 고른다(가입 경로를 대표값으로 본다).
 */
public record UserResponse(
        Long id,
        AuthProvider provider,
        String email,
        String nickname,
        int notifyLeadMin,
        boolean alarmEnabled,
        LocalDateTime historyConsentedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static UserResponse of(User user, AuthProvider provider) {
        return new UserResponse(
                user.getId(),
                provider,
                user.getEmail(),
                user.getNickname(),
                user.getNotifyLeadMin(),
                user.isAlarm(),
                user.getHistoryConsentedAt(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
