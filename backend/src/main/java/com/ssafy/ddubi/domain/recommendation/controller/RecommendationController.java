package com.ssafy.ddubi.domain.recommendation.controller;

import com.ssafy.ddubi.domain.recommendation.dto.response.RecommendationExplanationResponse;
import com.ssafy.ddubi.domain.recommendation.dto.response.RecommendationResponse;
import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.service.RecommendationService;
import com.ssafy.ddubi.global.common.ApiResponse;
import com.ssafy.ddubi.global.security.LoginUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "추천", description = "권장 출발 시각 계산과 근거 조회")
@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Operation(summary = "추천 계산",
            description = """
                    일정의 도착 시각에서 거꾸로 권장 출발 시각을 구한다.

                    구간 소요시간은 예측 → baseline_serving → 경로 API 순으로 내려간다.
                    출처는 step 단위로 하나만 고르며, 한 step 안에서 섞지 않는다.
                    실시간 구간 상태는 진입 예상시각이 그 값의 유효기간 안일 때만 더한다.

                    ⚠️ 지금은 예측 서비스가 붙지 않았고 구간 기준선도 비어 있어
                    사실상 경로 API 시간으로 계산된다. confidence 는 LOW, fallbackUsed 는 true 다.
                    자세한 것은 docs/contracts/recommendation-data-provenance.md.

                    추천은 불변이라 호출할 때마다 새 행이 만들어진다(도메인 규칙 7).
                    """)
    @PostMapping("/schedules/{scheduleId}/recommendations")
    public ApiResponse<RecommendationResponse> recommend(
            @LoginUserId Long userId,
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "INITIAL") TriggerType trigger) {
        return ApiResponse.success(RecommendationResponse.from(
                recommendationService.recommend(userId, scheduleId, trigger)));
    }

    @Operation(summary = "최근 추천",
            description = "가장 최근에 계산된 추천을 준다. 없으면 404. 남의 일정이면 403.")
    @GetMapping("/schedules/{scheduleId}/recommendations/latest")
    public ApiResponse<RecommendationResponse> latest(
            @LoginUserId Long userId,
            @PathVariable Long scheduleId) {
        return ApiResponse.success(RecommendationResponse.from(
                recommendationService.latest(userId, scheduleId)));
    }

    @Operation(summary = "추천 근거",
            description = """
                    "왜 이 시각인가" — 출처별 소요시간, 후보 비교, 계산에 쓴 구간별 원본.

                    저장된 JSON 을 그대로 문자열로 준다. 서버가 다시 구조를 바꾸면
                    저장된 근거와 화면에 보이는 근거가 갈라질 수 있다.
                    """)
    @GetMapping("/recommendations/{recommendationId}/explanation")
    public ApiResponse<RecommendationExplanationResponse> explanation(
            @LoginUserId Long userId,
            @PathVariable Long recommendationId) {
        return ApiResponse.success(RecommendationExplanationResponse.from(
                recommendationService.explanation(userId, recommendationId)));
    }
}
