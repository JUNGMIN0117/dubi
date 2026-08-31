import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../core/theme.dart';
import '../data/mock_repository.dart';
import '../domain/auth.dart';
import '../domain/models.dart';
import 'auth_screens.dart';
import 'widgets.dart';

void _open(BuildContext c, Widget page) =>
    Navigator.of(c).push(MaterialPageRoute(builder: (_) => page));
const _pad = EdgeInsets.fromLTRB(16, 8, 16, 20);
const _weekdays = ['월', '화', '수', '목', '금', '토', '일'];

String _fullDate(DateTime date) =>
    '${date.year}.${date.month.toString().padLeft(2, '0')}.${date.day.toString().padLeft(2, '0')} (${_weekdays[date.weekday - 1]})';

class LoginScreen extends StatelessWidget {
  const LoginScreen({super.key, required this.authRepository});
  final AuthRepository authRepository;
  @override
  Widget build(BuildContext c) => Scaffold(
    body: SafeArea(
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              children: [
                const Spacer(),
                TweenAnimationBuilder<double>(
                  duration: const Duration(milliseconds: 650),
                  curve: Curves.easeOutBack,
                  tween: Tween(begin: .82, end: 1),
                  builder: (_, value, child) => Opacity(
                    opacity: value.clamp(0, 1),
                    child: Transform.scale(scale: value, child: child),
                  ),
                  child: SizedBox(
                    height: 120,
                    child: Image.asset(
                      'assets/images/ttubee-guide.png',
                      fit: BoxFit.contain,
                      semanticLabel: '반갑게 인사하는 뚜비',
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Text('뚜비', style: Theme.of(c).textTheme.headlineLarge),
                const SizedBox(height: 8),
                const Text(
                  '일정에 늦지 않게,\n뚜비가 먼저 알려드려요',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 16),
                _login(
                  c,
                  '●  카카오로 시작하기',
                  const Color(0xFFFFD600),
                  Colors.black,
                  () => _social(c, '카카오'),
                ),
                _login(
                  c,
                  'G  Google로 시작하기',
                  Colors.white,
                  DdubiColors.navy,
                  () => _social(c, 'Google'),
                ),
                _login(
                  c,
                  '●  Apple로 시작하기',
                  Colors.white,
                  Colors.black,
                  () => _social(c, 'Apple'),
                ),
                _login(
                  c,
                  '✉  이메일로 시작하기',
                  Colors.white,
                  DdubiColors.navy,
                  () => _open(
                    c,
                    EmailLoginScreen(
                      repository: authRepository,
                      onAuthenticated: () => _completeLogin(c),
                    ),
                  ),
                ),
                TextButton(
                  onPressed: () =>
                      _open(c, SignUpScreen(repository: authRepository)),
                  child: const Text('아직 계정이 없으신가요?  회원가입'),
                ),
                const Spacer(),
                const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.lock_outline, size: 16),
                    SizedBox(width: 7),
                    Flexible(
                      child: Text(
                        '안전한 서비스 이용을 위해 최소한의 권한만 사용합니다.',
                        style: TextStyle(fontSize: 11),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
  void _completeLogin(BuildContext c) => c.go('/home');
  void _social(BuildContext c, String provider) => _open(
    c,
    SocialAuthScreen(
      provider: provider,
      onAuthenticated: () => _completeLogin(c),
    ),
  );
  Widget _login(
    BuildContext c,
    String text,
    Color bg,
    Color fg,
    VoidCallback onPressed,
  ) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: SizedBox(
      width: double.infinity,
      height: 48,
      child: OutlinedButton(
        style: OutlinedButton.styleFrom(
          backgroundColor: bg,
          foregroundColor: fg,
          side: const BorderSide(color: DdubiColors.border),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10),
          ),
        ),
        onPressed: onPressed,
        child: Text(text, style: const TextStyle(fontWeight: FontWeight.w700)),
      ),
    ),
  );
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.repository, this.go});
  final DdubiRepository repository;
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '다음 중요 일정',
    index: go == null ? null : 2,
    onNav: go,
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.notifications_none)),
    ],
    child: FutureBuilder<List<RouteOption>>(
      future: repository.routes(),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '추천 경로를 불러오지 못했어요.');
        }
        if (!s.hasData) return const Center(child: CircularProgressIndicator());
        final routes = s.data!;
        return ListView(
          padding: _pad,
          children: [
            InfoCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '19:30',
                              style: Theme.of(c).textTheme.headlineLarge,
                            ),
                            Text(
                              '잠실야구장',
                              style: Theme.of(c).textTheme.titleLarge,
                            ),
                            const SizedBox(height: 6),
                            const StatusBadge('지금은 정상'),
                          ],
                        ),
                      ),
                      const Icon(
                        Icons.stadium_outlined,
                        size: 58,
                        color: DdubiColors.blue,
                      ),
                    ],
                  ),
                  const Divider(height: 28),
                  const Text('추천 출발 시간', style: TextStyle(fontSize: 12)),
                  Row(
                    children: [
                      Text(
                        '17:57',
                        style: Theme.of(c).textTheme.headlineMedium
                            ?.copyWith(color: DdubiColors.blue),
                      ),
                      const Spacer(),
                      OutlinedButton(
                        onPressed: () => c.push('/schedules/schedule-baseball'),
                        child: const Text('상세 보기'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            const TtubeeGuideCard('17:57에 출발하면 안전해요!\n아직 준비할 시간이 충분해요.'),
            const SizedBox(height: 16),
            SizedBox(
              height: 230,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: routes.length,
                separatorBuilder: (_, _) => const SizedBox(width: 10),
                itemBuilder: (_, i) => RouteCard(
                  routes[i],
                  compact: true,
                  onTap: () => c.push('/recommendations/mock-recommendation-1'),
                ),
              ),
            ),
          ],
        );
      },
    ),
  );
}

class ScheduleListScreen extends StatefulWidget {
  const ScheduleListScreen({super.key, required this.repository, this.go});
  final DdubiRepository repository;
  final ValueChanged<int>? go;
  @override
  State<ScheduleListScreen> createState() => _ScheduleListScreenState();
}

class _ScheduleListScreenState extends State<ScheduleListScreen> {
  DateTime selectedDate = DateTime.now();
  Future<void> _add(BuildContext context) async {
    final saved = await context.push<bool>(
      '/schedules/new',
      extra: selectedDate,
    );
    if (saved == true && mounted) setState(() {});
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: '전체 일정',
    index: widget.go == null ? null : 0,
    onNav: widget.go,
    fab: FloatingActionButton(
      onPressed: () => _add(c),
      child: const Icon(Icons.add),
    ),
    child: FutureBuilder<List<Schedule>>(
      future: widget.repository.schedules(),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '일정을 불러오지 못했어요.');
        }
        final schedules = s.data ?? [];
        final selectedSchedules = schedules
            .where(
              (item) =>
                  item.date.year == selectedDate.year &&
                  item.date.month == selectedDate.month &&
                  item.date.day == selectedDate.day,
            )
            .toList();
        return ListView(
          padding: _pad,
          children: [
            MonthlyCalendar(
              selectedDate: selectedDate,
              schedules: schedules,
              onSelected: (date) => setState(() => selectedDate = date),
            ),
            const SizedBox(height: 18),
            SectionHeader(
              '${selectedDate.month}월 ${selectedDate.day}일 일정',
              trailing: TextButton.icon(
                onPressed: () => _add(c),
                icon: const Icon(Icons.add),
                label: const Text('일정 추가'),
              ),
            ),
            const SizedBox(height: 8),
            if (!s.hasData)
              const Center(child: CircularProgressIndicator())
            else if (selectedSchedules.isEmpty)
              const TtubeeGuideCard(
                '선택한 날짜에는 일정이 없어요.\n새로운 일정을 추가해볼까요?',
                compact: true,
              )
            else
              ...selectedSchedules.map(
                (v) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(
                    Icons.location_on_outlined,
                    color: DdubiColors.blue,
                  ),
                  title: Text(
                    '${v.time}  ${v.title}',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                  subtitle: Text(v.place),
                  trailing: Text(v.dateLabel),
                  onTap: () => c.push('/schedules/${v.id}'),
                ),
              ),
          ],
        );
      },
    ),
  );
}

class ScheduleFormScreen extends StatefulWidget {
  const ScheduleFormScreen({
    super.key,
    required this.repository,
    this.initialDate,
  });
  final DdubiRepository repository;
  final DateTime? initialDate;
  @override
  State<ScheduleFormScreen> createState() => _ScheduleFormScreenState();
}

class _ScheduleFormScreenState extends State<ScheduleFormScreen> {
  int importance = 1, transport = 0;
  final formKey = GlobalKey<FormState>();
  final title = TextEditingController(text: '잠실야구장');
  final place = TextEditingController(text: '잠실야구장');
  final time = TextEditingController(text: '19:30');
  final departure = TextEditingController(text: '집 (서울 강남구 역삼동)');

  @override
  void dispose() {
    title.dispose();
    place.dispose();
    time.dispose();
    departure.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext c) => AppPage(
    title: '일정 등록',
    child: Form(
      key: formKey,
      child: ListView(
        padding: _pad,
        children: [
          _field('일정 제목', title),
          _field('도착지 검색', place, icon: Icons.search),
          Text(
            _fullDate(widget.initialDate ?? DateTime.now()),
            style: Theme.of(c).textTheme.bodySmall,
          ),
          _field('반드시 도착해야 하는 시간', time, icon: Icons.schedule),
          _field('출발지', departure, icon: Icons.my_location),
          const Text('중요도', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          SegmentedButton<int>(
            segments: const [
              ButtonSegment(value: 0, label: Text('일반')),
              ButtonSegment(value: 1, label: Text('중요')),
              ButtonSegment(value: 2, label: Text('절대 지각 불가')),
            ],
            selected: {importance},
            onSelectionChanged: (v) => setState(() => importance = v.first),
          ),
          const SizedBox(height: 20),
          const Text('교통수단 선호', style: TextStyle(fontWeight: FontWeight.w700)),
          RadioGroup<int>(
            groupValue: transport,
            onChanged: (v) => setState(() => transport = v!),
            child: Column(
              children: ['지하철 우선', '버스 + 지하철', '버스 우선']
                  .asMap()
                  .entries
                  .map(
                    (e) =>
                        RadioListTile<int>(value: e.key, title: Text(e.value)),
                  )
                  .toList(),
            ),
          ),
          const SizedBox(height: 10),
          PrimaryButton('저장하기', onPressed: _save),
        ],
      ),
    ),
  );

  Future<void> _save() async {
    if (!(formKey.currentState?.validate() ?? false)) return;
    final date = widget.initialDate ?? DateTime.now();
    final today = DateTime.now();
    final isToday =
        date.year == today.year &&
        date.month == today.month &&
        date.day == today.day;
    await widget.repository.addSchedule(
      Schedule(
        id: 'schedule-${DateTime.now().microsecondsSinceEpoch}',
        title: title.text.trim(),
        place: place.text.trim(),
        departurePlace: departure.text.trim(),
        time: time.text.trim(),
        dateLabel: isToday ? '오늘' : '${date.month}.${date.day}',
        date: date,
        importance: importance,
        preferredTransport: transport,
      ),
    );
    if (mounted) context.pop(true);
  }

  Widget _field(
    String label,
    TextEditingController controller, {
    IconData? icon,
  }) => Padding(
    padding: const EdgeInsets.only(bottom: 15),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
        ),
        const SizedBox(height: 6),
        TextFormField(
          controller: controller,
          validator: (value) =>
              (value ?? '').trim().isEmpty ? '필수 입력 항목입니다.' : null,
          decoration: InputDecoration(
            suffixIcon: icon == null ? null : Icon(icon),
          ),
        ),
      ],
    ),
  );
}

class ScheduleDetailScreen extends StatelessWidget {
  const ScheduleDetailScreen({
    super.key,
    required this.repository,
    required this.scheduleId,
  });
  final DdubiRepository repository;
  final String scheduleId;

  @override
  Widget build(BuildContext c) => FutureBuilder<Schedule?>(
    future: repository.scheduleById(scheduleId),
    builder: (context, snapshot) {
      if (snapshot.hasError) {
        return const AppPage(
          title: '일정 상세',
          child: DataLoadError(message: '일정을 불러오지 못했어요.'),
        );
      }
      if (snapshot.connectionState != ConnectionState.done) {
        return const AppPage(
          title: '일정 상세',
          child: Center(child: CircularProgressIndicator()),
        );
      }
      final schedule = snapshot.data;
      if (schedule == null) {
        return const AppPage(
          title: '일정 상세',
          child: DataLoadError(message: '해당 일정을 찾을 수 없어요.'),
        );
      }
      return _content(context, schedule);
    },
  );

  Widget _content(BuildContext c, Schedule schedule) => AppPage(
    title: '일정 상세',
    child: ListView(
      padding: _pad,
      children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(
            color: DdubiColors.blue,
            borderRadius: BorderRadius.circular(14),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                _fullDate(schedule.date),
                style: const TextStyle(color: Colors.white, fontSize: 12),
              ),
              const SizedBox(height: 8),
              Text(
                '${schedule.time} ${schedule.title}',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 23,
                  fontWeight: FontWeight.w800,
                ),
              ),
              Text(
                schedule.place,
                style: const TextStyle(color: Colors.white70),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        Row(
          children: [
            Expanded(
              child: InfoCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('도착까지 남은 시간'),
                    SizedBox(height: 7),
                    Text(
                      '2시간 48분',
                      style: TextStyle(
                        fontSize: 21,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    Text('(17:57 출발 기준)', style: TextStyle(fontSize: 11)),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 10),
            const Expanded(
              child: InfoCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('데이터 최신성'),
                    SizedBox(height: 7),
                    Text('12분 전 업데이트'),
                    StatusBadge('최신 데이터'),
                  ],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        InfoCard(
          child: Row(
            children: [
              Stack(
                alignment: Alignment.center,
                children: [
                  SizedBox(
                    width: 75,
                    height: 75,
                    child: CircularProgressIndicator(
                      value: .92,
                      strokeWidth: 8,
                      color: DdubiColors.green,
                      backgroundColor: DdubiColors.surface,
                    ),
                  ),
                  Text('92%', style: Theme.of(c).textTheme.titleLarge),
                ],
              ),
              const SizedBox(width: 18),
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('현재 도착 신뢰도'),
                    StatusBadge('매우 있음'),
                    Text('평소보다 15분 여유 있어요.', style: TextStyle(fontSize: 12)),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        PrimaryButton(
          '추천 경로 보기',
          onPressed: () => c.push('/recommendations/mock-recommendation-1'),
        ),
        const SizedBox(height: 18),
        const SectionHeader('한눈에 보기'),
        const SizedBox(height: 8),
        const Row(
          children: [
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Icon(Icons.sunny, color: Colors.orange),
                    Text('날씨'),
                    Text('맑음 22℃'),
                  ],
                ),
              ),
            ),
            SizedBox(width: 8),
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Icon(Icons.groups_outlined),
                    Text('행사/이벤트'),
                    Text('대규모 행사 없음'),
                  ],
                ),
              ),
            ),
            SizedBox(width: 8),
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Icon(Icons.check_circle_outline, color: DdubiColors.green),
                    Text('교통 상황'),
                    Text('지금은 정상'),
                  ],
                ),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class RouteResultsScreen extends StatelessWidget {
  const RouteResultsScreen({super.key, required this.repository, this.go});
  final DdubiRepository repository;
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '추천 결과',
    index: go == null ? null : 1,
    onNav: go,
    actions: const [IconButton(onPressed: null, icon: Icon(Icons.refresh))],
    child: FutureBuilder<List<RouteOption>>(
      future: repository.routes(),
      builder: (c, s) {
        if (s.hasError) {
          return const DataLoadError(message: '추천 경로를 불러오지 못했어요.');
        }
        return ListView(
          padding: _pad,
          children: [
            const TtubeeGuideCard('이 경로가 지금 가장 안정적이에요!', compact: true),
            const SizedBox(height: 12),
            if (!s.hasData)
              const Center(child: CircularProgressIndicator())
            else
              ...s.data!.map(
                (r) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: RouteCard(
                    r,
                    onTap: () =>
                        _open(c, RouteMapScreen(repository: repository)),
                  ),
                ),
              ),
            const InfoCard(
              color: Color(0xFFF4F8FF),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  StatusBadge(
                    '추천 이유',
                    tone: DdubiColors.blue,
                    icon: Icons.lightbulb_outline,
                  ),
                  SizedBox(height: 10),
                  Text(
                    '현재 버스 정차 구간이 혼잡해 버스 도착 예측 신뢰도가 낮아요.\n현재 정상 운행 중인 지하철 2호선 경로를 추천드려요.',
                  ),
                ],
              ),
            ),
          ],
        );
      },
    ),
  );
}

class RouteMapScreen extends StatelessWidget {
  const RouteMapScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '전체 지도',
    child: Column(
      children: [
        Expanded(
          child: Stack(
            children: [
              Container(
                color: const Color(0xFFEAF2EC),
                child: CustomPaint(
                  painter: _MapPainter(),
                  child: const SizedBox.expand(),
                ),
              ),
              const Positioned(
                left: 12,
                top: 12,
                child: InfoCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      StatusBadge(
                        '도보',
                        tone: Colors.grey,
                        icon: Icons.more_horiz,
                      ),
                      StatusBadge(
                        '버스',
                        tone: DdubiColors.blue,
                        icon: Icons.remove,
                      ),
                      StatusBadge(
                        '지하철',
                        tone: DdubiColors.green,
                        icon: Icons.remove,
                      ),
                    ],
                  ),
                ),
              ),
              const Positioned(
                right: 14,
                top: 120,
                child: Column(
                  children: [
                    CircleAvatar(child: Icon(Icons.my_location)),
                    SizedBox(height: 8),
                    CircleAvatar(child: Icon(Icons.add)),
                    SizedBox(height: 4),
                    CircleAvatar(child: Icon(Icons.remove)),
                  ],
                ),
              ),
            ],
          ),
        ),
        InfoCard(
          child: Column(
            children: [
              const Row(
                children: [
                  Text(
                    '17:57 출발',
                    style: TextStyle(fontWeight: FontWeight.w800),
                  ),
                  Spacer(),
                  Icon(Icons.arrow_forward),
                  Spacer(),
                  Text(
                    '18:45 도착',
                    style: TextStyle(fontWeight: FontWeight.w800),
                  ),
                  Spacer(),
                  Text(
                    '15분 여유',
                    style: TextStyle(
                      color: DdubiColors.green,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
              const Divider(height: 25),
              ...[
                '17:57  출발 · 역삼역 입구',
                '18:03  버스 탑승 · 271번',
                '18:24  지하철 2호선 환승',
                '18:45  잠실야구장 도착',
              ].map(
                (t) => ListTile(
                  dense: true,
                  leading: const Icon(
                    Icons.circle,
                    size: 12,
                    color: DdubiColors.blue,
                  ),
                  title: Text(t),
                ),
              ),
              PrimaryButton(
                '이동 안내 시작',
                onPressed: () => _open(c, MovingScreen(repository: repository)),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}

class _MapPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final road = Paint()
      ..color = Colors.white
      ..strokeWidth = 18
      ..style = PaintingStyle.stroke;
    final route = Paint()
      ..color = DdubiColors.blue
      ..strokeWidth = 7
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final green = Paint()
      ..color = DdubiColors.green
      ..strokeWidth = 7
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;
    final path = Path()
      ..moveTo(size.width * .2, size.height * .84)
      ..cubicTo(
        size.width * .55,
        size.height * .72,
        size.width * .18,
        size.height * .5,
        size.width * .58,
        size.height * .4,
      )
      ..cubicTo(
        size.width * .82,
        size.height * .32,
        size.width * .55,
        size.height * .16,
        size.width * .75,
        size.height * .08,
      );
    canvas.drawPath(path, road);
    canvas.drawPath(path, route);
    canvas.drawLine(
      Offset(size.width * .58, size.height * .4),
      Offset(size.width * .75, size.height * .08),
      green,
    );
    for (final p in [
      Offset(size.width * .2, size.height * .84),
      Offset(size.width * .58, size.height * .4),
      Offset(size.width * .75, size.height * .08),
    ]) {
      canvas.drawCircle(p, 10, Paint()..color = Colors.white);
      canvas.drawCircle(p, 7, Paint()..color = DdubiColors.red);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class DelayAlertScreen extends StatelessWidget {
  const DelayAlertScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '상황 변경 알림',
    child: ListView(
      padding: _pad,
      children: [
        const InfoCard(
          color: Color(0xFFFFF5ED),
          child: Row(
            children: [
              Icon(
                Icons.warning_amber_rounded,
                color: DdubiColors.orange,
                size: 30,
              ),
              SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '버스 지연이 커졌어요',
                      style: TextStyle(
                        color: DdubiColors.orange,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    Text('현재 경로로는 늦어질 수 있어요.'),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text('기존 경로 (버스)'),
            subtitle: Text('강남역 → 여의도역\n예상 도착  오전 9:47'),
            trailing: StatusBadge(
              '+8분 지연',
              tone: DdubiColors.orange,
              icon: Icons.warning_amber,
            ),
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(
              '새로운 경로 (지하철)',
              style: TextStyle(color: DdubiColors.blue),
            ),
            subtitle: Text('강남역 → 여의도역\n예상 도착  오전 9:39'),
            trailing: StatusBadge('제시간 도착'),
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          color: Color(0xFFF4F8FF),
          child: Text('✓ 지하철로 변경 시 약 8분을 절약할 수 있어요.'),
        ),
        const SpacerBox(),
        Row(
          children: [
            Expanded(
              child: OutlinedButton(
                onPressed: () => Navigator.pop(c),
                child: const Text('기존 경로 유지'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton(
                onPressed: () => _open(c, MovingScreen(repository: repository)),
                child: const Text('경로 변경'),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class SpacerBox extends StatelessWidget {
  const SpacerBox({super.key});
  @override
  Widget build(BuildContext context) => const SizedBox(height: 24);
}

class MovingScreen extends StatelessWidget {
  const MovingScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '출발 · 이동 중',
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.notifications_none)),
    ],
    child: ListView(
      padding: _pad,
      children: [
        const TtubeeGuideCard(
          '지금 출발하면 딱 좋아요!',
          compact: true,
          asset: 'assets/images/ttubee-moving.png',
        ),
        const SizedBox(height: 12),
        InfoCard(
          color: const Color(0xFFF0FAF7),
          child: Column(
            children: [
              const CircleAvatar(
                radius: 32,
                backgroundColor: Colors.white,
                child: Icon(
                  Icons.directions_walk,
                  size: 36,
                  color: DdubiColors.navy,
                ),
              ),
              const SizedBox(height: 15),
              Text('지금 출발하세요', style: Theme.of(c).textTheme.titleLarge),
              const Text('여유 있게 이동하면 제시간 도착해요.'),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            leading: CircleAvatar(child: Icon(Icons.directions_walk)),
            title: Text('다음 단계'),
            subtitle: Text('강남역까지 도보 6분\n450m'),
          ),
        ),
        const SizedBox(height: 12),
        const InfoCard(
          child: ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(Icons.check_circle, color: DdubiColors.green),
            title: Text('현재 지연 상태'),
            subtitle: StatusBadge('지연 위험 낮음 · 제시간 도착 예상'),
          ),
        ),
        const SizedBox(height: 28),
        PrimaryButton(
          '출발 안내 시작',
          onPressed: () => _open(c, ArrivalScreen(repository: repository)),
        ),
      ],
    ),
  );
}

class ArrivalScreen extends StatelessWidget {
  const ArrivalScreen({super.key, required this.repository});
  final DdubiRepository repository;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '',
    child: ListView(
      padding: _pad,
      children: [
        const TtubeeGuideCard('제시간에 도착했어요!\n오늘 이동도 정말 잘했어요.'),
        const SizedBox(height: 18),
        const Icon(Icons.check_circle, color: Color(0xFF12B89A), size: 70),
        Center(
          child: Text(
            '제시간에 도착했어요',
            style: Theme.of(c).textTheme.titleLarge
                ?.copyWith(color: const Color(0xFF008A72)),
          ),
        ),
        const Center(child: Text('모든 도착!')),
        const SizedBox(height: 22),
        const InfoCard(
          child: Column(
            children: [
              ListTile(title: Text('계획 출발'), trailing: Text('오전 9:00')),
              ListTile(title: Text('실제 출발'), trailing: Text('오전 9:02  (+2분)')),
              Divider(),
              ListTile(title: Text('계획 도착'), trailing: Text('오전 9:39')),
              ListTile(
                title: Text('실제 도착'),
                trailing: Text(
                  '오전 9:37  (-2분)',
                  style: TextStyle(color: DdubiColors.green),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        InfoCard(
          color: const Color(0xFFF0FAF7),
          child: Column(
            children: [
              const Text('절약한 시간'),
              Text(
                '2분',
                style: Theme.of(c).textTheme.headlineLarge
                    ?.copyWith(color: const Color(0xFF008A72)),
              ),
            ],
          ),
        ),
        const SizedBox(height: 22),
        const Center(child: Text('이번 경험은 어땠나요?')),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () => Navigator.popUntil(c, (r) => r.isFirst),
                icon: const Icon(Icons.sentiment_satisfied_alt),
                label: const Text('만족해요'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () {},
                icon: const Icon(Icons.sentiment_dissatisfied_outlined),
                label: const Text('아쉬워요'),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key, this.go});
  final ValueChanged<int>? go;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '알림함',
    index: go == null ? null : 3,
    onNav: go,
    actions: const [
      IconButton(onPressed: null, icon: Icon(Icons.settings_outlined)),
    ],
    child: ListView(
      padding: _pad,
      children: [
        const SectionHeader('출발 알림'),
        ...['08:30  곧 출발 시간이에요', '08:10  출발 20분 전 알림'].map(
          (t) => ListTile(
            leading: const Icon(Icons.circle, size: 8, color: DdubiColors.blue),
            title: Text(t),
            subtitle: const Text('방문 장소 · 09:00 일정'),
          ),
        ),
        const Divider(),
        const SectionHeader('이동 기록'),
        ...[
          '5.18 (토)  강남역 → 여의도역|오전 9:37 도착|제시간',
          '5.16 (목)  강남역 → 여의도역|오전 9:38 도착|제시간',
          '5.14 (화)  강남역 → 여의도역|오전 9:47 도착|지연',
        ].map((t) {
          final p = t.split('|');
          return ListTile(
            title: Text(p[0]),
            subtitle: Text(p[1]),
            trailing: StatusBadge(
              p[2],
              tone: p[2] == '지연' ? DdubiColors.orange : DdubiColors.green,
              icon: p[2] == '지연'
                  ? Icons.warning_amber
                  : Icons.check_circle_outline,
            ),
          );
        }),
        const Divider(),
        const SectionHeader('주간 리포트 (5.12 ~ 5.18)'),
        const SizedBox(height: 10),
        const Row(
          children: [
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Text('제시간 도착률'),
                    Text(
                      '92%',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SizedBox(width: 10),
            Expanded(
              child: InfoCard(
                child: Column(
                  children: [
                    Text('평균 여유 시간'),
                    Text(
                      '6분',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ],
    ),
  );
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, this.go, required this.repository});
  final ValueChanged<int>? go;
  final DdubiRepository repository;
  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  int selected = 1;
  @override
  Widget build(BuildContext c) => AppPage(
    title: '이동 설정',
    index: widget.go == null ? null : 4,
    onNav: widget.go,
    child: ListView(
      padding: _pad,
      children: [
        InfoCard(
          child: Column(
            children: [
              const ListTile(
                contentPadding: EdgeInsets.zero,
                leading: CircleAvatar(child: Icon(Icons.person_outline)),
                title: Text(
                  '뚜비 사용자',
                  style: TextStyle(fontWeight: FontWeight.w800),
                ),
                subtitle: Text('user@example.com'),
              ),
              const Divider(),
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.logout, color: DdubiColors.red),
                title: const Text(
                  '로그아웃',
                  style: TextStyle(
                    color: DdubiColors.red,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                onTap: () => showDialog<void>(
                  context: c,
                  builder: (dialogContext) => AlertDialog(
                    title: const Text('로그아웃할까요?'),
                    content: const Text('다시 로그인하면 일정과 추천 경로를 확인할 수 있어요.'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(dialogContext),
                        child: const Text('취소'),
                      ),
                      FilledButton(
                        onPressed: () {
                          Navigator.pop(dialogContext);
                          c.go('/login');
                        },
                        child: const Text('로그아웃'),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        const SectionHeader('교통수단 선호'),
        const SizedBox(height: 10),
        Row(
          children: List.generate(4, (i) {
            final labels = ['버스', '지하철', '도보', '택시'];
            final icons = [
              Icons.directions_bus,
              Icons.directions_subway,
              Icons.directions_walk,
              Icons.local_taxi,
            ];
            return Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 3),
                child: InkWell(
                  onTap: () => setState(() => selected = i),
                  child: Container(
                    padding: const EdgeInsets.symmetric(vertical: 13),
                    decoration: BoxDecoration(
                      border: Border.all(
                        color: selected == i
                            ? DdubiColors.blue
                            : DdubiColors.border,
                      ),
                      color: selected == i
                          ? const Color(0xFFF0F6FF)
                          : Colors.white,
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Column(
                      children: [
                        Icon(
                          icons[i],
                          color: selected == i ? DdubiColors.blue : null,
                        ),
                        Text(labels[i], style: const TextStyle(fontSize: 11)),
                      ],
                    ),
                  ),
                ),
              ),
            );
          }),
        ),
        const SizedBox(height: 16),
        const InfoCard(
          child: Column(
            children: [
              ListTile(
                leading: Icon(Icons.location_on_outlined),
                title: Text('위치 권한'),
                trailing: Text('항상 허용  ›'),
              ),
              Divider(),
              ListTile(
                leading: Icon(Icons.notifications_none),
                title: Text('알림 권한'),
                trailing: Text('허용됨  ›'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        const TtubeeGuideCard(
          '교통 정보를 불러오지 못했어요.\n잠시 후 다시 확인해주세요.',
          compact: true,
        ),
        const SizedBox(height: 12),
        InfoCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SectionHeader('데이터 부족 알림'),
              const SizedBox(height: 10),
              const StatusBadge(
                '교통 정보 업데이트에 실패했어요.',
                tone: DdubiColors.orange,
                icon: Icons.cloud_off_outlined,
              ),
              const Text('네트워크를 확인해주세요.'),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: FilledButton(
                  onPressed: () {},
                  child: const Text('다시 시도'),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        InfoCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SectionHeader('예외 상황 기록'),
              const SizedBox(height: 10),
              const Text(
                '최근 06.18 07:30',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              const Text('최신 교통정보가 부족해\n추천 경로를 불러오지 못했어요.'),
              const SizedBox(height: 12),
              Align(
                alignment: Alignment.centerRight,
                child: OutlinedButton(
                  onPressed: () => _open(
                    c,
                    DelayAlertScreen(repository: MockDdubiRepository()),
                  ),
                  child: const Text('다시 시도'),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
}
