import 'package:ddubi/widgets/common/placeholder_screen.dart';
import 'package:flutter/material.dart';

class RecommendationDetailScreen extends StatelessWidget {
  const RecommendationDetailScreen({super.key, required this.recommendationId});

  final String recommendationId;

  @override
  Widget build(BuildContext context) {
    return PlaceholderScreen(
      title: '추천 경로',
      message: '추천 ID: $recommendationId',
    );
  }
}
