package com.ssafy.ddubi.domain.recommendation.evaluation;

import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import com.ssafy.ddubi.domain.recommendation.service.*;
import com.ssafy.ddubi.domain.route.client.PublicTransitRouteClient;
import com.ssafy.ddubi.domain.route.model.RouteSearchResult;
import com.ssafy.ddubi.domain.schedule.dto.request.*;
import com.ssafy.ddubi.domain.schedule.service.ScheduleService;
import com.ssafy.ddubi.domain.schedule.repository.ScheduleRepository;
import com.ssafy.ddubi.domain.user.entity.User;
import com.ssafy.ddubi.domain.user.repository.UserRepository;
import com.ssafy.ddubi.global.exception.BusinessException;
import com.ssafy.ddubi.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 격리 MySQL/Redis에서 등록→재평가→상태 변화→수락·취소까지 실제 저장 경계를 검증한다. */
class ScheduleEvaluationIntegrationTest extends IntegrationTestSupport {
    @Autowired ScheduleService scheduleService;
    @Autowired ScheduleRepository schedules;
    @Autowired UserRepository users;
    @Autowired ScheduleEvaluationWorkflow workflow;
    @Autowired ScheduleEvaluationStore store;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PublicTransitRouteClient routeClient;
    @MockitoBean BusPreviewClient mlClient;
    long owner;
    long scheduleId;
    Instant now;

    @BeforeEach void fixture() {
        when(mlClient.predict(any())).thenReturn(BusPreviewClient.Estimate.unavailable("ML_NOT_CONFIGURED"));
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        owner = users.save(User.builder().email("evaluation@example.test").nickname("평가 테스트").build()).getId();
        var location = new LocationRequest("강남",new BigDecimal("37.4979"),new BigDecimal("127.0276"));
        var target = new LocationRequest("역삼",new BigDecimal("37.5006"),new BigDecimal("127.0364"));
        scheduleId = scheduleService.create(owner,new ScheduleCreateRequest("테스트",location,target,
                LocalDateTime.ofInstant(now.plusSeconds(1800),ZoneId.of("Asia/Seoul")),null,null,
                LocalDateTime.ofInstant(now.plusSeconds(600),ZoneId.of("Asia/Seoul")))).id();
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO",now,null,
                List.of(RouteEvidenceAssemblerTest.candidate(0,1500,30),RouteEvidenceAssemblerTest.candidate(1,600,30))));
    }

    @Test void inputIsStoredAndPeriodicEvaluationReusesCandidates() {
        var first = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        assertThat(first.selected().assessment()).isEqualTo(RouteTimelineEvaluator.Assessment.LATE_ESTIMATE);
        assertThat(first.suggestion().proposed().journeyKey()).isEqualTo("candidate-1");
        assertThat(store.latest(scheduleId,owner).get("state")).isEqualTo("READY");
        workflow.evaluate(scheduleId,owner,TriggerType.REFRESH,now.plusSeconds(30));
        verify(routeClient,times(1)).search(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM route WHERE schedule_id=? AND is_selected=true",Integer.class,scheduleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM route_segment_edge",Integer.class)).isEqualTo(2);
        assertThat(schedules.findById(scheduleId).orElseThrow().getPlannedDepartAt())
                .isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(600),ZoneId.of("Asia/Seoul")));
    }

    @Test void previewCannotBeAcceptedOrCreateProductionRecommendation() {
        var result = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        assertThat(result.preview().status()).isEqualTo("PREVIEW");
        assertThatThrownBy(() -> store.accept(scheduleId,owner+1000,result.evaluationId(),now)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.accept(scheduleId,owner,result.evaluationId(),now)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT notification_json FROM schedule_evaluation WHERE schedule_id=?",String.class,scheduleId)).isNull();
        assertThat(store.latest(scheduleId,owner).get("state")).isEqualTo("READY");
    }

    @Test void storedEvaluationUsesMlAndReturnsFastestCandidateAtUserDeparture() {
        when(routeClient.search(any())).thenReturn(new RouteSearchResult("KAKAO",now,null,
                List.of(MlRoutePreviewTest.route(0,"1",10,0,1),MlRoutePreviewTest.route(1,"2",900,0,1))));
        when(mlClient.predict(any())).thenAnswer(call -> new BusPreviewClient.Estimate(
                ((BusPreviewClient.Input)call.getArgument(0)).routeId().equals("1") ? 200.0 : 30.0,
                "MODEL_NOT_PRODUCTION_READY","test-model",now,0,8,false));
        var result = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        assertThat(result.preview().fastest().journeyKey()).isEqualTo("candidate-1");
        assertThat(result.preview().fastest().estimatedArrivalAt()).isEqualTo(now.plusSeconds(630));
        assertThat(result.preview().fastest().departureAt()).isEqualTo(now.plusSeconds(600));
        var stored = (ScheduleEvaluationWorkflow.Outcome) store.latest(scheduleId,owner).get("result");
        assertThat(stored.preview().predictions()).hasSize(2);
        assertThat(stored.preview().candidates().get(1).quality()).isEqualTo("ML_PREVIEW");
        assertThat(stored.recommendationId()).isNull();
    }

    @Test void canceledOrEditedScheduleCannotBeOverwrittenByInFlightEvaluation() {
        var job = store.claim(scheduleId,owner,now);
        assertThatThrownBy(() -> store.claim(scheduleId,owner,now)).isInstanceOf(BusinessException.class);
        scheduleService.cancel(owner,scheduleId);
        assertThat(store.due(now.plusSeconds(121))).doesNotContain(scheduleId);
        assertThatThrownBy(() -> store.complete(job,null,false,null,now,null,null,now)).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation",Integer.class)).isZero();
    }

    @Test void eventTargetsOnlyRelatedSelectedEdgesAndInvalidatesProposalAcceptance() {
        var result = workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now);
        store.wakeSegments(List.of("BUS:9:8:7"),now);
        assertThat(jdbc.queryForObject("SELECT event_revision FROM schedule_evaluation WHERE schedule_id=?",Long.class,scheduleId)).isZero();
        store.wakeSegments(List.of("BUS:1:2:3"),now);
        assertThat(jdbc.queryForObject("SELECT event_revision FROM schedule_evaluation WHERE schedule_id=?",Long.class,scheduleId)).isEqualTo(1);
        assertThatThrownBy(() -> store.accept(scheduleId,owner,result.evaluationId(),now)).isInstanceOf(BusinessException.class);
    }

    @Test void missingDepartureAndProviderFailureRemainExplicit() {
        when(routeClient.search(any())).thenThrow(new IllegalStateException("private URL"));
        assertThatThrownBy(() -> workflow.evaluate(scheduleId,owner,TriggerType.INITIAL,now)).isInstanceOf(IllegalStateException.class);
        assertThat(store.due(now.plusSeconds(1))).doesNotContain(scheduleId);
        assertThat(store.due(now.plusSeconds(61))).contains(scheduleId);
        assertThat(jdbc.queryForObject("SELECT last_error FROM schedule_evaluation WHERE schedule_id=?",String.class,scheduleId))
                .isEqualTo("EVALUATION_FAILED");
    }
}
