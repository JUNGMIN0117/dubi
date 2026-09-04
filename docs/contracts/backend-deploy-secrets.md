# [백엔드 → 인프라] 배포 서버 backend 환경변수 누락

작성: 백엔드(서비스) 손정민 · 2026-09-04

---

## 먼저, 지적하신 내용이 맞습니다

**제가 틀렸습니다.** 프론트 안내 문서에 "서비스 계정 키를 서버에 넣어뒀다" 고 썼는데,
확인해 보니 **제 로컬 개발 환경(`backend/.env`)에만** 있었습니다. 배포 서버는 확인하지
않고 쓴 문장입니다. 프론트 문서도 정정했습니다.

확인해 주셔서 감사합니다. 그런데 **찾아보니 문제가 Firebase 하나가 아니었습니다.**

---

## 실제 상황 — backend 에 전달되는 환경변수가 절반입니다

`infra/docker-compose.yml` 의 `backend` 서비스가 넘기는 값과, backend 가
`application.yml` 에서 읽는 값을 대조했습니다.

| 환경변수 | 전달됨? | 없으면 생기는 일 |
| --- | --- | --- |
| `MYSQL_*` · `REDIS_*` · `JWT_SECRET` | ✅ | — |
| `KAKAO_REST_API_KEY` | ✅ | — |
| **`MAIL_HOST/PORT/USERNAME/PASSWORD`** | ❌ | 🔴 **로컬 회원가입이 불가능합니다** |
| **`GOOGLE_CLIENT_ID/SECRET`** | ❌ | 🔴 구글 로그인 실패 |
| **`NAVER_CLIENT_ID/SECRET`** | ❌ | 🔴 네이버 로그인 실패 |
| `KAKAO_APP_ID` · `KAKAO_CLIENT_SECRET` | ❌ | 🟡 카카오 일부 흐름 |
| **`FIREBASE_CREDENTIALS_BASE64/PATH`** | ❌ | 🟡 푸시 미발송 (보고해 주신 건) |

### 메일이 가장 급합니다

로컬 회원가입은 이메일 인증을 통과해야 진행됩니다(`AuthService:108` `requireVerified`).
`MAIL_USERNAME/PASSWORD` 가 없으면 `MailConfig` 가 **로그 발송기로 대체**해서, 인증코드가
서버 로그에만 남고 사용자에게는 영영 오지 않습니다.

**기동은 정상이고 API 도 200 을 주기 때문에** 겉으로는 멀쩡해 보입니다. 지금 배포 서버에서
회원가입을 시도하면 인증코드 입력 단계에서 막힙니다.

이게 Firebase 와 같은 구조의 문제라서, **키가 없으면 조용히 로그로 떨어지는 설계**가
배포에서는 오히려 문제를 늦게 발견하게 만들었습니다. 제 설계 판단이 CI 에서는 맞았지만
배포에서는 이런 부작용이 있다는 걸 이번에 알았습니다.

---

## `FCM_CREDENTIALS_PATH` 는 이름이 달라서 안 먹습니다

`infra/.env.example` 에 `FCM_CREDENTIALS_PATH` 가 있는데, **backend 가 읽는 이름은
`FIREBASE_CREDENTIALS_PATH`** 입니다. 채워 넣으셨어도 반영되지 않았을 값입니다.
혼동 방지를 위해 예시 파일에서 지우고 올바른 이름으로 바꿨습니다.

---

## 제가 한 것 (MR 로 올립니다)

1. `infra/docker-compose.yml` — `backend` 서비스에 누락된 환경변수 전부 추가
2. `infra/.env.example` — `FCM_CREDENTIALS_PATH` 제거, 올바른 이름들 추가 + 설명

전부 `${VAR:-}` 형태라 **값이 없어도 기동에는 영향이 없습니다.** 지금 배포에 바로 반영해도
깨지는 것이 없습니다.

---

## 부탁드릴 것

**compose 변경분을 반영해 주시고**, 서버1 `infra/.env` 에 값을 채울 수 있게 해 주세요.
**값은 제가 직접 넣겠습니다** — 키를 채팅이나 이슈에 올리지 않으려고 합니다.

서버1 `.env` 에 제가 접근할 방법(계정/경로)만 알려 주시거나, 아니면 GitLab CI/CD Variables
(Masked + Protected)로 주입하는 방식이면 제가 변수만 등록하겠습니다. **후자를 권합니다** —
서버가 늘어나도 한 곳에서 관리되고, 값이 로그에 찍히지 않습니다.

### FCM 은 파일이 아니라 base64 로 주시면 됩니다

볼륨 마운트가 필요 없어서 배포가 간단합니다. backend 가 두 방식을 모두 지원합니다.

```bash
base64 -w0 firebase-service-account.json
```

이 한 줄을 `FIREBASE_CREDENTIALS_BASE64` 에 넣으면 됩니다. 파일을 서버에 올리거나
compose 에 volume 을 추가할 필요가 없습니다.

---

## 반영 후 확인 방법

backend 로그에서 아래 세 줄이 **사라져야** 정상입니다.

```
MAIL_USERNAME/MAIL_PASSWORD 미설정 — 인증 메일을 실제로 보내지 않고 로그에만 남깁니다
FIREBASE_CREDENTIALS_PATH/BASE64 미설정 — 푸시를 실제로 보내지 않고 로그에만 남깁니다
```

FCM 이 제대로 붙으면 대신 이 줄이 뜹니다.

```
FCM 발송 준비 완료
```

`/actuator/health` 는 이 값들과 무관하게 UP 이라 **헬스체크로는 판별되지 않습니다.**
로그를 봐 주세요.
