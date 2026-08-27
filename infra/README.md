# Infrastructure

## 서버 배치

### 서버 1 — 데이터 처리

- Kafka
- Collector ×5
- Spark Master / Worker
- Archiver
- HDFS NameNode / DataNode1
- Kafka / Spark exporter

### 서버 2 — 사용자 서비스

- Nginx + Flutter Web
- Spring Boot Backend
- Notification
- PostgreSQL + PostGIS
- Redis
- HDFS DataNode2
- Prometheus / Grafana

## 원칙

- 외부 공개 포트는 서버 2의 80/443만 사용합니다.
- 서버 간 포트는 두 EC2의 보안그룹끼리만 허용합니다.
- 주소·비밀번호·API 키는 환경변수로 주입합니다.
- 실제 `.env`는 Git에 커밋하지 않습니다.
- Compose 서비스와 자원 제한은 서버 CPU·디스크 확인 후 확정합니다.
