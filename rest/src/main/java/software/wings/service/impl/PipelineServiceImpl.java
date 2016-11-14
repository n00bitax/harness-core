package software.wings.service.impl;

import static java.util.Arrays.asList;
import static software.wings.beans.PipelineExecution.Builder.aPipelineExecution;
import static software.wings.beans.PipelineStageExecution.Builder.aPipelineStageExecution;
import static software.wings.dl.PageRequest.Builder.aPageRequest;
import static software.wings.sm.ExecutionStatus.ABORTED;
import static software.wings.sm.ExecutionStatus.ERROR;
import static software.wings.sm.ExecutionStatus.FAILED;
import static software.wings.sm.ExecutionStatus.SUCCESS;
import static software.wings.sm.StateType.APPROVAL;
import static software.wings.sm.StateType.ARTIFACT;
import static software.wings.sm.StateType.ENV_STATE;
import static software.wings.utils.Validator.notNullCheck;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.wings.api.ApprovalStateExecutionData;
import software.wings.api.EnvStateExecutionData;
import software.wings.beans.Application;
import software.wings.beans.ErrorCodes;
import software.wings.beans.ExecutionArgs;
import software.wings.beans.Pipeline;
import software.wings.beans.PipelineExecution;
import software.wings.beans.PipelineStageExecution;
import software.wings.beans.SearchFilter.Operator;
import software.wings.beans.SortOrder.OrderType;
import software.wings.beans.WorkflowExecution;
import software.wings.beans.WorkflowType;
import software.wings.beans.artifact.Artifact;
import software.wings.dl.PageRequest;
import software.wings.dl.PageResponse;
import software.wings.dl.WingsPersistence;
import software.wings.exception.WingsException;
import software.wings.service.intfc.AppService;
import software.wings.service.intfc.ArtifactService;
import software.wings.service.intfc.PipelineService;
import software.wings.service.intfc.WorkflowExecutionService;
import software.wings.service.intfc.WorkflowService;
import software.wings.sm.ExecutionStatus;
import software.wings.sm.State;
import software.wings.sm.StateExecutionData;
import software.wings.sm.StateExecutionInstance;
import software.wings.sm.StateMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;

/**
 * Created by anubhaw on 10/26/16.
 */
public class PipelineServiceImpl implements PipelineService {
  public static final List<ExecutionStatus> FINISHED_EXECUTION_STATUSES =
      Arrays.asList(SUCCESS, FAILED, ERROR, ABORTED);
  @Inject private WorkflowExecutionService workflowExecutionService;
  @Inject private WorkflowService workflowService;
  @Inject private AppService appService;
  @Inject private WingsPersistence wingsPersistence;
  @Inject private ExecutorService executorService;
  @Inject private ArtifactService artifactService;

  private final Logger logger = LoggerFactory.getLogger(getClass());

  @Override
  public PageResponse<PipelineExecution> listPipelineExecutions(PageRequest<PipelineExecution> pageRequest) {
    PageResponse<PipelineExecution> pageResponse = wingsPersistence.query(PipelineExecution.class, pageRequest);
    pageResponse.getResponse().forEach(this ::refreshPipelineExecution);
    return pageResponse;
  }

  private void refreshPipelineExecution(PipelineExecution pipelineExecution) {
    if (FINISHED_EXECUTION_STATUSES.contains(pipelineExecution.getStatus())) {
      return;
    }
    StateMachine stateMachine =
        workflowService.readLatest(pipelineExecution.getAppId(), pipelineExecution.getPipelineId());
    ImmutableMap<String, StateExecutionInstance> stateExecutionInstanceMap =
        getStateExecutionInstanceMap(pipelineExecution);
    List<PipelineStageExecution> stageExecutionDataList = new ArrayList<>();

    State currState = stateMachine.getInitialState();

    while (currState != null) {
      StateExecutionInstance stateExecutionInstance = stateExecutionInstanceMap.get(currState.getName());

      if (stateExecutionInstance == null) {
        stageExecutionDataList.add(
            aPipelineStageExecution()
                .withStateType(currState.getStateType())
                .withStateName(currState.getName())
                .withStatus(ExecutionStatus.QUEUED)
                .withEstimatedTime(pipelineExecution.getPipeline().getStateEtaMap().get(currState.getName()))
                .build());
      } else if (APPROVAL.name().equals(stateExecutionInstance.getStateType())) {
        PipelineStageExecution stageExecution = aPipelineStageExecution()
                                                    .withStateType(stateExecutionInstance.getStateType())
                                                    .withStatus(stateExecutionInstance.getStatus())
                                                    .withStateName(stateExecutionInstance.getStateName())
                                                    .withStartTs(stateExecutionInstance.getStartTs())
                                                    .withEndTs(stateExecutionInstance.getEndTs())
                                                    .build();
        StateExecutionData stateExecutionData = stateExecutionInstance.getStateExecutionMap().get(currState.getName());

        if (stateExecutionData != null && stateExecutionData instanceof ApprovalStateExecutionData) {
          stageExecution.setStateExecutionData(stateExecutionData);
        }

        stageExecutionDataList.add(stageExecution);
      } else if (ENV_STATE.name().equals(stateExecutionInstance.getStateType())) {
        PipelineStageExecution stageExecution = aPipelineStageExecution()
                                                    .withStateType(currState.getStateType())
                                                    .withStateName(currState.getName())
                                                    .withStatus(stateExecutionInstance.getStatus())
                                                    .withStartTs(stateExecutionInstance.getStartTs())
                                                    .withEndTs(stateExecutionInstance.getEndTs())
                                                    .build();

        StateExecutionData stateExecutionData = stateExecutionInstance.getStateExecutionMap().get(currState.getName());

        if (stateExecutionData != null && stateExecutionData instanceof EnvStateExecutionData) {
          EnvStateExecutionData envStateExecutionData = (EnvStateExecutionData) stateExecutionData;
          WorkflowExecution workflowExecution = workflowExecutionService.getExecutionDetails(
              pipelineExecution.getAppId(), envStateExecutionData.getWorkflowExecutionId());
          stageExecution.setWorkflowExecutions(asList(workflowExecution));
        }

        stageExecutionDataList.add(stageExecution);
      } else if (ARTIFACT.name().equals(stateExecutionInstance.getStateType())) {
        // do nothing
      } else {
        throw new WingsException(
            ErrorCodes.UNKNOWN_ERROR, "message", "Unknown stateType " + stateExecutionInstance.getStateType());
      }
      List<State> nextStates = stateMachine.getNextStates(currState.getName());
      currState = nextStates != null ? nextStates.get(0) : null;
    }

    WorkflowExecution executionDetails = workflowExecutionService.getExecutionDetails(
        pipelineExecution.getAppId(), pipelineExecution.getWorkflowExecutionId());
    pipelineExecution.setPipelineStageExecutions(stageExecutionDataList);
    pipelineExecution.setEndTs(System.currentTimeMillis());
    pipelineExecution.setStatus(executionDetails.getStatus());

    try {
      wingsPersistence.merge(pipelineExecution);
      executorService.submit(() -> updatePipelineEstimates(pipelineExecution));
    } catch (ConcurrentModificationException cex) {
      // do nothing as it gets refreshed in next fetch
      logger.error("Pipeline execution update failed " + cex); // TODO: add retry
    }
  }

  private void updatePipelineEstimates(PipelineExecution pipelineExecution) {
    if (FINISHED_EXECUTION_STATUSES.contains(pipelineExecution.getStatus())) {
      PageRequest pageRequest = aPageRequest()
                                    .addFilter("appId", Operator.EQ, pipelineExecution.getAppId())
                                    .addFilter("pipelineId", Operator.EQ, pipelineExecution.getPipelineId())
                                    .addFilter("status", Operator.EQ, ExecutionStatus.SUCCESS)
                                    .addOrder("endTs", OrderType.DESC)
                                    .withLimit("5")
                                    .build();
      List<PipelineExecution> pipelineExecutions = listPipelineExecutions(pageRequest).getResponse();

      Map<String, LongSummaryStatistics> stateEstimatesSum =
          pipelineExecutions.stream()
              .flatMap(pe -> pe.getPipelineStageExecutions().stream())
              .collect(Collectors.groupingBy(
                  PipelineStageExecution::getStateName, Collectors.summarizingLong(this ::getEstimate)));

      Map<String, Long> newEstimates = new HashMap<>();

      stateEstimatesSum.keySet().forEach(s -> {
        LongSummaryStatistics longSummaryStatistics = stateEstimatesSum.get(s);
        if (longSummaryStatistics.getCount() != 0) {
          newEstimates.put(s, longSummaryStatistics.getSum() / longSummaryStatistics.getCount());
        }
      });
      wingsPersistence.update(pipelineExecution.getPipeline(),
          wingsPersistence.createUpdateOperations(Pipeline.class).set("stateEtaMap", newEstimates));
    }
  }

  private Long getEstimate(PipelineStageExecution pipelineStageExecution) {
    if (pipelineStageExecution.getEndTs() != null && pipelineStageExecution.getStartTs() != null
        && pipelineStageExecution.getEndTs() > pipelineStageExecution.getStartTs()) {
      return pipelineStageExecution.getEndTs() - pipelineStageExecution.getStartTs();
    }
    return null;
  }

  private ImmutableMap<String, StateExecutionInstance> getStateExecutionInstanceMap(
      PipelineExecution pipelineExecution) {
    PageRequest<StateExecutionInstance> req =
        aPageRequest()
            .withLimit(PageRequest.UNLIMITED)
            .addFilter("appId", Operator.EQ, pipelineExecution.getAppId())
            .addFilter("executionUuid", Operator.EQ, pipelineExecution.getWorkflowExecutionId())
            .build();
    List<StateExecutionInstance> stateExecutionInstances =
        wingsPersistence.query(StateExecutionInstance.class, req).getResponse();
    return Maps.uniqueIndex(stateExecutionInstances, v -> v.getStateName());
  }

  @Override
  public void refreshPipelineExecutionAsync(String appId, String workflowExecutionId) {
    executorService.submit(() -> refreshPipelineExecution(appId, workflowExecutionId));
  }

  @Override
  public void refreshPipelineExecution(String appId, String workflowExecutionId) {
    PipelineExecution pipelineExecution = wingsPersistence.createQuery(PipelineExecution.class)
                                              .field("appId")
                                              .equal(appId)
                                              .field("workflowExecutionId")
                                              .equal(workflowExecutionId)
                                              .get();
    refreshPipelineExecution(pipelineExecution);
  }

  @Override
  public WorkflowExecution execute(String appId, String pipelineId, ExecutionArgs executionArgs) {
    WorkflowExecution workflowExecution =
        workflowExecutionService.triggerPipelineExecution(appId, pipelineId, executionArgs);
    Pipeline pipeline = wingsPersistence.get(Pipeline.class, appId, pipelineId);
    Application application = appService.get(appId);
    List<Artifact> artifacts = validateAndFetchArtifact(appId, executionArgs.getArtifacts());
    executionArgs.setArtifacts(artifacts);
    Artifact artifact = artifacts.get(0);
    PipelineExecution pipelineExecution = aPipelineExecution()
                                              .withAppId(appId)
                                              .withAppName(application.getName())
                                              .withPipelineId(pipelineId)
                                              .withPipeline(pipeline)
                                              .withWorkflowExecutionId(workflowExecution.getUuid())
                                              .withWorkflowType(WorkflowType.PIPELINE)
                                              .withStatus(workflowExecution.getStatus())
                                              .withStartTs(System.currentTimeMillis())
                                              .withArtifactId(artifact.getUuid())
                                              .withArtifactName(artifact.getDisplayName())
                                              .build();
    pipelineExecution = wingsPersistence.saveAndGet(PipelineExecution.class, pipelineExecution);
    refreshPipelineExecution(appId, pipelineExecution.getWorkflowExecutionId());
    return workflowExecution;
  }

  private List<Artifact> validateAndFetchArtifact(String appId, List<Artifact> artifacts) {
    notNullCheck("artifacts", artifacts);
    List<Artifact> validatedArtifacts = new ArrayList<>();
    artifacts.forEach(artifact -> {
      notNullCheck("artifact", artifact);
      validatedArtifacts.add(artifactService.get(appId, artifact.getUuid()));
    });
    return validatedArtifacts;
  }
}
