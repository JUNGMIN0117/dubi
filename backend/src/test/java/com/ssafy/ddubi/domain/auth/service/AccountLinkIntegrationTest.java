package com.ssafy.ddubi.domain.auth.service;

import com.ssafy.ddubi.domain.auth.client.GoogleOAuthClient;
import com.ssafy.ddubi.domain.auth.client.NaverOAuthClient;
import com.ssafy.ddubi.domain.auth.client.OAuthUserInfo;
import com.ssafy.ddubi.domain.auth.dto.request.LocalLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.LocalSignupRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthAccessTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthIdTokenLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLinkRequest;
import com.ssafy.ddubi.domain.auth.dto.request.OAuthLoginRequest;
import com.ssafy.ddubi.domain.auth.dto.response.AuthTokenResponse;
import com.ssafy.ddubi.domain.auth.dto.response.OAuthLoginResponse;
import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.repository.UserAuthRepository;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.global.exception.ErrorCode;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 도메인 규칙 3 — 계정 연동 (2026-09-10 개정).
 *
 * <blockquote>
 * 이메일이 계정 키다. 같은 <b>검증된</b> 이메일로 소셜 로그인하면 계정을 새로 만들지 않지만,
 * <b>자동으로 붙이지도 않는다.</b> 기존 계정의 비밀번호로 소유를 증명해야 연동된다.
 * 이메일이 없거나 미검증이면 별도 계정이다.
 * </blockquote>
 *
 * <h3>이 테스트가 지키는 것</h3>
 * 가장 중요한 것은 <b>자동 통합이 다시 살아나지 않는 것</b>이다. 자동 통합은 제공자가 이메일
 * 소유를 잘못 확인한 순간 남의 계정으로 들어가는 경로가 된다.
 */
class AccountLinkIntegrationTest extends IntegrationTestSupport {

    private static final String EMAIL = "merge@ddubi.com";
    private static final String PASSWORD = "password123";
    private static final String KAKAO_ID = "5066698097";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserAuthRepository userAuthRepository;
    @MockitoBean
    private GoogleOAuthClient googleOAuthClient;
    @MockitoBean
    private NaverOAuthClient naverOAuthClient;

    // ------------------------------------------------------------------
    // ① 이미 연결된 소셜 · ② 신규 — 기존과 같다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("이미 연결된 소셜 계정으로 다시 로그인하면 그대로 토큰이 나온다")
    void logsInWhenSocialAccountAlreadyLinked() {
        givenKakao(KAKAO_ID, EMAIL, true, "카카오사용자");
        Long firstId = authenticated(authService.loginOAuth(kakaoLogin()).response()).user().id();

        var second = authService.loginOAuth(kakaoLogin());

        assertThat(second.created()).isFalse();
        assertThat(authenticated(second.response()).user().id()).isEqualTo(firstId);
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userAuthRepository.count()).as("인증 수단도 늘지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이메일 계정이 없으면 회원을 만들고 바로 토큰을 준다")
    void createsAccountWhenNoEmailMatch() {
        signupLocal(EMAIL, "로컬사용자");
        givenKakao(KAKAO_ID, "other@ddubi.com", true, "카카오사용자");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.created()).as("신규 가입이므로 201").isTrue();
        assertThat(result.response().status()).isEqualTo(OAuthLoginResponse.Status.AUTHENTICATED);
        assertThat(userRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // ③ 같은 이메일 — 자동 통합하지 않는다
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 이메일이어도 자동으로 붙이지 않고 연동을 요구한다")
    void requiresLinkInsteadOfAutoMerging() {
        signupLocal(EMAIL, "기존닉네임");
        Long existingId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        givenKakao(KAKAO_ID, EMAIL, true, "카카오닉네임");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.response().status())
                .isEqualTo(OAuthLoginResponse.Status.ACCOUNT_LINK_REQUIRED);
        assertThat(result.response().tokens()).as("아직 로그인이 아니다").isNull();
        assertThat(result.response().linkToken()).isNotBlank();
        assertThat(result.response().provider()).isEqualTo(AuthProvider.KAKAO);
        assertThat(userAuthRepository.findProvidersByUserId(existingId))
                .as("비밀번호 확인 전에는 인증 수단이 붙지 않는다")
                .containsExactly(AuthProvider.LOCAL);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이메일은 마스킹해서 준다 — 어느 계정인지는 알리되 전체는 노출하지 않는다")
    void masksEmailInLinkChallenge() {
        signupLocal(EMAIL, "기존닉네임");
        givenKakao(KAKAO_ID, EMAIL, true, "카카오닉네임");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.response().email()).isEqualTo("me***@ddubi.com");
    }

    @Test
    @DisplayName("네이버·구글도 같은 규칙을 받는다")
    void appliesToNaverAndGoogle() {
        signupLocal(EMAIL, "기존닉네임");

        given(naverOAuthClient.provider()).willReturn(AuthProvider.NAVER);
        given(naverOAuthClient.fetchUserInfoFromAccessToken(any()))
                .willReturn(new OAuthUserInfo("naver-1", EMAIL, true, "네이버닉네임"));
        var naver = authService.loginOAuthToken(
                new OAuthAccessTokenLoginRequest(AuthProvider.NAVER, "naver-token", "dev-mobile"));

        given(googleOAuthClient.provider()).willReturn(AuthProvider.GOOGLE);
        given(googleOAuthClient.fetchUserInfoFromIdToken(any()))
                .willReturn(new OAuthUserInfo("google-1", EMAIL, true, "구글닉네임"));
        var google = authService.loginOAuthIdToken(
                new OAuthIdTokenLoginRequest(AuthProvider.GOOGLE, "google-id-token", "dev-mobile"));

        assertThat(naver.response().status()).isEqualTo(OAuthLoginResponse.Status.ACCOUNT_LINK_REQUIRED);
        assertThat(google.response().status()).isEqualTo(OAuthLoginResponse.Status.ACCOUNT_LINK_REQUIRED);
    }

    // ------------------------------------------------------------------
    // 연동 확정
    // ------------------------------------------------------------------

    @Test
    @DisplayName("비밀번호가 맞으면 인증 수단이 붙고 토큰이 나온다")
    void linksWhenPasswordMatches() {
        signupLocal(EMAIL, "기존닉네임");
        Long existingId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        String linkToken = linkTokenFromKakaoLogin();

        AuthTokenResponse tokens = authService.linkOAuthAccount(
                new OAuthLinkRequest(linkToken, PASSWORD, "dev-1"));

        assertThat(tokens.user().id()).isEqualTo(existingId);
        assertThat(tokens.user().nickname())
                .as("소셜 프로필로 기존 닉네임을 덮어쓰지 않는다")
                .isEqualTo("기존닉네임");
        assertThat(userAuthRepository.findProvidersByUserId(existingId))
                .containsExactlyInAnyOrder(AuthProvider.LOCAL, AuthProvider.KAKAO);
        assertThat(userRepository.count()).as("계정은 늘지 않는다").isEqualTo(1);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 이고 연동되지 않는다")
    void rejectsWrongPassword() {
        signupLocal(EMAIL, "기존닉네임");
        Long existingId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        String linkToken = linkTokenFromKakaoLogin();

        assertThatThrownBy(() -> authService.linkOAuthAccount(
                new OAuthLinkRequest(linkToken, "wrong-password", "dev-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        assertThat(userAuthRepository.findProvidersByUserId(existingId))
                .containsExactly(AuthProvider.LOCAL);
    }

    @Test
    @DisplayName("티켓은 한 번만 쓴다 — 비밀번호를 틀려도 소비된다")
    void ticketIsConsumedEvenOnFailure() {
        signupLocal(EMAIL, "기존닉네임");
        String linkToken = linkTokenFromKakaoLogin();

        assertThatThrownBy(() -> authService.linkOAuthAccount(
                new OAuthLinkRequest(linkToken, "wrong-password", "dev-1")))
                .isInstanceOf(BusinessException.class);

        // 같은 티켓으로 이번엔 맞는 비밀번호를 넣어도 통하지 않는다.
        assertThatThrownBy(() -> authService.linkOAuthAccount(
                new OAuthLinkRequest(linkToken, PASSWORD, "dev-1")))
                .as("살려 두면 이 화면이 비밀번호 대입 창구가 된다")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_TOKEN_INVALID);
    }

    @Test
    @DisplayName("성공한 티켓도 재사용되지 않는다")
    void ticketCannotBeReusedAfterSuccess() {
        signupLocal(EMAIL, "기존닉네임");
        String linkToken = linkTokenFromKakaoLogin();
        authService.linkOAuthAccount(new OAuthLinkRequest(linkToken, PASSWORD, "dev-1"));

        assertThatThrownBy(() -> authService.linkOAuthAccount(
                new OAuthLinkRequest(linkToken, PASSWORD, "dev-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_TOKEN_INVALID);
    }

    @Test
    @DisplayName("모르는 티켓은 401")
    void rejectsUnknownTicket() {
        signupLocal(EMAIL, "기존닉네임");

        assertThatThrownBy(() -> authService.linkOAuthAccount(
                new OAuthLinkRequest("made-up-token", PASSWORD, "dev-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.LINK_TOKEN_INVALID);
    }

    @Test
    @DisplayName("그 사이 소셜이 다른 사용자에게 붙었으면 409 — 조용히 옮기지 않는다")
    void rejectsWhenSocialWasLinkedToSomeoneElseMeanwhile() {
        signupLocal(EMAIL, "기존닉네임");
        String linkToken = linkTokenFromKakaoLogin();

        // 티켓을 들고 있는 사이, 같은 카카오 계정이 다른 이메일로 로그인해 새 회원이 됐다.
        givenKakao(KAKAO_ID, "other@ddubi.com", true, "카카오사용자");
        authService.loginOAuth(kakaoLogin());

        assertThatThrownBy(() -> authService.linkOAuthAccount(
                new OAuthLinkRequest(linkToken, PASSWORD, "dev-1")))
                .as("옮기면 먼저 붙여 둔 쪽이 로그인 수단을 잃는다")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SOCIAL_ALREADY_LINKED);
    }

    // ------------------------------------------------------------------
    // 연동할 수 없는 경우
    // ------------------------------------------------------------------

    @Test
    @DisplayName("기존 계정에 비밀번호가 없으면 409 — 쓰던 소셜로 로그인하라고 안내한다")
    void refusesWhenExistingAccountHasNoPassword() {
        // 구글로만 가입한 계정
        given(googleOAuthClient.provider()).willReturn(AuthProvider.GOOGLE);
        given(googleOAuthClient.fetchUserInfoFromIdToken(any()))
                .willReturn(new OAuthUserInfo("google-1", EMAIL, true, "구글사용자"));
        authService.loginOAuthIdToken(
                new OAuthIdTokenLoginRequest(AuthProvider.GOOGLE, "google-id-token", "dev-mobile"));

        givenKakao(KAKAO_ID, EMAIL, true, "카카오사용자");

        assertThatThrownBy(() -> authService.loginOAuth(kakaoLogin()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GOOGLE")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LINK_UNAVAILABLE);

        assertThat(userRepository.count())
                .as("UK_USER_EMAIL 때문에 새 계정을 만들 수도 없다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("미검증 이메일은 연동 후보가 아니고, 새 계정에 저장하지도 않는다")
    void ignoresUnverifiedEmailEntirely() {
        signupLocal(EMAIL, "로컬사용자");
        givenKakao(KAKAO_ID, EMAIL, false, "카카오사용자");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.response().status()).isEqualTo(OAuthLoginResponse.Status.AUTHENTICATED);
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(authenticated(result.response()).user().email())
                .as("저장하면 UK_USER_EMAIL 을 차지해 진짜 주인이 막힌다")
                .isNull();
    }

    @Test
    @DisplayName("이메일을 아예 주지 않으면 별도 계정이 된다")
    void createsSeparateAccountWhenEmailIsNull() {
        signupLocal(EMAIL, "로컬사용자");
        givenKakao(KAKAO_ID, null, false, "카카오사용자");

        var result = authService.loginOAuth(kakaoLogin());

        assertThat(result.created()).isTrue();
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(authenticated(result.response()).user().email()).isNull();
    }

    // ------------------------------------------------------------------
    // 로컬 가입·로그인 안내 (기존 동작 유지)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("소셜로만 가입된 이메일로 로컬 가입하면 409 와 안내 메시지를 준다")
    void rejectsLocalSignupOnSocialOnlyEmail() {
        givenKakao(KAKAO_ID, EMAIL, true, "카카오사용자");
        authService.loginOAuth(kakaoLogin());

        assertThatThrownBy(() -> signupLocal(EMAIL, "새사용자"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("카카오")
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    @DisplayName("소셜 전용 계정에 로컬 로그인을 시도하면 카카오로 로그인하라고 안내한다")
    void guidesToSocialLoginWhenNoLocalAuth() {
        givenKakao(KAKAO_ID, EMAIL, true, "카카오사용자");
        authService.loginOAuth(kakaoLogin());

        assertThatThrownBy(() -> authService.loginLocal(
                new LocalLoginRequest(EMAIL, PASSWORD, "dev-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("카카오");
    }

    // ------------------------------------------------------------------
    // 픽스처
    // ------------------------------------------------------------------

    private void givenKakao(String providerUserId, String email, boolean emailVerified, String nickname) {
        given(kakaoOAuthClient.fetchUserInfo(any()))
                .willReturn(new OAuthUserInfo(providerUserId, email, emailVerified, nickname));
    }

    private OAuthLoginRequest kakaoLogin() {
        return new OAuthLoginRequest(AuthProvider.KAKAO, "code",
                "http://localhost:3001/oauth/kakao", null, "dev-1");
    }

    private void signupLocal(String email, String nickname) {
        givenEmailVerified(email);
        authService.signupLocal(new LocalSignupRequest(email, PASSWORD, nickname, "dev-0"));
    }

    /** 카카오 로그인으로 연동 티켓을 하나 받아 온다. */
    private String linkTokenFromKakaoLogin() {
        givenKakao(KAKAO_ID, EMAIL, true, "카카오닉네임");
        var response = authService.loginOAuth(kakaoLogin()).response();
        assertThat(response.status()).isEqualTo(OAuthLoginResponse.Status.ACCOUNT_LINK_REQUIRED);
        return response.linkToken();
    }

    /** 로그인이 성립했는지까지 함께 확인한다 — null 토큰을 조용히 넘기지 않는다. */
    private AuthTokenResponse authenticated(OAuthLoginResponse response) {
        assertThat(response.status()).isEqualTo(OAuthLoginResponse.Status.AUTHENTICATED);
        return response.tokens();
    }
}
