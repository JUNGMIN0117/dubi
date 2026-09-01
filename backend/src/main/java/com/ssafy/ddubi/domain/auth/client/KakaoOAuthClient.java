package com.ssafy.ddubi.domain.auth.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 로그인 연동.
 *
 * <pre>
 * 1) POST kauth.kakao.com/oauth/token   인가 코드 → 액세스 토큰
 * 2) GET  kapi.kakao.com/v2/user/me     액세스 토큰 → 회원번호·이메일·닉네임
 * </pre>
 *
 * 이메일은 <b>없을 수 있다.</b> 사용자가 이메일 제공에 동의하지 않으면 카카오가 주지 않으며,
 * 그 경우 기존 계정과 통합되지 않고 별도 계정이 만들어진다(도메인 규칙 3).
 */
@Component
public class KakaoOAuthClient implements OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClient.class);

    private static final String TOKEN_PATH = "/oauth/token";
    private static final String USER_INFO_PATH = "/v2/user/me";

    private final ObjectMapper objectMapper;
    private final RestClient authClient;
    private final RestClient apiClient;
    private final KakaoOAuthProperties properties;

    public KakaoOAuthClient(RestClient.Builder builder, ObjectMapper objectMapper, KakaoOAuthProperties properties) {
        this.objectMapper = objectMapper;
        this.authClient = builder.clone().baseUrl(properties.authUrl()).build();
        this.apiClient = builder.clone().baseUrl(properties.apiUrl()).build();
        this.properties = properties;
    }

    @Override
    public AuthProvider provider() {
        return AuthProvider.KAKAO;
    }

    @Override
    public OAuthUserInfo fetchUserInfo(String authorizationCode, String redirectUri) {
        if (!StringUtils.hasText(properties.restApiKey())) {
            // 설정 누락을 카카오의 401 로 위장하지 않는다. 원인이 우리 쪽임을 분명히 남긴다.
            log.error("KAKAO_REST_API_KEY 미설정 — 카카오 로그인 불가");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        String accessToken = exchangeCodeForToken(authorizationCode, redirectUri);
        return fetchProfile(accessToken);
    }

    private String exchangeCodeForToken(String authorizationCode, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.restApiKey());
        form.add("redirect_uri", redirectUri);
        form.add("code", authorizationCode);
        if (StringUtils.hasText(properties.clientSecret())) {
            form.add("client_secret", properties.clientSecret());
        }

        JsonNode body = post(TOKEN_PATH, form);
        String accessToken = text(body, "access_token");
        if (!StringUtils.hasText(accessToken)) {
            log.warn("카카오 토큰 응답에 access_token 이 없음");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "카카오 로그인에 실패했습니다.");
        }
        return accessToken;
    }

    private JsonNode post(String path, MultiValueMap<String, String> form) {
        try {
            return authClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            // 카카오가 준 실패 사유를 그대로 남긴다. error_code(KOE320 등) 없이는
            // 만료인지, redirect_uri 불일치인지, client_secret 누락인지 구분할 수 없다.
            log.warn("카카오 토큰 교환 실패 [{}] {}", e.getStatusCode(), kakaoError(e));
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "카카오 인가 코드가 유효하지 않거나 만료되었습니다.");
        } catch (RestClientException e) {
            log.warn("카카오 토큰 교환 통신 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "카카오 로그인에 실패했습니다.");
        }
    }

    /**
     * 카카오 오류 응답에서 진단에 필요한 필드만 뽑는다.
     * 본문을 통째로 찍지 않는 이유: error_description 에 인가 코드가 섞여 나올 수 있다.
     */
    private String kakaoError(HttpStatusCodeException e) {
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            return "error=%s, error_code=%s, description=%s".formatted(
                    body.path("error").asText("?"),
                    body.path("error_code").asText("?"),
                    body.path("error_description").asText("?"));
        } catch (Exception ignored) {
            return "(응답 파싱 실패)";
        }
    }

    private OAuthUserInfo fetchProfile(String accessToken) {
        JsonNode body;
        try {
            body = apiClient.get()
                    .uri(USER_INFO_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("카카오 사용자 조회 실패: {}", e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "카카오 로그인에 실패했습니다.");
        }

        if (body == null || body.path("id").isMissingNode()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "카카오 로그인에 실패했습니다.");
        }

        String providerUserId = body.path("id").asText();
        JsonNode account = body.path("kakao_account");

        // 이메일은 동의 항목이라 없을 수 있다. 없으면 null 로 두고 별도 계정 경로를 타게 한다.
        String email = account.path("email").isTextual() ? account.path("email").asText() : null;
        String nickname = account.path("profile").path("nickname").isTextual()
                ? account.path("profile").path("nickname").asText()
                : null;

        return new OAuthUserInfo(providerUserId, email, nickname);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.path(field).isTextual() ? node.path(field).asText() : null;
    }
}
