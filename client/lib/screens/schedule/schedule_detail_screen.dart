import 'package:ddubi/widgets/common/placeholder_screen.dart';
import 'package:flutter/material.dart';

class ScheduleDetailScreen extends StatelessWidget {
  const ScheduleDetailScreen({super.key, required this.scheduleId});

  final String scheduleId;

  @override
  Widget build(BuildContext context) {
    return PlaceholderScreen(title: '일정 상세', message: '일정 ID: $scheduleId');
  }
}
