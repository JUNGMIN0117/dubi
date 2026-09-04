# [백엔드 → 인프라] backend CI 잡 요구사항

작성: 백엔드(서비스) 손정민
대상: `.gitlab-ci.yml` — 현재 `backend/**` 잡이 없습니다(파일 하단 "후속 작업" 항목)

---

## 결정이 필요한 것 하나

**백엔드 통합 테스트는 MySQL 8 + Redis 7 이 떠 있어야 돕니다.**

H2 같은 인메모리 DB 로 대체하지 않습니다. 검증 대상이 MySQL 의 실제 동작이기 때문입니다 —
`ON DELETE CASCADE`(탈퇴 시 하위 전체 삭제), `ENUM`, `UNIQUE` 의 NULL 중복 허용,
`DATETIME` 정밀도. H2 로 바꾸면 통과하는데 운영에서 깨집니다.

지금은 Testcontainers 가 테스트 실행 중에 컨테이너를 직접 띄웁니다. 이러려면 **Docker 데몬**이
필요한데, CI 파일에 "dind(privileged) 러너가 필요해 수동 잡으로 둔다" 고 적혀 있어
**러너가 privileged 를 지원하는지 모르겠습니다.**

### 방식 두 가지

| | A. dind | B. GitLab `services:` |
| --- | --- | --- |
| privileged 러너 | **필요** | 불필요 |
| 실행 비용 | 무거움 (매번 Docker in Docker 기동) | 가벼움 |
| 백엔드 코드 수정 | 없음 | **1곳 필요** (제가 합니다) |

**B 를 권합니다.** privileged 를 안 써도 되고 빠릅니다. 코드 쪽은
"환경변수로 DB 주소가 들어오면 그걸 쓰고, 없으면 지금처럼 Testcontainers" 로 바꾸면 됩니다.
로컬 개발은 지금 그대로 돌아갑니다.

**러너가 privileged 를 지원하는지만 알려주시면** 그에 맞춰 코드를 정리하겠습니다.

---

## B 방식으로 갈 때 필요한 잡 정의

```yaml
backend-test:
  stage: test
  image: eclipse-temurin:17-jdk        # 우리는 Java 17 고정
  services:
    - name: mysql:8.0
      alias: mysql
      command:
        - "--character-set-server=utf8mb4"
        - "--collation-server=utf8mb4_0900_ai_ci"
        - "--default-time-zone=+09:00"   # 서버 시간대가 다르면 시각 비교 테스트가 깨집니다
    - name: redis:7-alpine
      alias: redis
  variables:
    MYSQL_DATABASE: ddubi
    MYSQL_USER: ddubi
    MYSQL_PASSWORD: ddubi
    MYSQL_ROOT_PASSWORD: root
    # 아래 둘이 있으면 테스트가 Testcontainers 대신 이 주소를 씁니다
    CI_MYSQL_HOST: mysql
    CI_REDIS_HOST: redis
  cache:
    key:
      files: [backend/pom.xml]
    paths: [.m2/repository]
  script:
    - cd backend && ./mvnw -B -Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository test
  rules:
    - changes:
        - backend/**/*
        - docs/ddubi_erd_v2.sql          # 스키마 원천이 바뀌면 마이그레이션 검증이 필요합니다
```

### 각 항목이 왜 필요한가

- **`eclipse-temurin:17-jdk`** — 팀 표준이 Java 17 입니다. 21 로 돌리면 로컬과 다른 결과가 날 수 있습니다.
- **MySQL 시간대 `+09:00`** — 우리 시각 컬럼은 KST 기준입니다. UTC 러너에서 그냥 돌리면
  일정 시각 비교 테스트가 9시간 어긋나 실패합니다.
- **`utf8mb4` 설정** — 알림 제목·일정 이름이 한글입니다. 기본 문자셋으로 두면 저장은 되는데
  비교가 어긋납니다.
- **Maven 캐시** — 의존성이 꽤 큽니다(Spring Boot, firebase-admin, Apache POI).
  캐시 없이는 매 실행마다 수백 MB 를 받습니다.
- **`docs/ddubi_erd_v2.sql` 도 트리거** — 이 파일과 `V1__init.sql` 은 항상 같아야 하는데,
  한쪽만 고치는 사고가 실제로 납니다. 스키마 원천이 바뀌면 테스트를 돌려야 합니다.

### 소요 시간 예상

전체 193건 중 통합 테스트가 다수라 **7~10분** 정도 봅니다. 컨테이너 기동과 Flyway 마이그레이션이
대부분입니다. 너무 길면 단위 테스트만 MR 파이프라인에 두고 통합은 `be/dev` 병합 시로 미루는 안도 있습니다.

---

## 비밀값 — 필요 없습니다

**백엔드 CI 에는 어떤 키도 주입하지 않아도 됩니다.** 테스트는 외부 서비스를 부르지 않습니다.

| 키 | CI 에서 |
| --- | --- |
| `KAKAO_*`, `GOOGLE_*`, `NAVER_*` | 소셜 클라이언트를 목으로 대체합니다 |
| `MAIL_*` | 자격증명이 없으면 로그 발송기로 떨어집니다 |
| `FIREBASE_*` | 마찬가지로 로그 발송기 |
| `JWT_SECRET` | 테스트 프로파일에 고정값이 있습니다 |

키가 없을 때 로그로 대체되는 구조를 일부러 그렇게 만들었습니다. CI 에 비밀값을 넣으면
포크 MR 에서 새어 나갈 위험만 생깁니다.

---

## 이미지 빌드는 아직 넣지 마세요

`backend/Dockerfile` 이 아직 없습니다. `infra/` 의 serving 프로필이 어떻게 빌드하는지
확인한 뒤 맞춰 만들겠습니다. 그때 다시 요청드리겠습니다.

---

## 요약 — 답해주실 것

1. **러너가 privileged(dind)를 지원하나요?** → B(services) 로 갈 수 있는지 결정됩니다
2. 위 잡 정의로 넣어주시면 됩니다. 코드 쪽 수정은 제가 하고 알려드리겠습니다
3. 비밀값 주입은 필요 없습니다
