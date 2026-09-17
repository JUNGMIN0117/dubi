import 'dart:async';

import 'package:flutter/material.dart';

import '../domain/schedule_evaluation.dart';
import 'evaluation_diagnostics_panel.dart';

/// 부족한 입력을 일반 사용자 문구로 옮긴다. 상단 결론 한 줄과 카드가 같은 표현을 쓴다.
String evaluationMissingInputLabel(String value) => switch (value) {
  _ when value.startsWith('BUS:') => '일부 버스 구간의 실시간 정보가 부족해요',
  _ when value.startsWith('SUB:') => '일부 지하철 구간의 운행정보가 부족해요',
  'SUBWAY_TIMETABLE_NOT_CONFIGURED' => '지하철 시간표가 연결되지 않았어요',
  'ML_NOT_CONFIGURED' => '기본 경로 소요시간을 쓰고 있어요',
  'PLANNED_DEPART_AT' => '출발 예정시각이 없어요',
  'BUS_ARRIVAL' => '버스 도착 정보가 부족해요',
  'ROUTE_PROVIDER' => '경로 정보가 부족해요',
  _ => '일부 교통정보를 확인하지 못했어요',
};

class ScheduleEvaluationCard extends StatefulWidget {
  const ScheduleEvaluationCard({
    super.key,
    required this.repository,
    required this.scheduleId,
    this.developerDiagnostics = false,
    this.headline,
  });

  final ScheduleEvaluationRepository repository;
  final String scheduleId;

  /// 화면 위쪽 결론 한 줄에 같은 결과를 전달하는 통로. 없으면 카드만 그린다.
  final ValueNotifier<ScheduleEvaluationV2?>? headline;

  /// 개발자 진단 패널 표시 여부. 디버그 빌드나 더보기 > 개발자 옵션에서만 켜진다.
  final bool developerDiagnostics;

  /// 서버의 첫 계산 예산(30초)에 맞춘 안내. 상한이며 결과가 오면 즉시 바꾼다.
  static const String initialComputingMessage =
      '추천 경로를 비교하고 있어요. 최대 30초 정도 걸릴 수 있어요.';

  /// 수동 다시 계산 예산(10초)에 맞춘 안내. 상한이며 결과가 오면 즉시 바꾼다.
  static const String manualRefreshMessage =
      '최신 교통정보로 다시 계산하고 있어요. 최대 10초 정도 걸릴 수 있어요.';

  /// 안내한 상한(첫 계산 30초·수동 10초)을 넘겨도 끝나지 않으면 이 문구로 바꾼다.
  /// 서버 응답 대기는 더 길 수 있으므로(수동 요청 HTTP 대기 30초) "최대 10초" 문구를 그대로 두지 않는다.
  static const String overdueMessage =
      '예상보다 오래 걸리고 있어요. 결과가 나오면 바로 보여드릴게요.';
  static const Duration manualRefreshBound = Duration(seconds: 10);
  static const Duration initialComputingBound = Duration(seconds: 30);

  /// 계산 중(결과 없음·PENDING·RUNNING)에는 짧게, 결과가 있으면 길게 확인한다.
  static const Duration computingPollInterval = Duration(seconds: 3);
  static const Duration idlePollInterval = Duration(seconds: 30);

  /// 빠른 확인 횟수 상한(3초 × 15회 = 45초 ≒ 초기 예산 30초 + 여유). 넘으면 보통 간격으로 돌아간다.
  static const int maxFastPolls = 15;

  @override
  State<ScheduleEvaluationCard> createState() => _ScheduleEvaluationCardState();
}

class _ScheduleEvaluationCardState extends State<ScheduleEvaluationCard>
    with WidgetsBindingObserver {
  ScheduleEvaluationV2? evaluation;
  String? error;
  String? actionError;
  String? notice;

  /// 사용자 동작(다시 계산·적용·출발) 진행 안내. 자동 갱신에는 쓰지 않는다.
  String? progressMessage;

  /// 사용자 동작 처리 중. 버튼을 잠그고 진행 표시를 띄운다.
  bool busy = false;

  /// 서버 결과 조회 중. 자동 갱신은 이 상태에서도 로딩 UI를 띄우지 않는다.
  bool loading = false;
  bool active = true;
  int requestGeneration = 0;
  int retryCount = 0;
  int fastPolls = 0;

  /// 마지막 조회 실패 종류. 404(아직 결과 없음)만 "계산 중"으로 본다. 연결 실패는 30초 backoff 를 유지한다.
  EvaluationFailureKind? lastFailure;
  Timer? timer;

  /// 사용자 동작 안내가 상한(10초)을 넘겼을 때 문구를 바꾸는 타이머.
  Timer? progressTimer;

  /// 첫 계산 안내 상한(30초)을 넘겼는지. 빠른 확인 횟수(3초 간격)로 계산한다.
  bool get _initialOverdue =>
      fastPolls * ScheduleEvaluationCard.computingPollInterval.inSeconds >=
      ScheduleEvaluationCard.initialComputingBound.inSeconds;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    requestGeneration++;
    timer?.cancel();
    progressTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    active = state == AppLifecycleState.resumed;
    timer?.cancel();
    if (active) _load(force: true);
  }

  bool get _shouldPoll => switch (evaluation?.state) {
    EvaluationState.stopped || EvaluationState.departureRequired => false,
    _ => true,
  };

  /// 서버가 첫 결과를 계산 중인 상태: PENDING/RUNNING, 또는 결과가 아직 없어 404 를 받은 경우.
  /// 연결 실패나 다른 오류는 계산 중으로 보지 않는다(서버를 빠르게 재호출하지 않는다).
  bool get _computing => switch (evaluation?.state) {
    EvaluationState.pending || EvaluationState.running => true,
    null =>
      lastFailure == null || lastFailure == EvaluationFailureKind.notFound,
    _ => false,
  };

  void _scheduleNext({required bool failed}) {
    timer?.cancel();
    if (!mounted || !active || !_shouldPoll) return;
    if (!_computing) fastPolls = 0;
    final fast =
        _computing && fastPolls < ScheduleEvaluationCard.maxFastPolls;
    if (fast) fastPolls++;
    final base = fast
        ? ScheduleEvaluationCard.computingPollInterval
        : ScheduleEvaluationCard.idlePollInterval;
    final backoffStep = (retryCount - 1).clamp(0, 2);
    final interval = failed ? base * (1 << backoffStep) : base;
    timer = Timer(interval, _load);
  }

  Future<void> _load({bool force = false}) async {
    if (!active) return;
    if (loading && !force) return;
    final generation = ++requestGeneration;
    setState(() => loading = true);
    var failed = false;
    try {
      final latest = await widget.repository.latestEvaluation(
        widget.scheduleId,
      );
      if (!mounted || generation != requestGeneration) return;
      lastFailure = null;
      if (!_isOlderThanCurrent(latest)) {
        setState(() {
          evaluation = latest;
          error = null;
          retryCount = 0;
        });
        // 상단 결론 한 줄은 이 카드가 읽은 결과를 그대로 쓴다. 따로 조회하지 않는다.
        widget.headline?.value = latest;
      }
    } on ScheduleEvaluationFailure catch (failure) {
      failed = true;
      if (!mounted || generation != requestGeneration) return;
      lastFailure = failure.kind;
      setState(() {
        retryCount++;
        // 일정을 막 만든 직후에는 첫 결과가 나오기 전까지 404가 온다. 계산 중 안내를 유지한다.
        final stillComputing =
            failure.kind == EvaluationFailureKind.notFound &&
            evaluation == null &&
            fastPolls < ScheduleEvaluationCard.maxFastPolls;
        error = stillComputing ? null : _failureMessage(failure);
      });
    } on Object {
      failed = true;
      if (!mounted || generation != requestGeneration) return;
      lastFailure = EvaluationFailureKind.unknown;
      setState(() {
        retryCount++;
        error = '예측을 불러오지 못했습니다. 연결 상태를 확인해 주세요.';
      });
    } finally {
      if (mounted && generation == requestGeneration) {
        setState(() => loading = false);
        _scheduleNext(failed: failed);
      }
    }
  }

  bool _isOlderThanCurrent(ScheduleEvaluationV2 next) {
    final current = evaluation;
    if (current == null) return false;
    final currentInput = current.inputVersion;
    final nextInput = next.inputVersion;
    if (currentInput != null && nextInput != null && nextInput < currentInput) {
      return true;
    }
    if (currentInput == nextInput &&
        current.computedAt != null &&
        next.computedAt != null &&
        next.computedAt!.isBefore(current.computedAt!)) {
      return true;
    }
    return false;
  }

  Future<void> _action(
    Future<void> Function() action, {
    String? progress,
  }) async {
    if (busy) return;
    timer?.cancel();
    progressTimer?.cancel();
    setState(() {
      busy = true;
      actionError = null;
      notice = null;
      progressMessage = progress;
    });
    if (progress != null) {
      // 안내한 상한을 넘기면 문구를 바꿔 "최대 10초"가 거짓이 되지 않게 한다.
      progressTimer = Timer(ScheduleEvaluationCard.manualRefreshBound, () {
        if (mounted && busy) {
          setState(
            () => progressMessage = ScheduleEvaluationCard.overdueMessage,
          );
        }
      });
    }
    var stale = false;
    var succeeded = false;
    try {
      await action();
      succeeded = true;
    } on ScheduleEvaluationFailure catch (failure) {
      stale = failure.kind == EvaluationFailureKind.staleEvaluation;
      if (mounted) {
        setState(() {
          if (stale) {
            error = null;
            notice = '추천이 변경됐습니다. 최신 결과를 다시 확인해 주세요.';
          } else {
            actionError = _failureMessage(failure);
          }
        });
      }
    } on Object {
      if (mounted) {
        setState(() => actionError = '요청을 처리하지 못했습니다. 다시 시도해 주세요.');
      }
    } finally {
      progressTimer?.cancel();
      if (mounted) {
        setState(() {
          busy = false;
          progressMessage = null;
        });
      }
    }
    if (!mounted) return;
    if (succeeded) setState(() => notice = '서버 처리 결과를 다시 확인했습니다.');
    await _load(force: true);
  }

  String _failureMessage(ScheduleEvaluationFailure failure) =>
      switch (failure.kind) {
        EvaluationFailureKind.rateLimited => '요청이 많아 잠시 후 자동으로 다시 확인합니다.',
        EvaluationFailureKind.dependencyUnavailable =>
          '교통 데이터 연결이 불안정해 잠시 후 다시 확인합니다.',
        EvaluationFailureKind.authentication => '로그인 또는 일정 접근 권한을 확인해 주세요.',
        EvaluationFailureKind.notFound => '아직 생성된 평가 결과가 없습니다.',
        EvaluationFailureKind.invalidResponse => '서버 응답 형식을 확인할 수 없습니다.',
        _ => failure.message,
      };

  @override
  Widget build(BuildContext context) {
    final initialComputing = evaluation == null && error == null;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('이동 예측', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (evaluation != null)
              ScheduleEvaluationSummary(
                evaluation: evaluation!,
                developerDiagnostics: widget.developerDiagnostics,
              ),
            if (initialComputing)
              Text(
                _initialOverdue
                    ? ScheduleEvaluationCard.overdueMessage
                    : ScheduleEvaluationCard.initialComputingMessage,
                key: const Key('evaluationInitialProgress'),
              ),
            // 서버가 PENDING/RUNNING 을 30초 넘게 유지하면 상한 문구가 거짓이 되지 않도록 덧붙인다.
            if (evaluation != null && _computing && _initialOverdue) ...[
              const SizedBox(height: 8),
              const Text(
                ScheduleEvaluationCard.overdueMessage,
                key: Key('evaluationOverdueNotice'),
              ),
            ],
            if (progressMessage != null) ...[
              const SizedBox(height: 8),
              Text(progressMessage!, key: const Key('evaluationActionProgress')),
            ],
            if (error != null) ...[
              const SizedBox(height: 8),
              Text(
                error!,
                key: const Key('evaluationError'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (actionError != null) ...[
              const SizedBox(height: 8),
              Text(
                actionError!,
                key: const Key('evaluationActionError'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            if (notice != null) ...[
              const SizedBox(height: 8),
              Text(notice!, key: const Key('evaluationNotice')),
            ],
            if (evaluation != null) ...[
              const SizedBox(height: 10),
              _actions(evaluation!),
            ],
            // 진행 표시는 첫 결과를 기다릴 때와 사용자 동작 중에만 띄운다. 자동 갱신은 조용히 처리한다.
            if (busy || initialComputing) ...[
              const SizedBox(height: 8),
              const LinearProgressIndicator(),
            ],
          ],
        ),
      ),
    );
  }

  Widget _actions(ScheduleEvaluationV2 value) {
    final canAccept = value.canAcceptAt(DateTime.now());
    final evaluationId = value.evaluationId;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        if (value.proposedDepartAt != null)
          FilledButton(
            key: const Key('acceptEvaluationButton'),
            onPressed:
                busy ||
                    !canAccept ||
                    evaluationId == null ||
                    evaluationId.isEmpty
                ? null
                : () {
                    if (!value.canAcceptAt(DateTime.now())) {
                      setState(() => notice = '제안이 만료됐습니다. 최신 결과를 확인해 주세요.');
                      _load(force: true);
                      return;
                    }
                    _action(
                      () => widget.repository.acceptEvaluation(
                        widget.scheduleId,
                        evaluationId,
                      ),
                    );
                  },
            child: const Text('이 제안 적용'),
          ),
        if (value.state != EvaluationState.stopped &&
            value.state != EvaluationState.departureRequired)
          OutlinedButton(
            onPressed: busy
                ? null
                : () => _action(
                    () =>
                        widget.repository.refreshEvaluation(widget.scheduleId),
                    progress: ScheduleEvaluationCard.manualRefreshMessage,
                  ),
            child: const Text('다시 계산'),
          ),
        if (value.state != EvaluationState.stopped &&
            value.state != EvaluationState.departureRequired)
          TextButton(
            onPressed: busy
                ? null
                : () => _action(
                    () => widget.repository.confirmDeparture(widget.scheduleId),
                  ),
            child: const Text('출발했어요'),
          ),
      ],
    );
  }
}

class ScheduleEvaluationSummary extends StatefulWidget {
  const ScheduleEvaluationSummary({
    super.key,
    required this.evaluation,
    this.compact = false,
    this.developerDiagnostics = false,
  });

  final ScheduleEvaluationV2 evaluation;
  final bool compact;

  /// 일반 사용자 화면에는 번역된 문구만, 개발자 진단이 켜진 경우에만 원본 코드·버전·유효 시각을 덧붙인다.
  final bool developerDiagnostics;

  @override
  State<ScheduleEvaluationSummary> createState() => _SummaryState();
}

class _SummaryState extends State<ScheduleEvaluationSummary> {
  ScheduleEvaluationV2 get evaluation => widget.evaluation;
  Timer? _expiry;

  /// 보조 시각(입력·계획·목표·마지막 확인)을 펼쳤는지. 기본은 접힘이다.
  bool _showTimeDetails = false;

  /// 접어둘 시각이 하나라도 있는지. 없으면 "자세히" 자체를 띄우지 않는다.
  /// 상세 화면(compact=false)은 입력·계획 출발을 "확인 불가"로라도 항상 보여준다.
  bool get _hasFoldedTimes =>
      !widget.compact ||
      evaluation.targetArriveAt != null ||
      evaluation.computedAt != null;

  @override
  void initState() {
    super.initState();
    _scheduleExpiry();
  }

  @override
  void didUpdateWidget(covariant ScheduleEvaluationSummary oldWidget) {
    super.didUpdateWidget(oldWidget);
    _scheduleExpiry();
  }

  void _scheduleExpiry() {
    _expiry?.cancel();
    final until = evaluation.validUntil;
    if (until != null && until.isAfter(DateTime.now())) {
      _expiry = Timer(
        until.difference(DateTime.now()) + const Duration(milliseconds: 1),
        () {
          if (mounted) setState(() {});
        },
      );
    }
  }

  @override
  void dispose() {
    _expiry?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final usable = evaluation.canRecommendAt(DateTime.now());
    final historicalEstimate = evaluation.canShowHistoricalEstimateAt(
      DateTime.now(),
    );
    final stale =
        evaluation.state == EvaluationState.stale ||
        evaluation.isExpiredAt(DateTime.now());
    final diagnostics = widget.developerDiagnostics;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 짧은 배지는 뜻이 전달되지 않아 문장으로 바꿨다. 판단 기준은 그대로다.
        if (evaluation.quality.preview ||
            stale ||
            (diagnostics && evaluation.isLegacy) ||
            evaluation.hasHistoricalReference) ...[
          for (final note in [
            if (evaluation.quality.preview) '아직 시험 중인 예측이에요.',
            if (stale) '결과가 오래됐어요. 다시 계산해 주세요.',
            if (evaluation.hasHistoricalReference) '일부 구간은 과거 평균으로 계산했어요.',
            if (diagnostics && evaluation.isLegacy) '구버전 응답이에요.',
          ])
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                note,
                style: const TextStyle(fontSize: 12, color: Color(0xFF5A6B85)),
              ),
            ),
          const SizedBox(height: 4),
        ],
        Text(
          stale
              ? '이전 결과입니다. 최신 결과를 다시 확인해 주세요.'
              : evaluation.state == EvaluationState.ready && !usable
              ? '정확한 출발 추천을 아직 확정하지 못했어요.'
              : _stateMessage(evaluation.state),
          key: const Key('evaluationStateMessage'),
        ),
        if (usable) ...[
          const SizedBox(height: 6),
          Text(
            _decisionMessage(
              evaluation.decision,
              departureMissed: evaluation.departureMissed,
            ),
            key: const Key('evaluationDecisionMessage'),
          ),
        ],
        const SizedBox(height: 8),
        // 기본은 두 줄(제안 출발·예상 도착)만 남긴다. 나머지 시각은 "자세히"로 접는다.
        if (usable && evaluation.proposedDepartAt != null)
          _timeRow('제안 출발', evaluation.proposedDepartAt),
        if (usable) _timeRow('예상 도착', evaluation.estimatedArrivalAt),
        if (!usable && historicalEstimate) ...[
          _timeRow('과거 평균 기반 참고 출발', evaluation.proposedDepartAt),
          _timeRow('과거 평균 기반 참고 도착', evaluation.estimatedArrivalAt),
          const Text('과거 자료로 계산한 참고 추정입니다. 현재 운행상황이나 정시 도착을 보장하지 않습니다.'),
        ],
        if (_hasFoldedTimes) ...[
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              key: const Key('evaluationTimeDetailsToggle'),
              onPressed: () => setState(() => _showTimeDetails = !_showTimeDetails),
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 4),
                minimumSize: Size.zero,
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(_showTimeDetails ? '자세히 접기' : '자세히'),
            ),
          ),
          if (_showTimeDetails) ...[
            if (!widget.compact) ...[
              _timeRow('사용자 입력 출발', evaluation.userInputDepartAt),
              _timeRow('현재 계획 출발', evaluation.plannedDepartAt),
            ],
            if (evaluation.targetArriveAt != null)
              _timeRow('평가 목표 도착', evaluation.targetArriveAt),
            if (evaluation.computedAt != null)
              _timeRow('마지막 확인', evaluation.computedAt),
            if (!widget.compact && diagnostics && evaluation.validUntil != null)
              _timeRow('유효 시각', evaluation.validUntil),
            if (!widget.compact &&
                diagnostics &&
                evaluation.nextEvaluationAt != null)
              _timeRow('다음 확인', evaluation.nextEvaluationAt),
          ],
        ],
        const SizedBox(height: 8),
        Text(_qualityMessage(evaluation.quality)),
        if (evaluation.hasHistoricalReference)
          const Text('버스 일부 구간에 과거 평균 소요시간을 사용했습니다. 실시간 정보나 ML 예측값이 아닙니다.'),
        if (evaluation.quality.costCoverage != 'COMPLETE')
          const Text('전체 이동시간의 구성요소가 완전히 확인되지 않았습니다.'),
        if (evaluation.quality.missingInputs.isNotEmpty)
          Text(
            '부족한 정보: '
            '${_groupMissing(evaluation.quality.missingInputs)}',
          ),
        if (!widget.compact && evaluation.quality.fallbackReasons.isNotEmpty)
          Text(
            '대체 계산 사용: '
            '${evaluation.quality.fallbackReasons.map(_fallbackLabel).toSet().join(', ')}',
          ),
        if (evaluation.decision == DepartureDecision.searchIncomplete)
          const Text('아직 최신 출발시각을 확정하지 못했어요. 확인된 후보는 참고용입니다.'),
        if (!widget.compact && evaluation.causes.isNotEmpty) ...[
          const SizedBox(height: 8),
          for (final cause in evaluation.causes) Text(_causeMessage(cause)),
        ],
        if (!widget.compact && evaluation.legs.isNotEmpty) ...[
          const Divider(),
          const Text('경로 구간', style: TextStyle(fontWeight: FontWeight.w700)),
          for (final leg in evaluation.legs) Text(_legLabel(leg)),
        ],
        if (!widget.compact &&
            !evaluation.canAcceptAt(DateTime.now()) &&
            evaluation.proposedDepartAt != null)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text('검증·유효성 조건을 충족한 제안만 일정에 적용할 수 있습니다.'),
          ),
        if (!widget.compact && diagnostics)
          EvaluationDiagnosticsPanel(evaluation: evaluation),
      ],
    );
  }

  Widget _timeRow(String label, DateTime? value) =>
      Text('$label: ${value == null ? '확인 불가' : _formatDateTime(value)}');

  static String _formatDateTime(DateTime value) {
    final local = value.toLocal();
    return '${local.month}/${local.day} '
        '${local.hour.toString().padLeft(2, '0')}:'
        '${local.minute.toString().padLeft(2, '0')}';
  }

  static String _causeMessage(EvaluationCause cause) {
    final description = cause.description?.trim();
    if (description != null && description.isNotEmpty) return description;

    final effect = cause.effect?.toUpperCase();
    if (cause.confirmed == true && effect == 'BLOCKED') {
      return '이동 경로의 통제가 확인되었습니다.';
    }

    final evidenceLevel = cause.evidenceLevel?.toUpperCase();
    if (evidenceLevel == 'FULL' || evidenceLevel == 'EXACT') {
      return '이동 영향 원인이 확인되었습니다.';
    }
    return '이동 구간에 영향 가능성이 있습니다.';
  }

  static String _stateMessage(EvaluationState state) => switch (state) {
    // 서버가 첫 계산(예산 30초) 중이다. 상한 안내이며 결과가 오면 즉시 바꾼다.
    EvaluationState.pending ||
    EvaluationState.running => ScheduleEvaluationCard.initialComputingMessage,
    EvaluationState.ready => '평가가 완료됐습니다.',
    EvaluationState.failed => '최신 예측을 계산하지 못했습니다.',
    EvaluationState.stopped => '이 일정의 출발 전 감시가 종료됐습니다.',
    EvaluationState.departureRequired => '일정 수정에서 출발 예정시각을 입력해 주세요.',
    EvaluationState.stale => '이전 결과입니다. 최신 결과를 다시 확인해 주세요.',
    EvaluationState.unknown => '평가 상태를 확인하고 있습니다.',
  };

  static String _decisionMessage(
    DepartureDecision decision, {
    bool departureMissed = false,
  }) => switch (decision) {
    DepartureDecision.keepDeparture => '현재 출발 계획을 유지해도 일정 전에 도착할 것으로 예상됩니다.',
    DepartureDecision.advanceDeparture => '제안 시각에 출발하면 일정 10분 전 도착이 예상됩니다.',
    DepartureDecision.departNow =>
      departureMissed
          ? '계획한 출발시각이 지났습니다. 현재 가능한 경로를 확인하세요.'
          : '일정에 맞추려면 지금 출발해야 합니다.',
    DepartureDecision.marginUnavailable => '10분 여유 확보는 어렵지만 일정 전 도착 가능한 경로입니다.',
    DepartureDecision.lateExpected => '현재 확인 가능한 경로로는 일정 시작 이후 도착이 예상됩니다.',
    DepartureDecision.dataInsufficient => '최신 도착 예측에 필요한 정보가 부족합니다.',
    DepartureDecision.routeUnavailable => '현재 이용 가능한 경로를 확인하지 못했습니다.',
    DepartureDecision.searchIncomplete =>
      '제한 시간 안에 탐색을 완료하지 못했습니다. 확인된 대안만 표시합니다.',
    DepartureDecision.unknown => '결과의 의미를 확인 중입니다.',
  };

  static String _qualityMessage(EvaluationQuality quality) {
    final walking = switch (quality.walkingApiCoverage) {
      'BOTH' => '접근·이탈 도보 경로 반영',
      'ACCESS_ONLY' => '접근 도보 경로만 반영',
      'EGRESS_ONLY' => '이탈 도보 경로만 반영',
      _ => '기존 대중교통 도보 정보 사용',
    };
    final realtime = quality.realtimeApplied
        ? '일부 구간에 실시간 정보 반영'
        : '실시간 정보 미적용';
    return '$walking · $realtime';
  }

  static String _groupMissing(List<String> inputs) {
    final groups = <String, int>{};
    for (final input in inputs.toSet()) {
      final label = evaluationMissingInputLabel(input);
      groups[label] = (groups[label] ?? 0) + 1;
    }
    return groups.entries
        .map(
          (entry) =>
              entry.value > 1 ? '${entry.key} (${entry.value}건)' : entry.key,
        )
        .join(', ');
  }

  /// 일반 사용자용 번역. ML 실패와 과거 자료 부족을 같은 문구로 합치지 않는다.
  static String _fallbackLabel(String value) =>
      historicalReferenceReasonLabel(value) ??
      switch (value) {
        _ when value.contains('HISTORICAL_REFERENCE_APPLIED') =>
          '버스 과거 평균 소요시간 적용',
        _ when value.contains('HISTORICAL_REFERENCE_WEATHER_UNKNOWN') =>
          '과거 평균에 현재 날씨 미반영',
        _
            when value.contains('HISTORICAL_REFERENCE_DATA_VERSION') ||
                value.contains('HISTORICAL_REFERENCE_SAMPLE_KIND') =>
          '사용한 과거 자료의 출처 기록',
        _ when value.contains('HISTORICAL_REFERENCE_') =>
          '과거 자료 적용 조건이 맞지 않아 기본 경로시간 유지',
        _ when value.contains('ML_APPLIED') => 'ML 예측 소요시간 적용',
        _ when value.contains('ML_TEST_APPLIED') => '검증 중인 ML 예측값을 시험 적용',
        _ when value.contains('ML_SHADOW_RECORDED') => 'ML 예측값은 기록만 하고 미사용',
        _ when value.contains('ML_NOT_APPLIED:ML_BUDGET_EXHAUSTED') ||
                value.contains('ML_BUDGET_EXHAUSTED') =>
          '계산 시간이 부족해 ML 예측 미적용',
        _ when value.contains('ML_NOT_APPLIED:ML_TIMEOUT') ||
                value.contains('ML_TIMEOUT') =>
          'ML 응답 지연으로 예측 미적용',
        _ when value.contains('ML_NOT_APPLIED') => 'ML 예측 미적용',
        _ when value.contains('ML_MODEL_VERSION') ||
                value.contains('ML_MIN_HISTORY_SAMPLES') ||
                value.contains('ML_MAX_MISSING_FEATURES') ||
                value.contains('ML_HISTORY_INSUFFICIENT') =>
          'ML 예측 근거 기록',
        _ when value.endsWith('API_FALLBACK') => '경로 API 원본 소요시간 사용',
        'EGRESS_ENDPOINT_UNVERIFIED' ||
        'EGRESS_WALK_FALLBACK' => '이탈 도보는 기존 경로 정보 사용',
        'MODEL_VALIDATION_ONLY' || 'VALIDATION_ONLY' => '검증 중인 모델 결과',
        _ => '일부 구간은 기본 경로 정보 사용',
      };

  static String _legLabel(EvaluationRouteLeg leg) {
    final role = switch (leg.role) {
      RouteLegRole.accessWalk => '접근 도보',
      RouteLegRole.transferWalk => '환승 도보',
      RouteLegRole.egressWalk => '이탈 도보',
      RouteLegRole.ride => leg.mode == 'BUS' ? '버스 탑승' : '지하철 탑승',
      RouteLegRole.wait => '대기',
      RouteLegRole.unknown => '이동 구간',
    };
    final source = switch (leg.durationSource) {
      DurationSource.kakaoWalkingApi => '도보 API',
      DurationSource.publicTransitStep => '원본 경로 기준',
      DurationSource.providerTotalFallback => '전체 경로시간 기준',
      DurationSource.realtimeArrival => '실시간 도착예측',
      DurationSource.timetable => '시간표 기준',
      DurationSource.mlPreview => '검증용 모델',
      DurationSource.mlProduction => 'ML 예측(운영 계약)',
      DurationSource.busHistoricalReference => '과거 버스 평균 기준 · 실시간 아님',
      DurationSource.unknown => '출처 확인 중',
    };
    final endpoints = [
      leg.fromName,
      leg.toName,
    ].whereType<String>().where((value) => value.isNotEmpty).join(' → ');
    final duration = leg.durationSeconds == null
        ? ''
        : ' · ${(leg.durationSeconds! / 60).ceil()}분';
    return '$role${endpoints.isEmpty ? '' : ' · $endpoints'}$duration · $source';
  }
}
