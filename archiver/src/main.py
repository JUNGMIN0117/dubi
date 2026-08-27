"""Kafka-to-Parquet/HDFS archiver entry point."""

import logging


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    logging.getLogger(__name__).info("Archiver scaffold is ready")


if __name__ == "__main__":
    main()
