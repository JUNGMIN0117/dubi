"""검증된 SQLite 기준값을 BE 참고자료 청크로 변환한다. 네트워크/DB 쓰기 없음.

실제 관측과 시간대 평균을 혼합하지 않는다. 이 도구는 시간대 원천 평균 전용이다.
--max-chunks는 계약 시험용이며 전체 내보내기 완료로 표시하지 않는다.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import math


def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''): h.update(block)
    return h.hexdigest()


def convert(row,master_version,source_sha):
    route,a,b,ao,bo,dow,hour,total,count,start,end=row
    mean=total/count if count else float('nan')
    if not math.isfinite(mean) or not 0<mean<=86400 or int(bo)!=int(ao)+1 or str(a)==str(b):
        raise ValueError('INVALID_REFERENCE_ROW')
    return dict(masterVersion=master_version,namespace='SEOUL_BUS',routeId=str(route),
        fromStopId=str(a),toStopId=str(b),fromOrder=int(ao),toOrder=int(bo),dow=dow,hourSlot=hour,
        sampleKind='HOURLY_SOURCE_MEAN',meanSeconds=mean,sampleCount=count,observedDates=count,
        observationStart=start,observationEnd=end,sourceSha256=source_sha)


def run(folder,output,max_chunks=0):
    spec=importlib.util.spec_from_file_location('verifier',Path(__file__).with_name('verify-transit-bundle.py'))
    verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)
    if not verifier.verify(folder)['integrityVerified']: raise ValueError('BUNDLE_INTEGRITY_FAILED')
    manifest=json.loads((folder/'complete.json').read_text(encoding='utf-8'))
    meta=json.loads((folder/'master-additions-candidate.json').read_text(encoding='utf-8'))
    master=meta['parentMasterVersion']
    # 완료manifest 자체 지문을 버전으로 쓰므로 입력/코드/마스터가 바뀌면 별도 데이터 버전이다.
    code_sha=sha(Path(__file__))
    version='bus-ref-'+hashlib.sha256((sha(folder/'complete.json')+code_sha).encode()).hexdigest()[:24]
    output.mkdir(parents=True,exist_ok=False)
    files={};batch=[];count=0;limited=False
    def flush():
        nonlocal count,batch
        payload=json.dumps(dict(rows=batch),ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()
        path=output/f'{len(files):06d}.json.gz'
        path.write_bytes(gzip.compress(payload,mtime=0));files[path.name]=sha(path)
        count+=len(batch);batch=[]
    for name in sorted(manifest['outputs']):
        if not name.startswith('hourly-part-') or not name.endswith('.sqlite'): continue
        with sqlite3.connect((folder/name).resolve().as_uri()+'?mode=ro',uri=True) as db:
            cursor=db.execute('SELECT route,a,b,ao,bo,dow,hour,totalSeconds,observedDates,observationStart,observationEnd FROM hourly_reference ORDER BY rowid')
            for row in cursor:
                if max_chunks and len(files)>=max_chunks:
                    limited=True;break
                batch.append(convert(row,master,manifest['outputs'][name]))
                if len(batch)==1000: flush()
        if limited: break
    if batch: flush()
    result=dict(schemaVersion=1,dataVersion=version,masterVersion=master,rows=count,chunks=len(files),
        complete=not limited,productionReady=False,outputs=files,
        sourceManifestSha256=sha(folder/'complete.json'),codeSha256=code_sha,sampleKind='HOURLY_SOURCE_MEAN',
        limitations=['WEATHER_NOT_JOINED','SOURCE_AVAILABILITY_UNKNOWN','MASTER_EFFECTIVE_DATE_UNVERIFIED'])
    (output/'export.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    return {k:v for k,v in result.items() if k!='outputs'}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('folder',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--max-chunks',type=int,default=0)
    a=p.parse_args()
    if a.max_chunks<0: p.error('max-chunks must not be negative')
    print(json.dumps(run(a.folder,a.output,a.max_chunks),ensure_ascii=False))
