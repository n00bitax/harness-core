package io.harness.cdng.pipeline.executions;

import com.google.inject.Inject;

import io.harness.ambiance.Ambiance;
import io.harness.cdng.environment.EnvironmentOutcome;
import io.harness.cdng.infra.steps.InfrastructureStep;
import io.harness.cdng.pipeline.executions.service.NgPipelineExecutionService;
import io.harness.cdng.service.beans.ServiceOutcome;
import io.harness.cdng.service.steps.ServiceStep;
import io.harness.engine.executions.node.NodeExecutionServiceImpl;
import io.harness.engine.outcomes.OutcomeService;
import io.harness.execution.NodeExecution;
import io.harness.execution.events.OrchestrationEvent;
import io.harness.execution.events.SyncOrchestrationEventHandler;
import io.harness.execution.status.Status;
import io.harness.executionplan.plancreator.beans.StepOutcomeGroup;
import io.harness.ngpipeline.common.AmbianceHelper;
import io.harness.plan.PlanNode;

import java.util.Objects;
import java.util.Optional;

public class PipelineExecutionUpdateEventHandler implements SyncOrchestrationEventHandler {
  @Inject private NgPipelineExecutionService ngPipelineExecutionService;
  @Inject private NodeExecutionServiceImpl nodeExecutionService;
  @Inject private OutcomeService outcomeService;

  @Override
  public void handleEvent(OrchestrationEvent event) {
    Ambiance ambiance = event.getAmbiance();
    String accountId = AmbianceHelper.getAccountId(ambiance);
    String orgId = AmbianceHelper.getOrgIdentifier(ambiance);
    String projectId = AmbianceHelper.getProjectIdentifier(ambiance);
    String nodeExecutionId = ambiance.obtainCurrentRuntimeId();
    String planExecutionId = ambiance.getPlanExecutionId();
    NodeExecution nodeExecution = nodeExecutionService.get(nodeExecutionId);
    if (isServiceNodeAndCompleted(nodeExecution.getNode(), nodeExecution.getStatus())) {
      Optional<ServiceOutcome> serviceOutcome = getServiceOutcome(nodeExecutionId, planExecutionId);
      serviceOutcome.ifPresent(outcome
          -> ngPipelineExecutionService.addServiceInformationToPipelineExecutionNode(
              accountId, orgId, projectId, planExecutionId, nodeExecutionId, outcome));
      return;
    }
    if (isInfrastructureNodeAndCompleted(nodeExecution.getNode(), nodeExecution.getStatus())) {
      Optional<EnvironmentOutcome> environmentOutcome = getEnvironmentOutcome(nodeExecutionId, planExecutionId);
      environmentOutcome.ifPresent(outcome
          -> ngPipelineExecutionService.addEnvironmentInformationToPipelineExecutionNode(
              accountId, orgId, projectId, planExecutionId, nodeExecutionId, outcome));
      return;
    }
    if (!shouldHandle(nodeExecution.getNode().getGroup())) {
      return;
    }
    ngPipelineExecutionService.updateStatusForGivenNode(
        accountId, orgId, projectId, ambiance.getPlanExecutionId(), nodeExecution);
  }

  private Optional<ServiceOutcome> getServiceOutcome(String planNodeId, String planExecutionId) {
    return outcomeService.findAllByRuntimeId(planExecutionId, planNodeId)
        .stream()
        .filter(outcome -> outcome instanceof ServiceOutcome)
        .map(outcome -> (ServiceOutcome) outcome)
        .findFirst();
  }

  private Optional<EnvironmentOutcome> getEnvironmentOutcome(String planNodeId, String planExecutionId) {
    return outcomeService.findAllByRuntimeId(planExecutionId, planNodeId)
        .stream()
        .filter(outcome -> outcome instanceof EnvironmentOutcome)
        .map(outcome -> (EnvironmentOutcome) outcome)
        .findFirst();
  }

  private boolean isServiceNodeAndCompleted(PlanNode node, Status status) {
    return Objects.equals(node.getStepType(), ServiceStep.STEP_TYPE) && status == Status.SUCCEEDED;
  }

  private boolean isInfrastructureNodeAndCompleted(PlanNode node, Status status) {
    return Objects.equals(node.getStepType(), InfrastructureStep.STEP_TYPE) && status == Status.SUCCEEDED;
  }

  private boolean shouldHandle(String stepOutcomeGroup) {
    return Objects.equals(stepOutcomeGroup, StepOutcomeGroup.STAGE.name())
        || Objects.equals(stepOutcomeGroup, StepOutcomeGroup.PIPELINE.name());
  }
}
