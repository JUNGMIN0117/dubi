"""DDUBI collector entry point.

각 데이터 소스 구현은 공통 이벤트 계약으로 변환한 뒤 Kafka에 발행합니다.
"""

import logging


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    logging.getLogger(__name__).info("Collector scaffold is ready")


if __name__ == "__main__":
    main()
