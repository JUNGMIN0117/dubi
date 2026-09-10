import 'dart:async';
import 'package:flutter/material.dart';
import '../domain/schedule_evaluation.dart';

/// 서버 평가 상태를 보여준다. 임의 신뢰도·정시 도착 보장을 표시하지 않고,
/// 결과 시각/잠정 여부/실패를 구분한다. 수락과 실제 출발 확인은 사용자 동작에서만 보낸다.
class ScheduleEvaluationCard extends StatefulWidget {
  const ScheduleEvaluationCard({super.key, required this.repository, required this.scheduleId});
  final ScheduleEvaluationRepository repository;
  final String scheduleId;
  @override
  State<ScheduleEvaluationCard> createState() => _ScheduleEvaluationCardState();
}

class _ScheduleEvaluationCardState extends State<ScheduleEvaluationCard> {
  Map<String, dynamic>? data;
  String? error;
  bool busy = false;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    _load();
    timer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (!busy && data?['state'] != 'STOPPED') _load();
    });
  }

  @override
  void dispose() { timer?.cancel(); super.dispose(); }

  Future<void> _load() async {
    if (busy) return;
    setState(() => busy = true);
    try {
      final latest = await widget.repository.latestEvaluation(widget.scheduleId);
      if (mounted) setState(() { data = latest; error = null; });
    } catch (_) {
      if (mounted) setState(() => error = '예측을 불러오지 못했습니다. 다시 확인해 주세요.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> _action(Future<void> Function() action) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      await action();
      final latest = await widget.repository.latestEvaluation(widget.scheduleId);
      if (mounted) setState(() { data = latest; error = null; });
    } catch (_) {
      if (mounted) setState(() => error = '처리하지 못했습니다. 최신 예측을 다시 확인해 주세요.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Map _map(dynamic value) => value is Map ? value : const {};
  String _time(dynamic value) {
    final instant = DateTime.tryParse(value?.toString() ?? '');
    if (instant == null) return '확인 불가';
    final kst = instant.toUtc().add(const Duration(hours: 9));
    return '${kst.month}/${kst.day} ${kst.hour.toString().padLeft(2, '0')}:${kst.minute.toString().padLeft(2, '0')}';
  }

  String _stepLabel(Map step) {
    final mode = switch (step['mode']) { 'BUS' => '버스', 'SUBWAY' => '지하철', 'WALK' => '도보', _ => '이동' };
    final vehicles = (step['vehicles'] as List? ?? const []).join(', ');
    return '$mode $vehicles · ${step['fromName']} → ${step['toName']}';
  }

  @override
  Widget build(BuildContext context) {
    final state = data?['state'];
    final result = _map(data?['result']);
    final selected = _map(result['selected']);
    final suggestion = _map(result['suggestion']);
    final proposed = _map(suggestion['proposed']);
    final hasChange = proposed.isNotEmpty && (proposed['journeyKey'] != result['selectedKey'] ||
        proposed['departureAt'] != result['plannedDepartAt']);
    final candidates = result['candidates'] is List ? result['candidates'] as List : const [];
    final proposedInfo = candidates.map(_map).where((c) => c['key'] == proposed['journeyKey']);
    final routeSteps = proposedInfo.isEmpty ? const [] : (proposedInfo.first['steps'] as List? ?? const []);
    final label = switch (state) {
      'PENDING' => '예측을 준비하고 있습니다.',
      'STALE' => '갱신을 기다리고 있습니다. 아래 값은 이전 예측입니다.',
      'DEPARTURE_REQUIRED' => '일정 수정에서 출발 예정시각을 입력해 주세요.',
      'STOPPED' => '이 일정의 출발 전 감시가 종료됐습니다.',
      'FAILED' => '최신 예측을 계산하지 못했습니다. 잠시 후 다시 확인해 주세요.',
      'READY' => switch (selected['assessment']) {
        'ON_TIME_ESTIMATE' => '목표시간 이내 도착 예상',
        'LATE_ESTIMATE' => '목표시간보다 늦게 도착할 것으로 예상됩니다.',
        'BLOCKED' => '현재 경로에 이용 불가 구간이 있습니다.',
        _ => '도착 예측에 필요한 정보가 부족합니다.',
      },
      _ => '예측을 확인하고 있습니다.',
    };
    return Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('이동 예측', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8), Text(label),
        if (error != null) Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        if (result.isNotEmpty) ...[
          Text('입력 출발: ${_time(result['plannedDepartAt'])}'),
          Text('예상 도착: ${_time(selected['estimatedArrivalAt'])}'),
          Text('계산: ${_time(result['checkedAt'])}'),
          const Text('배차와 실제 도착은 달라질 수 있습니다.'),
          if (selected['tentative'] == true) const Text('일부 정보가 없어 경로 API 예상시간을 사용했습니다.'),
          if (hasChange) ...[
            const Divider(),
            Text('제안 출발: ${_time(proposed['departureAt'])}'),
            Text('제안 도착: ${_time(proposed['estimatedArrivalAt'])}'),
            for (final value in routeSteps) Text(_stepLabel(_map(value))),
            FilledButton(onPressed: busy || error != null || state != 'READY' ? null : () => _action(() =>
                widget.repository.acceptEvaluation(widget.scheduleId, result['evaluationId'] as String)),
                child: const Text('이 제안 적용')),
          ],
        ],
        if (busy) const LinearProgressIndicator(),
        if (state != 'STOPPED' && state != 'DEPARTURE_REQUIRED') Wrap(spacing: 8, children: [
          OutlinedButton(onPressed: busy ? null : () => _action(() => widget.repository.refreshEvaluation(widget.scheduleId)),
              child: const Text('다시 계산')),
          TextButton(onPressed: busy ? null : () => _action(() => widget.repository.confirmDeparture(widget.scheduleId)),
              child: const Text('출발했어요')),
        ]),
      ],
    )));
  }
}
