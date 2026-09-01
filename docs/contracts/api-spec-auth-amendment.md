# API 명세서 개정 요청 — AUTH-003 소셜 로그인

작성: 백엔드(서비스) 손정민
대상: Notion「DDUBI_APP_API」v2.1.1 § 3.1.3 소셜 로그인

소셜 로그인 4종(카카오·구글·네이버)을 구현하면서 **명세서와 실제 계약이 어긋나는 지점 2건**을 찾았습니다.
아래 문안으로 고쳐 주시면 됩니다.

---

## 1. `state` 필드 추가 (필수 개정)

**네이버 토큰 교환은 `state` 가 필수입니다.** 현재 Request Body 정의에 없어서,
명세서대로 구현하면 네이버 로그인이 100% 실패합니다.

카카오·구글은 `state` 를 쓰지 않으므로 **선택 필드**로 두고, 네이버일 때만 서버가 필수로 검증합니다.
빠지면 네이버를 호출하지 않고 즉시 `400 INVALID_REQUEST` 를 냅니다 — 외부 호출을 낭비하지 않고
원인이 클라이언트 요청임을 분명히 하기 위해서입니다.

### 붙여넣을 표 (기존 Request 표를 이걸로 교체)

| 필드 | 필수 | 허용값 |
| --- | --- | --- |
| `provider` | O | `KAKAO`, `GOOGLE`, `NAVER` |
| `authorizationCode` | O | 제공자에게 받은 일회용 코드 |
| `redirectUri` | O | 사전 등록 URI |
| `state` | △ | **`NAVER` 일 때 필수.** 인가 요청에 쓴 값과 동일해야 한다. 카카오·구글은 무시된다 |
| `deviceId` | X | 미지정 시 서버가 발급 |

### 요청 예시

```json
{
  "provider": "NAVER",
  "authorizationCode": "제공자에게-받은-코드",
  "redirectUri": "http://localhost:3001/oauth/naver",
  "state": "인가요청에-쓴-난수",
  "deviceId": "device-A"
}
```

> 프론트 참고: `state` 는 CSRF 방지용 난수입니다. 인가 URL 을 만들 때 생성해 두었다가
> 콜백으로 돌아온 값과 대조한 뒤, **같은 값을 그대로** 이 API 에 실어 보내면 됩니다.

---

## 2. `SSAFY` 삭제 (문구 정정)

현재 `provider` 허용값에 `SSAFY` 가 적혀 있습니다. **팀 합의로 제외된 항목**이고
ERD_master 의 `user_auth.provider` ENUM 에도 없습니다(`LOCAL, KAKAO, GOOGLE, NAVER`).

같은 문단이 `user.provider` ENUM 을 근거로 들고 있는데, `provider` 는
「안건 합의」에서 `user` → `user_auth` 로 옮겨졌습니다. 즉 이 문단은 `user_auth` 신설 이전에
쓰인 잔재로 보이며, `SSAFY` 도 거기 딸려온 것으로 판단됩니다.

Apple 제외 각주의 `user.provider` 도 `user_auth.provider` 로 바꾸면 정확해집니다.

---

## 참고 — 구현 현황

| provider | 구현 | 실호출 검증 |
| --- | --- | --- |
| `LOCAL` | 완료 | 완료 |
| `KAKAO` | 완료 | 완료(실계정 로그인·계정 통합 확인) |
| `GOOGLE` | 완료 | **콘솔 등록 대기** |
| `NAVER` | 완료 | **콘솔 등록 대기** |

구글·네이버는 코드와 단위 테스트가 끝났고, GCP·네이버 개발자센터 앱 등록과 키 발급만 남았습니다.
키가 없는 상태에서 호출하면 해당 provider 만 `500 INTERNAL_ERROR` 이고 나머지는 정상 동작합니다.

### 계정 통합 시 이메일 인증 여부를 본다 (구현 결정)

도메인 규칙 3(같은 이메일이면 계정 통합)에 **"인증된 이메일에 한한다"** 는 조건을 더했습니다.

제공자가 이메일을 주더라도 미인증이면(`kakao_account.is_email_verified=false`,
구글 `email_verified=false`) 통합하지 않고 별도 계정을 만듭니다.

이유: 미인증 이메일을 신뢰하면 **남의 이메일을 등록한 소셜 계정으로 그 사람의 기존 계정에
그대로 들어갈 수 있습니다.** 통합 키가 이메일 하나뿐이라 이 검사가 없으면 막을 방법이 없습니다.
