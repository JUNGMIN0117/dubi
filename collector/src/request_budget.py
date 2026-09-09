"""공유 SQLite에 실제 HTTP 시도를 기록한다. 비밀키는 SHA-256만 저장한다."""
from __future__ import annotations

import hashlib
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import unquote


class BudgetExceeded(RuntimeError):
    pass


class RequestBudget:
    # 여러 Collector 컨테이너가 같은 Docker volume의 DB를 공유한다. WAL은 읽기와
    # 쓰기의 충돌을 줄이고 busy_timeout은 다른 프로세스의 짧은 쓰기가 끝날 때까지
    # 즉시 실패하지 않고 기다리게 한다.
    BUSY_TIMEOUT_MS = 30_000

    def __init__(self, path: str, key: str, limit: int = 900, clock=time.time):
        if not 1 <= limit <= 1000:
            raise ValueError("API_DAILY_LIMIT must be between 1 and 1000")
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.key = hashlib.sha256(unquote(key.strip()).encode()).hexdigest()
        self.limit, self.clock = limit, clock
        with self._db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS requests (key TEXT, at REAL)")
            db.execute("CREATE INDEX IF NOT EXISTS requests_key_at ON requests(key, at)")
            db.execute("CREATE TABLE IF NOT EXISTS cycles (key TEXT, source TEXT, next REAL, PRIMARY KEY(key, source))")

    @contextmanager
    def _db(self):
        db = sqlite3.connect(
            self.path,
            timeout=self.BUSY_TIMEOUT_MS / 1000,
            isolation_level=None,
        )
        try:
            db.execute(f"PRAGMA busy_timeout={self.BUSY_TIMEOUT_MS}")
            # journal_mode는 DB 파일에 유지된다. 모든 프로세스가 같은 값을 다시
            # 요청해도 안전하며, 최초 실행 시에만 실제 모드 전환이 일어난다.
            journal_mode = db.execute("PRAGMA journal_mode=WAL").fetchone()[0]
            if str(journal_mode).lower() != "wal":
                raise RuntimeError("request budget SQLite WAL mode is unavailable")
            db.execute("PRAGMA synchronous=NORMAL")
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.execute("COMMIT")
        except BaseException:
            if db.in_transaction:
                db.execute("ROLLBACK")
            raise
        finally:
            db.close()

    def claim_cycle(self, source: str, interval: int, expected_requests: int, *, group: str | None = None) -> bool:
        """재시작·동시 프로세스가 같은 소스를 연속 수집하지 않도록 선점한다."""
        now = self.clock()
        with self._db() as db:
            row = db.execute("SELECT next FROM cycles WHERE key=? AND source=?", (self.key, source)).fetchone()
            if row and row[0] > now:
                return False
            if group:
                group_row = db.execute("SELECT next FROM cycles WHERE key=? AND source=?", (self.key, group)).fetchone()
                if group_row and group_row[0] > now:
                    return False
            used = db.execute("SELECT count(*) FROM requests WHERE key=? AND at>?", (self.key, now-86400)).fetchone()[0]
            if used + expected_requests > self.limit:
                raise BudgetExceeded("Not enough rolling-24h request budget for this cycle")
            db.execute("INSERT OR REPLACE INTO cycles VALUES (?, ?, ?)", (self.key, source, now+interval))
            if group:
                db.execute("INSERT OR REPLACE INTO cycles VALUES (?, ?, ?)", (self.key, group, now+86400))
        return True

    def before_request(self, request) -> None:
        """HTTP 요청 직전에 커밋. 실패/리다이렉트도 차감하며 환불하지 않는다."""
        now = self.clock()
        with self._db() as db:
            db.execute("DELETE FROM requests WHERE at<=?", (now-86400,))
            used = db.execute("SELECT count(*) FROM requests WHERE key=?", (self.key,)).fetchone()[0]
            if used >= self.limit:
                raise BudgetExceeded("Rolling-24h API request limit reached")
            db.execute("INSERT INTO requests VALUES (?, ?)", (self.key, now))
