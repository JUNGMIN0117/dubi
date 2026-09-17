import 'dart:async';

import 'package:flutter/material.dart';

import '../domain/schedule_evaluation.dart';

class ScheduleEvaluationCard extends StatefulWidget {
  const ScheduleEvaluationCard({
    super.key,
    required this.repository,
    required this.scheduleId,
  });

  final ScheduleEvaluationRepository repository;
  final String scheduleId;

  @override
  State<ScheduleEvaluationCard> createState() => _ScheduleEvaluationCardState();
}

class _ScheduleEvaluationCardState extends State<ScheduleEvaluationCard>
    with WidgetsBindingObserver {
  ScheduleEvaluationV2? evaluation;
  String? error;
  String? actionError;
  String? notice;
  bool busy = false;
  bool active = true;
  int requestGeneration = 0;
  int retryCount = 0;
  Timer? timer;

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
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    active = state == AppLifecycleState.resumed;
    timer?.cancel();
    if (active) _load();
  }

  bool get _shouldPoll => switch (evaluation?.state) {
    EvaluationState.stopped || EvaluationState.departureRequired => false,
    _ => true,
  };

  void _scheduleNext({required bool failed}) {
    timer?.cancel();
    if (!mounted || !active || !_shouldPoll) return;
    final backoffStep = (retryCount - 1).clamp(0, 2);
    final seconds = failed ? (30 * (1 << backoffStep)) : 30;
    timer = Timer(Duration(seconds: seconds), _load);
  }

  Future<void> _load() async {
    if (busy || !active) return;
    final generation = ++requestGeneration;
    setState(() => busy = true);
    var failed = false;
    try {
      final latest = await widget.repository.latestEvaluation(
        widget.scheduleId,
      );
      if (!mounted || generation != requestGeneration) return;
      if (!_isOlderThanCurrent(latest)) {
        setState(() {
          evaluation = latest;
          error = null;
          retryCount = 0;
        });
      }
    } on ScheduleEvaluationFailure catch (failure) {
      failed = true;
      if (!mounted || generation != requestGeneration) return;
      setState(() {
        retryCount++;
        error = _failureMessage(failure);
      });
    } on Object {
      failed = true;
      if (!mounted || generation != requestGeneration) return;
      setState(() {
        retryCount++;
        error = '예측을 불러오지 못했습니다. 연결 상태를 확인해 주세요.';
      });
    } finally {
      if (mounted && generation == requestGeneration) {
        setState(() => busy = false);
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

  Future<void> _action(Future<void> Function() action) async {
    if (busy) return;
    timer?.cancel();
    setState(() {
      busy = true;
      actionError = null;
      notice = null;
    });
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
        setState(
          () => actionError = '요청을 처리하지 못했습니다. 다시 시도해 주세요.',
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
    if (!mounted) return;
    if (succeeded) setState(() => notice = '서버 처리 결과를 다시 확인했습니다.');
    await _load();
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
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('이동 예측', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          if (evaluation != null)
            ScheduleEvaluationSummary(evaluation: evaluation!),
          if (evaluation == null && error == null)
            const Text('최신 예측을 확인하고 있습니다.'),
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
          if (busy) ...[
            const SizedBox(height: 8),
            const LinearProgressIndicator(),
          ],
        ],
      ),
    ),
  );

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
                : () => _action(
                    () => widget.repository.acceptEvaluation(
                      widget.scheduleId,
                      evaluationId,
                    ),
                  ),
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

class ScheduleEvaluationSummary extends StatelessWidget {
  const ScheduleEvaluationSummary({super.key, required this.evaluation});

  final ScheduleEvaluationV2 evaluation;

  @override
  Widget build(BuildContext context) {
    final stale =
        evaluation.state == EvaluationState.stale ||
        evaluation.isExpiredAt(DateTime.now());
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            if (evaluation.quality.preview) const Chip(label: Text('검증용')),
            if (stale) const Chip(label: Text('갱신 필요')),
            if (evaluation.isLegacy) const Chip(label: Text('구버전 응답')),
          ],
        ),
        Text(
          _stateMessage(evaluation.state),
          key: const Key('evaluationStateMessage'),
        ),
        if (evaluation.state == EvaluationState.ready) ...[
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
        _timeRow('사용자 입력 출발', evaluation.userInputDepartAt),
        _timeRow('현재 계획 출발', evaluation.plannedDepartAt),
        if (evaluation.targetArriveAt != null)
          _timeRow('평가 목표 도착', evaluation.targetArriveAt),
        if (evaluation.proposedDepartAt != null)
          _timeRow('제안 출발', evaluation.proposedDepartAt),
        _timeRow('예상 도착', evaluation.estimatedArrivalAt),
        if (evaluation.computedAt != null)
          _timeRow('계산 시각', evaluation.computedAt),
        if (evaluation.validUntil != null)
          _timeRow('유효 시각', evaluation.validUntil),
        if (evaluation.nextEvaluationAt != null)
          _timeRow('다음 확인', evaluation.nextEvaluationAt),
        const SizedBox(height: 8),
        Text(_qualityMessage(evaluation.quality)),
        if (evaluation.quality.costCoverage != 'COMPLETE')
          const Text('전체 이동시간의 구성요소가 완전히 확인되지 않았습니다.'),
        if (evaluation.quality.missingInputs.isNotEmpty)
          Text(
            '부족한 정보: '
            '${evaluation.quality.missingInputs.map(_inputLabel).join(', ')}',
          ),
        if (evaluation.quality.fallbackReasons.isNotEmpty)
          Text(
            '대체 계산 사용: '
            '${evaluation.quality.fallbackReasons.map(_fallbackLabel).join(', ')}',
          ),
        if (evaluation.decision == DepartureDecision.searchIncomplete &&
            evaluation.bestKnownDecision != null)
          Text(
            '현재까지 확인된 결과: '
            '${_decisionMessage(evaluation.bestKnownDecision!)}',
          ),
        if (evaluation.causes.isNotEmpty) ...[
          const SizedBox(height: 8),
          for (final cause in evaluation.causes) Text(_causeMessage(cause)),
        ],
        if (evaluation.legs.isNotEmpty) ...[
          const Divider(),
          const Text('경로 구간', style: TextStyle(fontWeight: FontWeight.w700)),
          for (final leg in evaluation.legs) Text(_legLabel(leg)),
        ],
        if (!evaluation.canAcceptAt(DateTime.now()) &&
            evaluation.proposedDepartAt != null)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text('검증·유효성 조건을 충족한 제안만 일정에 적용할 수 있습니다.'),
          ),
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
    EvaluationState.pending => '예측을 준비하고 있습니다.',
    EvaluationState.running => '최신 경로와 도착시각을 계산하고 있습니다.',
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
    DepartureDecision.departNow => departureMissed
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
    final realtime = quality.realtimeApplied ? '일부 실시간 정보 반영' : '실시간 정보 미적용';
    return '$walking · $realtime';
  }

  static String _inputLabel(String value) => switch (value) {
    'PLANNED_DEPART_AT' => '출발 예정시각',
    'BUS_ARRIVAL' => '버스 도착 정보',
    'ROUTE_PROVIDER' => '경로 정보',
    _ => value,
  };

  static String _fallbackLabel(String value) => switch (value) {
    'EGRESS_ENDPOINT_UNVERIFIED' ||
    'EGRESS_WALK_FALLBACK' => '이탈 도보는 기존 경로 정보 사용',
    'MODEL_VALIDATION_ONLY' || 'VALIDATION_ONLY' => '검증 중인 모델 결과',
    _ => value,
  };

  static String _legLabel(EvaluationRouteLeg leg) {
    final role = switch (leg.role) {
      RouteLegRole.accessWalk => '접근 도보',
      RouteLegRole.transferWalk => '환승 도보',
      RouteLegRole.egressWalk => '이탈 도보',
      RouteLegRole.ride => leg.mode == 'BUS' ? '버스 탑승' : '지하철 탑승',
      RouteLegRole.wait => '대기',
      RouteLegRole.unknown => leg.roleRaw ?? '이동 구간',
    };
    final source = switch (leg.durationSource) {
      DurationSource.kakaoWalkingApi => '도보 API',
      DurationSource.publicTransitStep => '원본 경로 기준',
      DurationSource.providerTotalFallback => '전체 경로시간 기준',
      DurationSource.realtimeArrival => '실시간 도착예측',
      DurationSource.timetable => '시간표 기준',
      DurationSource.mlPreview => '검증용 모델',
      DurationSource.unknown => leg.durationSourceRaw ?? '출처 확인 중',
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
