package com.ssafy.ddubi.domain.auth.repository;

import com.ssafy.ddubi.domain.auth.entity.AuthProvider;
import com.ssafy.ddubi.domain.auth.entity.UserAuth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface UserAuthRepository extends JpaRepository<UserAuth, Long> {

    /** 소셜 로그인 1차 조회 — 이미 연결된 소셜 계정인지 본다. */
    Optional<UserAuth> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);

    Optional<UserAuth> findByUserIdAndProvider(Long userId, AuthProvider provider);

    List<UserAuth> findAllByUserId(Long userId);

    boolean existsByUserIdAndProvider(Long userId, AuthProvider provider);

    /**
     * 일반 로그인용. user 를 함께 끌어와 N+1 을 피한다
     * (LAZY 라 그냥 조회하면 user 접근 시 쿼리가 한 번 더 나간다).
     */
    @Query("select ua from UserAuth ua join fetch ua.user u "
            + "where u.email = :email and ua.provider = com.ssafy.ddubi.domain.auth.entity.AuthProvider.LOCAL")
    Optional<UserAuth> findLocalAuthByEmail(String email);

    /** 응답의 providers 배열용. 엔티티를 다 끌어오지 않고 provider 만 뽑는다. */
    @Query("select ua.provider from UserAuth ua where ua.user.id = :userId order by ua.provider")
    List<AuthProvider> findProvidersByUserId(Long userId);
}
