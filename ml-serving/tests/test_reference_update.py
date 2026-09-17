"""참조 갱신이 가짜 관측/잘못된 날짜 준비 상태를 만들지 않는지 검증한다."""
from datetime import date
from pathlib import Path
import sys
from types import SimpleNamespace
import pandas as pd
import pytest
from fastapi.testclient import TestClient
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from app import create_app
from tools.extend_reference_bundle import normalize, deduplicate, required_dates


@pytest.mark.parametrize('source,prefix,zero_missing', [
    ('OA-21217', '운행시간', True),
    ('OA-21218', '재차인원합', False),
    ('OA-21219', '승차_승객_수', False),
    ('OA-21220', '버스운행횟수', False),
])
def test_source_specific_zero_and_absent_observations(source, prefix, zero_missing):
    """실제 승객·운행 0을 결측으로 잃거나 미관측 운행시간 0을 정답으로 쓰는 회귀를 막는다."""
    raw = {'기준_날짜': '20260713', '노선_ID': '001', '출발_정류장_ID': '002',
           '도착_정류장_ID': '003', '정류장_ID': '002',
           '출발_정류장_순서': '7', '정류장_순서': '7'}
    raw.update({f'{prefix}_{h}시': '0' for h in range(24)})
    raw.update({f'{prefix}_1시': '', f'{prefix}_2시': '-1', f'{prefix}_3시': '12'})
    normalized, rejected, _ = normalize(pd.DataFrame([raw], dtype='string'), source)
    row = normalized.iloc[0]
    assert rejected.empty
    assert bool(pd.isna(row.h00)) == zero_missing
    if not zero_missing:
        assert row.h00 == 0
    assert pd.isna(row.h01) and pd.isna(row.h02)
    assert row.h03 == 12


def test_zero_travel_is_missing_but_zero_passengers_is_observation():
    raw={'기준_날짜':'20260713','노선_ID':'001','출발_정류장_ID':'002','도착_정류장_ID':'003',
         '출발_정류장_순서':'7','정류장_순서':'7'}
    raw.update({f'운행시간_{h:02d}시':'0' for h in range(24)})
    raw.update({f'재차인원합_{h}시':'0' for h in range(24)})
    bus,bad,keys=normalize(pd.DataFrame([raw],dtype='string'),'OA-21217')
    passenger,_,_=normalize(pd.DataFrame([raw],dtype='string'),'OA-21218')
    assert bad.empty and bus.iloc[0].provider_route_id=='001'
    assert pd.isna(bus.iloc[0].h00) and passenger.iloc[0].h00==0
    changed=bus.copy();changed['h00']=30
    clean,conflict=deduplicate(pd.concat([bus,bus,changed]),keys)
    assert clean.empty and len(conflict)==2


def test_lags_stay_training_compatible():
    days=required_dates(date(2026,10,10),date(2026,10,10))
    assert date(2026,7,25) in days['OA-21217']
    assert date(2026,10,9) not in days['OA-21217']
    assert len(days['OA-21217'])==8 and len(days['OA-21218'])==4


def test_expired_and_future_bundle_are_not_ready_and_missing_files_are_visible():
    model=SimpleNamespace(bundle={'missing_files':['bus_daily/example.parquet']},metadata=lambda:{
        'supported_from':'2026-09-10','supported_until':'2026-09-30','production_ready':False})
    for day,expected,state in [(date(2026,9,9),503,'NOT_STARTED'),(date(2026,9,30),200,'CURRENT'),(date(2026,10,1),503,'EXPIRED')]:
        with TestClient(create_app(predictor=model,today=lambda:day)) as client:
            response=client.get('/health/ready')
            assert response.status_code==expected
            assert response.json()['reference_status']==state
            assert response.json()['missing_reference_file_count']==1
            assert client.get('/health/live').status_code==200
