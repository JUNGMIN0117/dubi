/// 일정 하나에 대한 "오늘의 이동 변수".
///
/// 네 칸(날씨·혼잡도·도로 통제·지하철)이 **각각 따로** 값 있음/없음을 들고 있다.
/// 하나로 묶으면 둘 중 하나가 된다 — 날씨만 들어와도 네 칸이 모두 "확인 안 됨"이 되거나,
/// 반대로 빈 칸을 0 이나 "맑음" 으로 채워 사용자가 없는 정보를 믿게 된다. 둘 다 안 된다.
///
/// 서버 조회 API 는 아직 없다(2026-09-22). 그때까지 이 화면은 네 칸을 모두 "확인 안 됨"
/// 으로 그린다. 예전처럼 한 줄로 뭉뚱그리면 무엇이 되고 무엇이 안 되는지 알 수 없었다.
class TravelConditions {
  const TravelConditions({
    this.weather = const TravelConditionItem.unavailable(),
    this.congestion = const TravelConditionItem.unavailable(),
    this.roadBlock = const TravelConditionItem.unavailable(),
    this.subwayDelay = const TravelConditionItem.unavailable(),
    this.observedAt,
  });

  /// 아무것도 확인되지 않은 상태. 조회 API 가 붙기 전의 기본값이다.
  static const TravelConditions unknown = TravelConditions();

  final TravelConditionItem weather;
  final TravelConditionItem congestion;
  final TravelConditionItem roadBlock;
  final TravelConditionItem subwayDelay;

  /// 서버가 값을 관측한 시각. 앱이 받은 시각이 아니다.
  final DateTime? observedAt;

  /// 네 칸 중 하나라도 값이 있으면 참.
  bool get hasAny =>
      weather.available ||
      congestion.available ||
      roadBlock.available ||
      subwayDelay.available;

  /// 서버 응답이 어떤 모양이든 화면이 깨지지 않게 받는다. 모르는 칸은 "확인 안 됨"이다.
  ///
  /// 서버 계약이 아직 확정되지 않았으므로(종무형 작업) 키 이름이 바뀔 수 있다. 바뀌어도
  /// 여기 한 곳만 고치면 되도록 파싱을 모아 둔다.
  factory TravelConditions.fromJson(Object? value) {
    if (value is! Map) return unknown;
    return TravelConditions(
      weather: TravelConditionItem.fromJson(value['weather']),
      congestion: TravelConditionItem.fromJson(value['congestion']),
      roadBlock: TravelConditionItem.fromJson(value['roadBlock']),
      subwayDelay: TravelConditionItem.fromJson(value['subwayDelay']),
      observedAt: _parseTime(value['observedAt']),
    );
  }

  static DateTime? _parseTime(Object? value) =>
      value is String ? DateTime.tryParse(value) : null;
}

/// 이동 변수 한 칸.
///
/// [text] 가 null 이면 "확인 안 됨"이다. 빈 문자열도 값 없음으로 본다 — 서버가 실수로
/// 빈 문자열을 보냈을 때 화면에 빈칸이 덩그러니 남는 것보다 낫다.
class TravelConditionItem {
  const TravelConditionItem.value(String this.text, {this.note});

  const TravelConditionItem.unavailable() : text = null, note = null;

  /// 사용자에게 보여줄 값. 예: `20.9℃ · 비 안 옴`
  final String? text;

  /// 값 아래 작게 붙는 덧말. 예: 어느 지역 기준인지
  final String? note;

  bool get available => text != null && text!.isNotEmpty;

  /// 값이 문자열이면 그대로, 객체면 `text`/`note` 를 읽는다.
  ///
  /// `available: false` 를 명시로 보내는 경우와 칸 자체가 없는 경우를 똑같이 다룬다.
  /// 서버가 둘 중 어느 쪽으로 보낼지 아직 정해지지 않았다.
  factory TravelConditionItem.fromJson(Object? value) {
    if (value is String) {
      return value.isEmpty
          ? const TravelConditionItem.unavailable()
          : TravelConditionItem.value(value);
    }
    if (value is! Map) return const TravelConditionItem.unavailable();
    if (value['available'] == false) return const TravelConditionItem.unavailable();
    final text = value['text'] ?? value['summary'] ?? value['label'];
    if (text is! String || text.isEmpty) {
      return const TravelConditionItem.unavailable();
    }
    final note = value['note'];
    return TravelConditionItem.value(
      text,
      note: note is String && note.isNotEmpty ? note : null,
    );
  }
}

/// 이동 변수를 가져오는 통로.
///
/// 서버 API 가 생기면 이 인터페이스만 구현해서 화면에 넘기면 된다. 화면은 이 통로가
/// 없어도(=null) 동작한다.
abstract class TravelConditionsService {
  Future<TravelConditions> conditionsFor(String scheduleId);
}
