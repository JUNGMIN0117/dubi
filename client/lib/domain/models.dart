enum RouteKind { recommended, fastest, safest }

class Schedule {
  const Schedule({
    required this.title,
    required this.place,
    required this.time,
    required this.dateLabel,
    required this.date,
  });
  final String title, place, time, dateLabel;
  final DateTime date;
}

class RouteOption {
  const RouteOption({
    required this.kind,
    required this.label,
    required this.transport,
    required this.departure,
    required this.arrival,
    required this.margin,
    required this.detail,
    required this.fare,
  });
  final RouteKind kind;
  final String label, transport, departure, arrival, margin, detail, fare;
}

abstract interface class DdubiRepository {
  Future<List<Schedule>> schedules();
  Future<void> addSchedule(Schedule schedule);
  Future<List<RouteOption>> routes();
}
