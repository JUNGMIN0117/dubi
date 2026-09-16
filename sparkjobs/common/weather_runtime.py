"""Weather stream configuration and lifecycle, testable without Spark."""
import logging
from urllib.parse import urlsplit

log = logging.getLogger(__name__)


def silver_configuration(env):
    target = env.get("SILVER_OUTPUT_URI", "").strip().rstrip("/")
    required = env.get("WEATHER_REQUIRE_SILVER", "false").strip().lower()
    if required not in ("true", "false"):
        raise ValueError("WEATHER_REQUIRE_SILVER must be true or false")
    if not target:
        if required == "true":
            raise RuntimeError("SILVER_OUTPUT_URI is required: production Weather Silver must not be silently disabled")
        return None
    uri = urlsplit(target)
    if uri.scheme not in ("hdfs", "file") or not uri.path or (uri.scheme == "hdfs" and not uri.netloc):
        raise ValueError("SILVER_OUTPUT_URI must be hdfs://host:port/path or file:///absolute/path")
    if "?" in target or "#" in target:
        raise ValueError("SILVER_OUTPUT_URI cannot contain query or fragment")
    return target + "/weather"


def supervise(streams, starters):
    """If any startup/query fails, report it and stop all already-started queries."""
    queries = []
    try:
        for start in starters:
            queries.append(start())
        while True:
            if streams.awaitAnyTermination(30):
                details = [(q.name, q.exception()) for q in queries]
                failed = next(((name, error) for name, error in details if error is not None), None)
                if failed:
                    raise RuntimeError("streaming query failed: %s: %s" % failed)
                stopped = [q.name for q in queries if not q.isActive]
                raise RuntimeError("streaming query stopped unexpectedly: %s" % stopped)
            for query in queries:
                progress = query.lastProgress
                if progress:
                    log.info("query=%s batch=%s inputRows=%s durationMs=%s", query.name,
                             progress.get("batchId"), progress.get("numInputRows"), progress.get("durationMs"))
    except Exception:
        log.exception("Weather stream failed during startup or execution")
        raise
    finally:
        for query in queries:
            try:
                if query.isActive:
                    query.stop()
            except Exception:
                log.exception("Could not stop query=%s", query.name)
