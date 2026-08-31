import 'package:ddubi/models/schedule.dart';

class Recommendation {
  const Recommendation({
    required this.id,
    required this.schedule,
    required this.estimatedArrivalTime,
    required this.recommendedDepartureTime,
    required this.delayDifference,
    required this.currentStatus,
  });

  final String id;
  final Schedule schedule;
  final String estimatedArrivalTime;
  final String recommendedDepartureTime;
  final String delayDifference;
  final String currentStatus;

  factory Recommendation.fromJson(Map<String, dynamic> json) {
    return Recommendation(
      id: json['id'].toString(),
      schedule: Schedule.fromJson(json['schedule'] as Map<String, dynamic>),
      estimatedArrivalTime: json['estimatedArrivalTime'] as String,
      recommendedDepartureTime: json['recommendedDepartureTime'] as String,
      delayDifference: json['delayDifference'] as String,
      currentStatus: json['currentStatus'] as String,
    );
  }
}
