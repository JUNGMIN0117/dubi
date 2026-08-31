import 'package:ddubi/core/constants/layout_constants.dart';
import 'package:ddubi/models/recommendation.dart';
import 'package:ddubi/providers/recommendation_provider.dart';
import 'package:ddubi/widgets/responsive/responsive_content.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final recommendation = ref.watch(homeRecommendationProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('뚜비')),
      body: SingleChildScrollView(
        child: ResponsiveContent(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final isWide =
                  constraints.maxWidth >= LayoutConstants.wideScreenBreakpoint;
              return _HomeContent(
                recommendation: recommendation,
                isWide: isWide,
              );
            },
          ),
        ),
      ),
    );
  }
}

class _HomeContent extends StatelessWidget {
  const _HomeContent({required this.recommendation, required this.isWide});

  final Recommendation recommendation;
  final bool isWide;

  @override
  Widget build(BuildContext context) {
    final metricCards = [
      _MetricCard(label: '도착 예정', value: recommendation.estimatedArrivalTime),
      _MetricCard(
        label: '추천 출발',
        value: recommendation.recommendedDepartureTime,
      ),
      _MetricCard(label: '평소 대비', value: recommendation.delayDifference),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          '오늘도 여유 있게 출발해요',
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: LayoutConstants.sectionSpacing),
        _NextScheduleCard(recommendation: recommendation),
        const SizedBox(height: LayoutConstants.sectionSpacing),
        if (isWide)
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var index = 0; index < metricCards.length; index++) ...[
                Expanded(child: metricCards[index]),
                if (index < metricCards.length - 1)
                  const SizedBox(width: LayoutConstants.sectionSpacing),
              ],
            ],
          )
        else
          Column(
            children: [
              for (var index = 0; index < metricCards.length; index++) ...[
                metricCards[index],
                if (index < metricCards.length - 1)
                  const SizedBox(height: LayoutConstants.sectionSpacing),
              ],
            ],
          ),
        const SizedBox(height: LayoutConstants.sectionSpacing),
        _StatusCard(status: recommendation.currentStatus),
        const SizedBox(height: 24),
        Align(
          alignment: isWide ? Alignment.centerRight : Alignment.center,
          child: SizedBox(
            width: isWide ? null : double.infinity,
            child: FilledButton(
              onPressed: () =>
                  context.go('/recommendations/${recommendation.id}'),
              child: const Text('추천 경로 보기'),
            ),
          ),
        ),
      ],
    );
  }
}

class _NextScheduleCard extends StatelessWidget {
  const _NextScheduleCard({required this.recommendation});

  final Recommendation recommendation;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('다음 일정', style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 8),
            Text(
              '${recommendation.schedule.time} '
              '${recommendation.schedule.title}',
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ],
        ),
      ),
    );
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label),
            const SizedBox(width: 16),
            Text(value, style: Theme.of(context).textTheme.titleLarge),
          ],
        ),
      ),
    );
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('현재 상태', style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 8),
            Row(
              children: [
                Icon(
                  Icons.info_outline,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    status,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
