#!/bin/bash
# Read-only weather diagnosis. Run on server1; never prints credentials or all env.
set -u
export TZ=Asia/Seoul

printf '\n[containers]\n'
docker ps -a --format '{{.Names}} {{.Status}}' | grep -E '^(collector-weather|archiver|spark-master|spark-worker-[12]|spark-streaming-weather|hdfs-namenode) ' || true
printf '\n[weather driver state]\n'
docker inspect spark-streaming-weather --format 'status={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} restarts={{.RestartCount}} started={{.State.StartedAt}} error={{.State.Error}}' 2>&1 || true
printf '\n[selected runtime config; no credentials]\n'
docker inspect spark-streaming-weather --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^(SILVER_OUTPUT_URI|WEATHER_REQUIRE_SILVER|WEATHER_SILVER_TRIGGER|WEATHER_SILVER_WATERMARK|SPARK_STARTING_OFFSETS|SPARK_CHECKPOINT_DIR|SPARK_EXECUTOR_MEMORY|SPARK_CORES_MAX)=' || true
printf '\n[deployed code supports Silver?]\n'
docker exec spark-streaming-weather sh -c 'grep -nE "silver_configuration|silver stream started|SILVER_OUTPUT_URI|WEATHER_REQUIRE_SILVER" /opt/ddubi/sparkjobs/streaming/weather_latest.py /opt/ddubi/sparkjobs/common/weather_runtime.py' 2>&1 || true
printf '\n[recent startup, query progress and errors]\n'
docker logs spark-streaming-weather --tail 250 2>&1 | grep -E 'ERROR|Traceback|Exception|Caused by|not accepted any resources|starting topic|started topic|silver stream|query=|batch=|SILVER_OUTPUT_URI' | tail -80 || true
printf '\n[master applications and available worker resources]\n'
docker exec spark-master python3 -c 'import json,urllib.request; d=json.load(urllib.request.urlopen("http://localhost:8080/json/", timeout=5)); print(json.dumps({k:d.get(k) for k in ("status","cores","coresused","memory","memoryused","activeapps")},ensure_ascii=False))' 2>&1 || true
printf '\n[HDFS safe mode and live datanodes]\n'
docker exec hdfs-namenode hdfs dfsadmin -safemode get 2>&1 || true
docker exec hdfs-namenode hdfs dfsadmin -report 2>&1 | grep -E 'Live datanodes|Dead datanodes|Missing blocks|Under replicated' || true
printf '\n[archive, Silver, independent query checkpoints]\n'
for path in /ddubi/archive/city.raw /ddubi/silver/weather /ddubi/checkpoints/weather_latest/commits /ddubi/checkpoints/weather_latest_silver/commits; do
  printf '\n%s\n' "$path"
  docker exec hdfs-namenode hdfs dfs -ls "$path" 2>&1 | tail -8 || true
done
printf '\nArchive presence alone does not prove Spark progress. Re-run after a weather collection cycle and compare both checkpoint commit numbers.\n'
