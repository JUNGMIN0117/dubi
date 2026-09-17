import 'package:flutter/foundation.dart';

/// 개발자 진단 화면 표시 여부.
///
/// - 디버그 빌드: 기본 켜짐.
/// - 릴리스 빌드: 기본 꺼짐. `--dart-define=DDUBI_DEVELOPER_DIAGNOSTICS=true` 로 빌드했거나
///   더보기 > 개발자 옵션에서 명시적으로 켠 경우에만 보인다.
/// - 값은 앱 실행 중에만 유지된다(재시작하면 빌드 기본값으로 돌아간다).
///
/// 일반 사용자 화면은 이 값과 무관하게 항상 같은 정보만 보여준다.
class DeveloperDiagnostics {
  DeveloperDiagnostics._();

  static const bool buildDefault = bool.fromEnvironment(
    'DDUBI_DEVELOPER_DIAGNOSTICS',
    defaultValue: kDebugMode,
  );

  static final ValueNotifier<bool> enabled = ValueNotifier<bool>(buildDefault);

  @visibleForTesting
  static void reset() => enabled.value = buildDefault;
}
