"""버스 모델 수치 확인용 내부 HTTP 서비스. 시작 시 모델을 한 번 로딩한다.

배치 API는 일부 미지원 입력을 개별 UNAVAILABLE로 반환하고 요청 자체가 잘못되면
422, 모델이 준비되지 않으면 503을 반환한다. 모든 모델 결과는 PREVIEW이며 기존
Spring 추천 결과를 자동 변경하지 않는다. 로컬은 127.0.0.1:8000으로 실행한다.
"""
from contextlib import asynccontextmanager
import logging
import os
from pathlib import Path
from datetime import date, datetime
from zoneinfo import ZoneInfo

from fastapi import FastAPI, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from predictor import BundleError, Predictor
from environment_features import prepare
from environment_schema import EnvironmentRequest
from environment_model import EnvironmentModel
from schemas import BatchRequest, BatchResponse, InvalidRequest, ServiceUnavailable
from serving_runtime import InferenceQueue, QueueBusy, RuntimeSettings

LOGGER = logging.getLogger(__name__)
DEFAULT_BUNDLE = Path(__file__).resolve().parent.parent / 'artifacts/ml-serving/20260908-v12'


def local_bundle():
    """명시 환경값이 없을 때만 검증 후 기록한 로컬 포인터를 읽는다. 경로 이탈/깨진 포인터는 실패다."""
    pointer=DEFAULT_BUNDLE.parent/'active-bundle.json'
    if not pointer.exists(): return DEFAULT_BUNDLE
    import json
    name=json.loads(pointer.read_text(encoding='utf-8'))['directory']
    if not isinstance(name,str) or Path(name).name!=name:
        raise BundleError('BUNDLE_PATH_INVALID')
    target=(DEFAULT_BUNDLE.parent/name).resolve()
    if not target.is_relative_to(DEFAULT_BUNDLE.parent.resolve()): raise BundleError('BUNDLE_PATH_INVALID')
    return target


def reference_health(model, today):
    """서울 운행일 기준 유효 범위와 원천 파일 결측을 구분한다. 결측을 정상 관측으로 채우지 않는다."""
    metadata=model.metadata()
    start=date.fromisoformat(metadata['supported_from']); end=date.fromisoformat(metadata['supported_until'])
    state='NOT_STARTED' if today<start else 'EXPIRED' if today>end else 'CURRENT'
    return dict(metadata,reference_status=state,reference_days_remaining=max(0,(end-today).days),
                missing_reference_file_count=len(model.bundle.get('missing_files',[])))


def create_app(predictor=None, settings=None, today=None):
    """테스트에서는 객체를 주입하며 실서비스에서는 환경변수로 묶음 경로를 고정한다.

로딩 실패를 정상값으로 숨기지 않고 readiness 503으로 노출한다. 예외 원문에는
서버 경로가 포함될 수 있으므로 로그·외부 응답에는 예외 종류만 남긴다.
"""
    @asynccontextmanager
    async def lifespan(application):
        policy = settings if settings is not None else RuntimeSettings.from_env(os.environ)
        application.state.inference_queue = InferenceQueue(policy)
        application.state.environment_model = None
        environment_path = os.environ.get('ML_ENVIRONMENT_BUNDLE_DIR')
        if environment_path:
            try: application.state.environment_model = EnvironmentModel(environment_path)
            except Exception as error: LOGGER.error('ENVIRONMENT_MODEL_LOAD_FAILED type=%s', type(error).__name__)
        application.state.predictor = predictor
        if predictor is None:
            try:
                application.state.predictor = Predictor(
                    os.environ['ML_BUNDLE_DIR'] if os.environ.get('ML_BUNDLE_DIR') else local_bundle(), cache_mib=policy.cache_mib)
            except Exception as error:
                LOGGER.error('MODEL_LOAD_FAILED type=%s', type(error).__name__)
        try:
            yield
        finally:
            await application.state.inference_queue.close()

    application = FastAPI(title='DDUBI 버스 모델 예측 확인', version='1.0.0', lifespan=lifespan)

    @application.exception_handler(RequestValidationError)
    async def validation_error(_, error):
        # 원본 입력이나 예외 객체를 응답에 복사하지 않는다. 필드 위치와 오류 종류만 제공한다.
        return JSONResponse(status_code=422, content={'error': 'INVALID_REQUEST', 'fields': [
            {'location': list(item['loc']), 'type': item['type']} for item in error.errors()]})

    @application.get('/health/live')
    async def live():
        """프로세스 생존 확인. 모델 준비 여부는 /health/ready로 별도 확인한다."""
        return {'status': 'UP'}

    @application.get('/health/ready')
    async def ready():
        """무결성과 오늘 KST 참조 범위를 함께 확인한다. 만료 묶음은 503이며 과거 Replay API는 별도로 유지한다."""
        model = application.state.predictor
        if model is None:
            raise HTTPException(status_code=503, detail='MODEL_NOT_READY')
        health=reference_health(model,today() if today else datetime.now(ZoneInfo('Asia/Seoul')).date())
        if health['reference_status']!='CURRENT':
            return JSONResponse(status_code=503,content={'status':'REFERENCE_DATE_UNSUPPORTED',**health})
        return {'status': 'READY_FOR_PREVIEW', **health}

    @application.post('/v1/bus/predictions:batch', response_model=BatchResponse,
                      responses={422: {'model': InvalidRequest}, 503: {'model': ServiceUnavailable}})
    async def predict(request: BatchRequest):
        """스레드 실행 전에 제한된 FIFO에 입장한다. timeout 재시도는 호출자가 소유한다.

대기열 초과/대기 만료는 MODEL_BUSY다. 모델 계산은 이벤트 루프 밖에서 실행하여
대기 중에도 health 요청을 처리한다. 클라이언트 timeout은 계산 취소를 보장하지 않는다.
"""
        model = application.state.predictor
        if model is None:
            raise HTTPException(status_code=503, detail='MODEL_NOT_READY')
        try:
            return await application.state.inference_queue.run(model.predict, request)
        except QueueBusy:
            raise HTTPException(status_code=503, detail='MODEL_BUSY') from None
        except BundleError as error:
            code = 'MODEL_BUSY' if str(error) == 'MODEL_BUSY' else 'MODEL_UNAVAILABLE'
            raise HTTPException(status_code=503, detail=code) from None
        except Exception as error:
            LOGGER.error('PREDICTION_FAILED type=%s', type(error).__name__)
            raise HTTPException(status_code=503, detail='MODEL_UNAVAILABLE') from None

    @application.post('/v1/bus/environment:prepare', responses={422: {'model': InvalidRequest}})
    async def prepare_environment(request: EnvironmentRequest):
        """학습과 같은 point-in-time 선택을 수행한다. 정답/현재 날씨의 과거 소급 입력은 허용하지 않는다."""
        data=request.model_dump(mode='json')
        try: return prepare(data['request'],data['observations'])
        except (ValueError,KeyError,TypeError):
            raise HTTPException(status_code=422,detail='INVALID_ENVIRONMENT_POINT') from None

    @application.post('/v1/bus/environment:predict', responses={503: {'model': ServiceUnavailable}})
    async def predict_environment(request: EnvironmentRequest):
        """명시 등록한 별도 모델만 사용한다. v12 모델로 신규 환경 특성을 계산했다고 표시하지 않는다."""
        prepared=await prepare_environment(request)
        model=application.state.environment_model
        if model is None: raise HTTPException(status_code=503,detail='MODEL_NOT_READY')
        try:
            prediction=await application.state.inference_queue.run(model.predict,prepared)
            return dict(prepared=prepared,prediction=prediction)
        except QueueBusy: raise HTTPException(status_code=503,detail='MODEL_BUSY') from None
        except Exception: raise HTTPException(status_code=503,detail='MODEL_UNAVAILABLE') from None

    return application


app = create_app()
