"""daily-brief.py 실패 경로 재현 (서버2에서 실행). 실제 Mattermost 발송은 하지 않는다(send 를 가로채거나 잘못된 URL 사용).

  python3 infra/scripts/tests/brief_failtest.py [daily-brief.py 경로]   # 기본: 옆 폴더의 운영 스크립트
검사: 정상 / Prometheus 다운 / 서버1 SSH 실패 / 서버2 docker 실패 / 커밋 비교(모르는 해시·해시 아님·앞뒤) /
      발송 실패(연결 거부·URL 없음) / NaN 표기 / build 예외 시 실패 통보 / 발송 실패 시 종료 코드.
모든 항목이 [OK] 여야 한다. [CRASH] 는 그 경로에서 스크립트가 예외로 죽는다는 뜻이다.
"""
import importlib.util, os, sys, time, math

TARGET = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "daily-brief.py")
spec = importlib.util.spec_from_file_location("b", TARGET)
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
env = b.load_env(b.ENV_PATH)
results = []

def case(name, fn):
    t = time.time()
    try:
        out = fn()
        results.append((name, "OK", out, round(time.time() - t, 1)))
    except Exception as e:  # 여기서 예외가 나면 그 경로는 아직 크래시한다는 뜻
        results.append((name, "CRASH", f"{type(e).__name__}: {e}", round(time.time() - t, 1)))

def head_and_warn(text):
    first = text.splitlines()[0]
    return first[:160]

# 1. 정상
case("1 정상 dry-run", lambda: head_and_warn(b.build(env)))

# 2. Prometheus 다운
orig_prom = b.PROM
def prom_down():
    b.PROM = "http://localhost:9";
    try: return head_and_warn(b.build(env))
    finally: b.PROM = orig_prom
case("2 Prometheus 다운", prom_down)

# 3. 서버1 SSH 실패
orig_key = b.SERVER1_KEY
def ssh_fail():
    b.SERVER1_KEY = "/nonexistent/key"
    try: return head_and_warn(b.build(env))
    finally: b.SERVER1_KEY = orig_key
case("3 서버1 SSH 실패", ssh_fail)

# 4. 서버2 로컬 수집 실패
orig_loc = b.collect_local
def loc_fail():
    b.collect_local = lambda: {}
    try: return head_and_warn(b.build(env))
    finally: b.collect_local = orig_loc
case("4 서버2 로컬 수집 실패", loc_fail)

# 5. 커밋 비교: 모르는 해시 / 해시 아님 / 서버1 뒤처짐
case("5a 모르는 해시", lambda: b._is_ancestor("deadbeef0", "5a60392"))
case("5b 해시 아님(날짜)", lambda: b._is_ancestor("2026-09-13", "5a60392"))
# 저장소에 있는 develop 이력 두 커밋(9/11 Spark OFF → 9/13 dt 머지). 이력이 재작성되면 두 해시를 갱신한다
case("5c 서버1 뒤처짐(03dd71e→5a60392)", lambda: b._is_ancestor("03dd71e", "5a60392"))
case("5d 서버2 뒤처짐(반대)", lambda: b._is_ancestor("5a60392", "03dd71e"))

# 6. 발송 실패: 연결 거부 / 404 / URL 없음  (재시도 대기 0초)
case("6a 웹훅 연결 거부", lambda: b.send({"GRAFANA_ALERT_WEBHOOK_URL": "http://127.0.0.1:9/hooks/x"}, "t", retries=1, wait=0))
case("6b 웹훅 404", lambda: b.send({"GRAFANA_ALERT_WEBHOOK_URL": "http://localhost:3000/hooks/does-not-exist"}, "t", retries=1, wait=0))
case("6c 웹훅 URL 없음", lambda: b.send({}, "t"))

# 7. NaN / Inf 표기
case("7 fmt NaN/Inf", lambda: (b.fmt(float("nan"), 1, "%"), b.fmt(float("inf")), b.human(float("nan"))))

# 8. build 예외 → main 이 실패 통보를 보내고 exit 1 (send 를 가로채 기록만)
sent = []
def build_crash():
    orig_build, orig_send = b.build, b.send
    b.build = lambda e: (_ for _ in ()).throw(RuntimeError("simulated crash"))
    b.send = lambda e, t, **k: (sent.append(t.splitlines()[0]), 0)[1]
    sys.argv = ["x"]
    try:
        try: b.main()
        except SystemExit as ex: return f"exit={ex.code} sent={sent}"
    finally: b.build, b.send = orig_build, orig_send
case("8 build 예외 → 실패 통보", build_crash)

# 9. 발송 실패해도 main 이 예외 없이 rc 1 (send 가로채 실패 반환)
def send_fail_main():
    orig_send = b.send
    b.send = lambda e, t, **k: 1
    sys.argv = ["x"]
    try: return f"rc={b.main()}"
    finally: b.send = orig_send
case("9 발송 실패 시 main rc", send_fail_main)

for name, st, out, sec in results:
    print(f"[{st}] {name} ({sec}s) -> {out}")
