"""원본 마스터 품질 오류를 누락하거나 ID를 변조하지 않는지 검사한다.

워크북은 읽기 인터페이스만 대체하며 XLSX 파일을 생성·수정하지 않는다. 실제 OA-1095
읽기는 별도 corpus Replay로 확인한다. 순번 구멍을 인접 구간으로 연결하는 오탐을 방어한다.
"""

import unittest
from unittest.mock import Mock, patch

from build_bus_mapping_corpus import identifier, read_bus_routes


class BusCorpusInputTest(unittest.TestCase):
    """필수 열, ID, 순번, 좌표와 명시적 부분 마스터의 실패 조건을 검증한다."""

    header = ("ROUTE_ID", "노선명", "순번", "NODE_ID", "ARS_ID", "정류소명", "X좌표", "Y좌표")

    def read(self, rows, route_ids=None, header=None):
        workbook = Mock()
        workbook.active.iter_rows.return_value = iter([header or self.header] + rows)
        with patch("build_bus_mapping_corpus.load_workbook", return_value=workbook):
            try:
                return read_bus_routes("readonly.xlsx", route_ids)
            finally:
                workbook.close.assert_called_once()

    def row(self, sequence=1, longitude=127.0):
        return (107000008, "422", sequence, 206000200, "47068", "성남역", longitude, 37.4)

    def test_preserves_official_node_id_instead_of_ars(self):
        result = self.read([self.row()])
        self.assertEqual("107000008", result[0]["routeId"])
        self.assertEqual("206000200", result[0]["stops"][0]["stopId"])

    def test_rejects_missing_or_duplicate_sequence(self):
        for rows in ([self.row(1), self.row(3)], [self.row(1), self.row(1)], [self.row(2)]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                self.read(rows)

    def test_rejects_invalid_coordinates_and_reports_excel_row(self):
        with self.assertRaisesRegex(ValueError, "Excel 행 2"):
            self.read([self.row(longitude=float("nan"))])

    def test_rejects_missing_header_and_unknown_selected_route(self):
        with self.assertRaises(ValueError):
            self.read([], header=("ROUTE_ID",))
        with self.assertRaises(ValueError):
            self.read([self.row()], route_ids=["missing"])

    def test_never_rounds_ids_or_converts_missing_to_text(self):
        self.assertEqual("0017", identifier("0017"))
        self.assertEqual("123", identifier(123.0))
        for value in (123.5, None, True, " "):
            with self.subTest(value=value), self.assertRaises(ValueError):
                identifier(value)


if __name__ == "__main__":
    unittest.main()
