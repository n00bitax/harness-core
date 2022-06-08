package io.harness.concurrency;

import static io.harness.rule.OwnerRule.SAHIL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.harness.OrchestrationTestBase;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.category.element.UnitTests;
import io.harness.engine.OrchestrationEngine;
import io.harness.engine.executions.node.NodeExecutionService;
import io.harness.execution.NodeExecution;
import io.harness.graph.stepDetail.service.PmsGraphStepDetailsService;
import io.harness.lock.PersistentLocker;
import io.harness.lock.redis.RedisAcquiredLock;
import io.harness.pms.contracts.ambiance.Ambiance;
import io.harness.pms.execution.utils.NodeProjectionUtils;
import io.harness.rule.Owner;
import io.harness.waiter.WaitNotifyEngine;

import java.util.HashMap;
import org.assertj.core.util.Lists;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

@OwnedBy(HarnessTeam.PIPELINE)
public class MaxConcurrentChildCallbackTest extends OrchestrationTestBase {
  private static final String PARENT_NODE_EXECUTION_ID = "parentExecutionId";
  private static final String PUBLISHER_NAME = "publisher";
  private static final String PLAN_EXECUTION_ID = "planExecutionId";

  @Mock OrchestrationEngine engine;
  @Mock NodeExecutionService nodeExecutionService;
  @Mock WaitNotifyEngine waitNotifyEngine;
  @Mock PersistentLocker persistentLocker;

  @Mock PmsGraphStepDetailsService nodeExecutionInfoService;

  MaxConcurrentChildCallback maxConcurrentChildCallback;
  @Before
  public void setUp() throws IllegalAccessException {
    when(persistentLocker.waitToAcquireLock(anyString(), any(), any())).thenReturn(RedisAcquiredLock.builder().build());
    Ambiance ambiance = Ambiance.newBuilder().setPlanExecutionId(PLAN_EXECUTION_ID).build();
    maxConcurrentChildCallback = MaxConcurrentChildCallback.builder()
                                     .ambiance(ambiance)
                                     .parentNodeExecutionId(PARENT_NODE_EXECUTION_ID)
                                     .engine(engine)
                                     .nodeExecutionService(nodeExecutionService)
                                     .nodeExecutionInfoService(nodeExecutionInfoService)
                                     .maxConcurrency(2)
                                     .publisherName(PUBLISHER_NAME)
                                     .waitNotifyEngine(waitNotifyEngine)
                                     .persistentLocker(persistentLocker)
                                     .build();
  }

  @Test
  @Owner(developers = SAHIL)
  @Category(UnitTests.class)
  public void testNotifyIfNullChildInstance() {
    when(nodeExecutionInfoService.incrementCursor(PARENT_NODE_EXECUTION_ID)).thenReturn(null);
    maxConcurrentChildCallback.notify(new HashMap<>());
    verify(nodeExecutionService).errorOutActiveNodes(anyString());
  }

  @Test
  @Owner(developers = SAHIL)
  @Category(UnitTests.class)
  public void testNotify() {
    when(nodeExecutionInfoService.incrementCursor(PARENT_NODE_EXECUTION_ID))
        .thenReturn(ConcurrentChildInstance.builder()
                        .cursor(1)
                        .childrenNodeExecutionIds(Lists.newArrayList("a", "b", "c"))
                        .build());
    Ambiance ambiance = Ambiance.newBuilder().setPlanExecutionId(PLAN_EXECUTION_ID).build();
    when(nodeExecutionService.getWithFieldsIncluded("b", NodeProjectionUtils.withAmbianceAndStatus))
        .thenReturn(NodeExecution.builder().ambiance(ambiance).uuid("b").build());

    maxConcurrentChildCallback.notify(new HashMap<>());

    verify(engine).startNodeExecution(ambiance);
    verify(waitNotifyEngine).waitForAllOn(eq(PUBLISHER_NAME), any(MaxConcurrentChildCallback.class), eq("b"));
  }
}