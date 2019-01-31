package io.harness.service.intfc;

import io.harness.beans.ExecutionStatus;
import software.wings.beans.ServiceSecretKey.ServiceApiVersion;
import software.wings.beans.ServiceSecretKey.ServiceType;
import software.wings.service.impl.analysis.AnalysisContext;
import software.wings.service.impl.analysis.MLAnalysisType;
import software.wings.service.impl.newrelic.LearningEngineAnalysisTask;
import software.wings.service.impl.newrelic.LearningEngineExperimentalAnalysisTask;
import software.wings.service.impl.newrelic.MLExperiments;
import software.wings.service.intfc.analysis.ClusterLevel;

import java.util.List;
import java.util.Optional;

/**
 * Created by rsingh on 1/9/18.
 */
public interface LearningEngineService {
  String RESOURCE_URL = "learning";
  boolean addLearningEngineAnalysisTask(LearningEngineAnalysisTask analysisTask);
  boolean addLearningEngineExperimentalAnalysisTask(LearningEngineExperimentalAnalysisTask analysisTask);

  LearningEngineAnalysisTask getNextLearningEngineAnalysisTask(
      ServiceApiVersion serviceApiVersion, Optional<Boolean> is24x7Task);
  LearningEngineExperimentalAnalysisTask getNextLearningEngineExperimentalAnalysisTask(
      String experimentName, ServiceApiVersion serviceApiVersion);

  boolean hasAnalysisTimedOut(String appId, String workflowExecutionId, String stateExecutionId);
  List<MLExperiments> getExperiments(MLAnalysisType ml_analysis_type);

  void markCompleted(String taskId);
  void markExpTaskCompleted(String taskId);

  void markStatus(
      String workflowExecutionId, String stateExecutionId, long analysisMinute, ExecutionStatus executionStatus);
  void markCompleted(String workflowExecutionId, String stateExecutionId, long analysisMinute, MLAnalysisType type,
      ClusterLevel level);
  void initializeServiceSecretKeys();

  String getServiceSecretKey(ServiceType serviceType);

  AnalysisContext getNextVerificationAnalysisTask(ServiceApiVersion serviceApiVersion);

  void markJobScheduled(AnalysisContext verificationAnalysisTask);
  void checkAndUpdateFailedLETask(String stateExecutionId, int analysisMinute);
}
