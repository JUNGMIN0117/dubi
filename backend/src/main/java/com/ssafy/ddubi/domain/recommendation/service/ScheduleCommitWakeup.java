package com.ssafy.ddubi.domain.recommendation.service;

import com.ssafy.ddubi.domain.recommendation.entity.TriggerType;
import java.time.Instant;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import jakarta.annotation.PreDestroy;

/**
 * commit 이후 외부 I/O를 bounded worker로 넘긴다. 큐 포화/프로세스 종료는 DB due 조회가 복구한다.
 * 이벤트는 최적화일 뿐 작업의 유일한 저장소가 아니며 동일 일정의 중복 실행은 store lease가 막는다.
 */
@Component
public class ScheduleCommitWakeup {
    private final ScheduleEvaluationWorkflow workflow;
    private final boolean enabled;
    private final ThreadPoolExecutor executor;
    public ScheduleCommitWakeup(ScheduleEvaluationWorkflow workflow,
            @Value("${ddubi.recommendation.commit-wakeup-enabled:true}") boolean enabled) {
        this.workflow=workflow;this.enabled=enabled;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(20),
                runnable->{Thread t=new Thread(runnable,"schedule-commit-evaluation");t.setDaemon(true);return t;},
                new ThreadPoolExecutor.AbortPolicy());
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void committed(ScheduleInputChanged change) {
        if(!enabled) return;
        try { executor.execute(()->{
            try { workflow.evaluate(change.scheduleId(),null,TriggerType.INITIAL,Instant.now()); }
            catch(RuntimeException unavailable) { /* store 실패 상태 또는 다른 lease: DB due가 재처리한다. */ }
        }); } catch(RejectedExecutionException full) { /* DB에 커밋된 입력 revision으로 복구한다. */ }
    }
    @PreDestroy public void close() { executor.shutdown(); }
}
