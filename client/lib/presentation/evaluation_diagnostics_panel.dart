import 'package:flutter/material.dart';

import '../domain/evaluation_diagnostics.dart';
import '../domain/schedule_evaluation.dart';

/// 개발자 진단 패널. 디버그 빌드 또는 더보기 > 개발자 옵션을 켠 경우에만 화면에 붙는다.
///
/// 일반 사용자 화면과 달리 원본 코드를 번역하지 않고 그대로 보여준다.
/// 단계별 처리 시간은 v2 응답 계약에 없어 표시하지 않는다(서버 로그 `평가 단계 소요` 참고).
class EvaluationDiagnosticsPanel extends StatelessWidget {
  const EvaluationDiagnosticsPanel({super.key, required this.evaluation});

  final ScheduleEvaluationV2 evaluation;

  @override
  Widget build(BuildContext context) {
    final diagnostics = EvaluationDiagnostics.from(evaluation);
    final quality = evaluation.quality;
    final style = Theme.of(
      context,
    ).textTheme.bodySmall?.copyWith(fontFamily: 'monospace', height: 1.4);
    final lines = <String>[
      'schemaVersion=${evaluation.schemaVersion}',
      'evaluationId=${evaluation.evaluationId ?? '-'} scheduleId=${evaluation.scheduleId ?? '-'}',
      'state=${evaluation.stateRaw} decision=${evaluation.decisionRaw ?? '-'} bestKnown=${evaluation.bestKnownDecisionRaw ?? '-'}',
      'trigger=${evaluation.triggerReason ?? '-'} inputVersion=${evaluation.inputVersion ?? '-'} eventRevision=${evaluation.eventRevision ?? '-'}',
      'computedAt=${_iso(evaluation.computedAt)} asOf=${_iso(evaluation.asOf)}',
      'validUntil=${_iso(evaluation.validUntil)} nextEvaluationAt=${_iso(evaluation.nextEvaluationAt)}',
      'ML 요약: ${mlApplicationLabel(diagnostics.mlSummary)}',
      '과거 평균 요약: ${historicalApplicationLabel(diagnostics.historicalSummary)}',
      'modelVersion=${diagnostics.modelVersion ?? '-'} dataVersion=${diagnostics.dataVersion ?? '-'}',
      for (final entry in diagnostics.versions.entries)
        'versions.${entry.key}=${entry.value}',
      'quality: preview=${quality.preview} productionEligible=${quality.productionEligible} '
          'realtimeApplied=${quality.realtimeApplied} walkingApiCoverage=${quality.walkingApiCoverage} '
          'costCoverage=${quality.costCoverage}',
      'missingInputs(${quality.missingInputs.length})=${quality.missingInputs.isEmpty ? '-' : quality.missingInputs.join(', ')}',
      if (evaluation.search != null)
        'search: domain=${evaluation.search!.domain ?? '-'} evaluated=${evaluation.search!.evaluatedCount ?? '-'} '
            'complete=${evaluation.search!.complete ?? '-'} incompleteReason=${evaluation.search!.incompleteReason ?? '-'}',
    ];
    final stepLines = <String>[
      for (final step in diagnostics.steps) ...[
        '${step.step}: ${mlApplicationLabel(step.ml)}'
            '${step.mlReasons.isEmpty ? '' : ' (${step.mlReasons.join(', ')})'}'
            ' · ${historicalApplicationLabel(step.historical)}'
            '${step.historicalReasons.isEmpty ? '' : ' (${step.historicalReasons.join(', ')})'}'
            '${step.apiFallback ? ' · API 원본 값 사용' : ''}',
        if (step.modelVersion != null ||
            step.minHistorySamples != null ||
            step.maxMissingFeatures != null)
          '  model=${step.modelVersion ?? '-'} historySamples(min)=${step.minHistorySamples ?? '-'} '
              'missingFeatures(max)=${step.maxMissingFeatures ?? '-'}',
        for (final code in step.rawCodes) '  raw ${step.step}:$code',
      ],
      for (final code in diagnostics.globalCodes) 'raw $code',
    ];
    final legLines = <String>[
      for (final leg in evaluation.legs)
        'leg ${leg.sequence} ${leg.mode} role=${leg.roleRaw ?? '-'} source=${leg.durationSourceRaw ?? '-'} '
            'seconds=${leg.durationSeconds ?? '-'}'
            '${leg.endpointEvidence == null ? '' : ' endpoint(verified=${leg.endpointEvidence!.verified}, '
                  'source=${leg.endpointEvidence!.source ?? '-'}, mapping=${leg.endpointEvidence!.mappingVersion ?? '-'}, '
                  'policy=${leg.endpointEvidence!.policyVersion ?? '-'}, judgment=${leg.endpointEvidence!.judgment ?? '-'}, '
                  'reason=${leg.endpointEvidence!.reason ?? '-'})'}',
    ];
    final causeLines = <String>[
      for (final cause in evaluation.causes)
        'cause ${cause.reasonCode ?? '-'} evidence=${cause.evidenceLevel ?? '-'} effect=${cause.effect ?? '-'} '
            'confirmed=${cause.confirmed ?? '-'} source=${cause.source ?? '-'} incident=${cause.incidentKey ?? '-'}',
    ];
    return Container(
      key: const Key('evaluationDiagnosticsPanel'),
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).dividerColor),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('개발자 진단', style: TextStyle(fontWeight: FontWeight.w700)),
          const Text(
            '원본 코드 그대로 표시합니다. 단계별 처리 시간은 서버 로그에만 있습니다.',
            style: TextStyle(fontSize: 11),
          ),
          const SizedBox(height: 6),
          SelectableText(lines.join('\n'), style: style),
          if (stepLines.isNotEmpty) ...[
            const SizedBox(height: 6),
            const Text('구간별 적용 결과', style: TextStyle(fontWeight: FontWeight.w600)),
            SelectableText(stepLines.join('\n'), style: style),
          ],
          if (legLines.isNotEmpty) ...[
            const SizedBox(height: 6),
            const Text('구간 원본', style: TextStyle(fontWeight: FontWeight.w600)),
            SelectableText(legLines.join('\n'), style: style),
          ],
          if (causeLines.isNotEmpty) ...[
            const SizedBox(height: 6),
            const Text('원인 원본', style: TextStyle(fontWeight: FontWeight.w600)),
            SelectableText(causeLines.join('\n'), style: style),
          ],
        ],
      ),
    );
  }

  static String _iso(DateTime? value) => value?.toIso8601String() ?? '-';
}
