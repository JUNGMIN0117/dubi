import 'package:ddubi/screens/auth/login_screen.dart';
import 'package:ddubi/screens/home/home_screen.dart';
import 'package:ddubi/screens/notification/notification_screen.dart';
import 'package:ddubi/screens/recommendation/recommendation_detail_screen.dart';
import 'package:ddubi/screens/schedule/schedule_detail_screen.dart';
import 'package:ddubi/screens/schedule/schedule_form_screen.dart';
import 'package:ddubi/screens/schedule/schedule_list_screen.dart';
import 'package:ddubi/widgets/common/placeholder_screen.dart';
import 'package:go_router/go_router.dart';

final GoRouter appRouter = GoRouter(
  initialLocation: '/home',
  routes: [
    GoRoute(path: '/', redirect: (context, state) => '/home'),
    GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
    GoRoute(path: '/home', builder: (context, state) => const HomeScreen()),
    GoRoute(
      path: '/schedules',
      builder: (context, state) => const ScheduleListScreen(),
      routes: [
        GoRoute(
          path: 'new',
          builder: (context, state) => const ScheduleFormScreen(),
        ),
        GoRoute(
          path: ':id',
          builder: (context, state) =>
              ScheduleDetailScreen(scheduleId: state.pathParameters['id']!),
        ),
      ],
    ),
    GoRoute(
      path: '/recommendations/:id',
      builder: (context, state) => RecommendationDetailScreen(
        recommendationId: state.pathParameters['id']!,
      ),
    ),
    GoRoute(
      path: '/notifications',
      builder: (context, state) => const NotificationScreen(),
    ),
  ],
  errorBuilder: (context, state) => PlaceholderScreen(
    title: '페이지를 찾을 수 없어요',
    message: state.error?.toString() ?? '요청한 경로를 확인해 주세요.',
  ),
);
