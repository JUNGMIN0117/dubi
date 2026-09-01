# DDUBI Client

## Kakao Maps

지도 앱 키는 저장소에 커밋하지 않고 `--dart-define`으로 전달합니다. 키가 없거나 초기화에 실패하면 기존 Mock 지도가 표시됩니다.

Android는 Kakao Developers에 패키지명 `com.ssafy.ddubi`를 등록한 뒤 Native App Key로 실행합니다.

```sh
flutter run -d <android-device-id> --dart-define=KAKAO_NATIVE_APP_KEY=<native-app-key>
```

Web은 Kakao Developers에 실행 도메인을 등록한 뒤 JavaScript Key로 실행합니다. 로컬 개발 포트는 팀에서 동일하게 사용할 수 있도록 `3001`을 권장합니다.

```sh
flutter run -d chrome --web-port=3001 --dart-define=KAKAO_JAVASCRIPT_KEY=<javascript-key>
```

현재 출발·현재·도착 마커는 화면 확인용 강남권 Mock 좌표입니다. 백엔드 경로 API에 좌표와 경로 형상이 추가되면 `lib/presentation/screens.dart`의 `_RouteMapView`에서 교체합니다.

Flutter 코드 하나로 Android 앱과 Web을 함께 개발합니다.

## 실행

```sh
flutter pub get
flutter run -d chrome
```

Android 에뮬레이터에서 로컬 Spring Boot에 연결할 때는 다음처럼 실행합니다.

```sh
flutter run -d <android-device-id> --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
```

Web의 기본 API 주소는 `http://localhost:8080/api/v1`입니다. 다른 환경에서는
`--dart-define=API_BASE_URL=<주소>`로 변경할 수 있습니다.

`build/web`은 Nginx가 제공하는 빌드 산출물이며 Git에 커밋하지 않습니다.

## API와 Mock

백엔드 API가 확정되기 전 사용하는 프론트 임시 계약은
[`../docs/frontend-api-contract.md`](../docs/frontend-api-contract.md)에 정리되어 있습니다.
정상·오류 응답 예시는 `mocks/api/`에서 확인할 수 있습니다.

- API 서버 주소: `lib/core/constants/api_constants.dart`
- API 경로: `lib/core/constants/api_paths.dart`
- Dio 설정: `lib/core/network/dio_provider.dart`
- 실제 HTTP 호출: `lib/services/`
- 현재 화면용 Mock 저장소: `lib/data/mock_repository.dart`
