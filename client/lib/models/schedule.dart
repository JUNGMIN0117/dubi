class Schedule {
  const Schedule({required this.id, required this.time, required this.title});

  final String id;
  final String time;
  final String title;

  factory Schedule.fromJson(Map<String, dynamic> json) {
    return Schedule(
      id: json['id'].toString(),
      time: json['time'] as String,
      title: json['title'] as String,
    );
  }
}
