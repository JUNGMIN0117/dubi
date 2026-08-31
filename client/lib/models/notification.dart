class DdubiNotification {
  const DdubiNotification({
    required this.id,
    required this.message,
    required this.isRead,
  });

  final String id;
  final String message;
  final bool isRead;
}
