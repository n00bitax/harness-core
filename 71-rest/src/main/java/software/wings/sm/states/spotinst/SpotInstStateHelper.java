package software.wings.sm.states.spotinst;

import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.exception.WingsException.USER;
import static io.harness.spotinst.model.SpotInstConstants.DEPLOYMENT_ERROR;
import static io.harness.spotinst.model.SpotInstConstants.GROUP_CONFIG_ELEMENT;
import static io.harness.spotinst.model.SpotInstConstants.PHASE_PARAM;
import static io.harness.spotinst.model.SpotInstConstants.SETUP_COMMAND_UNIT;
import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static software.wings.sm.states.spotinst.SpotInstServiceSetup.SPOTINST_SERVICE_SETUP_COMMAND;
import static software.wings.utils.Validator.notNullCheck;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.harness.beans.DelegateTask;
import io.harness.beans.ExecutionStatus;
import io.harness.beans.TriggeredBy;
import io.harness.context.ContextElementType;
import io.harness.delegate.beans.TaskData;
import io.harness.delegate.task.aws.LoadBalancerDetailsForBGDeployment;
import io.harness.delegate.task.spotinst.request.SpotInstSetupTaskParameters;
import io.harness.delegate.task.spotinst.request.SpotInstTaskParameters;
import io.harness.eraro.ErrorCode;
import io.harness.exception.WingsException;
import io.harness.security.encryption.EncryptedDataDetail;
import io.harness.spotinst.model.ElastiGroup;
import io.harness.spotinst.model.ElastiGroupCapacity;
import software.wings.annotation.EncryptableSetting;
import software.wings.api.PhaseElement;
import software.wings.api.ServiceElement;
import software.wings.beans.Activity;
import software.wings.beans.Activity.ActivityBuilder;
import software.wings.beans.Activity.Type;
import software.wings.beans.Application;
import software.wings.beans.AwsAmiInfrastructureMapping;
import software.wings.beans.AwsConfig;
import software.wings.beans.DeploymentExecutionContext;
import software.wings.beans.Environment;
import software.wings.beans.SettingAttribute;
import software.wings.beans.SpotInstConfig;
import software.wings.beans.TaskType;
import software.wings.beans.artifact.Artifact;
import software.wings.beans.command.CommandUnit;
import software.wings.beans.command.CommandUnitDetails.CommandUnitType;
import software.wings.beans.command.SpotinstDummyCommandUnit;
import software.wings.beans.container.UserDataSpecification;
import software.wings.service.impl.spotinst.SpotInstCommandRequest;
import software.wings.service.impl.spotinst.SpotInstCommandRequest.SpotInstCommandRequestBuilder;
import software.wings.service.intfc.ActivityService;
import software.wings.service.intfc.AppService;
import software.wings.service.intfc.InfrastructureMappingService;
import software.wings.service.intfc.ServiceResourceService;
import software.wings.service.intfc.SettingsService;
import software.wings.service.intfc.security.SecretManager;
import software.wings.sm.ExecutionContext;
import software.wings.sm.ExecutionContextImpl;
import software.wings.sm.WorkflowStandardParams;
import software.wings.utils.Misc;
import software.wings.utils.ServiceVersionConvention;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public class SpotInstStateHelper {
  @Inject private AppService appService;
  @Inject private InfrastructureMappingService infrastructureMappingService;
  @Inject private SettingsService settingsService;
  @Inject private SecretManager secretManager;
  @Inject private ActivityService activityService;
  @Inject private ServiceResourceService serviceResourceService;

  public SpotInstSetupStateExecutionData prepareStateExecutionData(
      ExecutionContext context, SpotInstServiceSetup serviceSetup) {
    PhaseElement phaseElement = context.getContextElement(ContextElementType.PARAM, PHASE_PARAM);
    WorkflowStandardParams workflowStandardParams = context.getContextElement(ContextElementType.STANDARD);

    Application app = appService.get(context.getAppId());
    Environment env = workflowStandardParams.getEnv();
    ServiceElement serviceElement = phaseElement.getServiceElement();

    Artifact artifact = ((DeploymentExecutionContext) context).getArtifactForService(serviceElement.getUuid());
    if (artifact == null) {
      throw new WingsException(format("Unable to find artifact for service id: %s", serviceElement.getUuid()));
    }

    if (serviceSetup.getOlderActiveVersionCountToKeep() == null) {
      serviceSetup.setOlderActiveVersionCountToKeep(Integer.valueOf(3));
    }

    if (serviceSetup.getOlderActiveVersionCountToKeep() <= 0) {
      throw new WingsException("Value for Older Active Versions To Keep Must be > 0");
    }

    // Remove when CurrentRunningCount is supported
    serviceSetup.setUseCurrentRunningCount(false);

    AwsAmiInfrastructureMapping awsAmiInfrastructureMapping =
        (AwsAmiInfrastructureMapping) infrastructureMappingService.get(app.getUuid(), context.fetchInfraMappingId());

    Activity activity = createActivity(context, artifact, serviceSetup.getStateType(), SPOTINST_SERVICE_SETUP_COMMAND,
        CommandUnitType.SPOTINST_SETUP,
        ImmutableList.of(
            new SpotinstDummyCommandUnit(SETUP_COMMAND_UNIT), new SpotinstDummyCommandUnit(DEPLOYMENT_ERROR)));

    SettingAttribute settingAttribute = settingsService.get(awsAmiInfrastructureMapping.getComputeProviderSettingId());
    AwsConfig awsConfig = (AwsConfig) settingAttribute.getValue();
    List<EncryptedDataDetail> awsEncryptedDataDetails = secretManager.getEncryptionDetails(
        (EncryptableSetting) settingAttribute.getValue(), context.getAppId(), context.getWorkflowExecutionId());

    settingAttribute = settingsService.get(awsAmiInfrastructureMapping.getSpotinstCloudProvider());
    SpotInstConfig spotInstConfig = (SpotInstConfig) settingAttribute.getValue();
    List<EncryptedDataDetail> spotinstEncryptedDataDetails = secretManager.getEncryptionDetails(
        (EncryptableSetting) settingAttribute.getValue(), context.getAppId(), context.getWorkflowExecutionId());

    // change ${app.name}__${service.name}__${env.name} to actual name.
    String elastiGroupNamePrefix = serviceSetup.getElastiGroupNamePrefix();

    elastiGroupNamePrefix = isBlank(elastiGroupNamePrefix)
        ? Misc.normalizeExpression(
              ServiceVersionConvention.getPrefix(app.getName(), serviceElement.getName(), env.getName()))
        : Misc.normalizeExpression(context.renderExpression(elastiGroupNamePrefix));

    String elastiGroupOriginalJson = context.renderExpression(awsAmiInfrastructureMapping.getSpotinstElastiGroupJson());
    ElastiGroup elastiGroupOriginalConfig = generateOriginalConfigFromJson(elastiGroupOriginalJson, serviceSetup);
    boolean blueGreen = serviceSetup.isBlueGreen();
    SpotInstTaskParameters spotInstTaskParameters =
        SpotInstSetupTaskParameters.builder()
            .accountId(app.getAccountId())
            .timeoutIntervalInMin(fetchTimeoutIntervalInMin(serviceSetup.getTimeoutIntervalInMin()))
            .workflowExecutionId(context.getWorkflowExecutionId())
            .appId(app.getAppId())
            .activityId(activity.getUuid())
            .commandName(SPOTINST_SERVICE_SETUP_COMMAND)
            .elastiGroupJson(elastiGroupOriginalJson)
            .blueGreen(blueGreen)
            .elastiGroupNamePrefix(elastiGroupNamePrefix)
            .awsLoadBalancerConfigs(
                addLoadBalancerConfigAfterExpressionEvaluation(serviceSetup.getAwsLoadBalancerConfigs(), context))
            .image(artifact.getRevision())
            .userData(getBase64EncodedUserData(app.getUuid(), serviceElement.getUuid(), context))
            .awsRegion(awsAmiInfrastructureMapping.getRegion())
            .build();

    SpotInstCommandRequest commandRequest = SpotInstCommandRequest.builder()
                                                .spotInstTaskParameters(spotInstTaskParameters)
                                                .awsConfig(awsConfig)
                                                .spotInstConfig(spotInstConfig)
                                                .awsEncryptionDetails(awsEncryptedDataDetails)
                                                .spotinstEncryptionDetails(spotinstEncryptedDataDetails)
                                                .build();

    return SpotInstSetupStateExecutionData.builder()
        .envId(env.getUuid())
        .appId(app.getUuid())
        .infraMappingId(awsAmiInfrastructureMapping.getUuid())
        .commandName(SPOTINST_SERVICE_SETUP_COMMAND)
        .maxInstanceCount(serviceSetup.getTargetInstances())
        .useCurrentRunningInstanceCount(serviceSetup.isUseCurrentRunningCount())
        .currentRunningInstanceCount(fetchCurrentRunningCountForSetupRequest(serviceSetup))
        .serviceId(serviceElement.getUuid())
        .spotinstCommandRequest(commandRequest)
        .elastiGroupOriginalConfig(elastiGroupOriginalConfig)
        .build();
  }

  private List<LoadBalancerDetailsForBGDeployment> addLoadBalancerConfigAfterExpressionEvaluation(
      List<LoadBalancerDetailsForBGDeployment> awsLoadBalancerConfigs, ExecutionContext context) {
    List<LoadBalancerDetailsForBGDeployment> loadBalancerConfigs = new ArrayList<>();

    awsLoadBalancerConfigs.forEach(awsLoadBalancerConfig -> {
      loadBalancerConfigs.add(
          LoadBalancerDetailsForBGDeployment.builder()
              .loadBalancerName(context.renderExpression(awsLoadBalancerConfig.getLoadBalancerName()))
              .loadBalancerArn(context.renderExpression(awsLoadBalancerConfig.getLoadBalancerArn()))
              .prodListenerPort(context.renderExpression(awsLoadBalancerConfig.getProdListenerPort()))
              .stageListenerPort(context.renderExpression(awsLoadBalancerConfig.getStageListenerPort()))
              .build());
    });

    return loadBalancerConfigs;
  }

  private int getPortNum(String port) {
    try {
      return Integer.parseInt(port);
    } catch (NumberFormatException e) {
      throw new WingsException(
          ErrorCode.INVALID_ARGUMENT, "PORT Number is invalid, Cant be cast to Integer: " + port, USER);
    }
  }

  private String getBase64EncodedUserData(String appId, String serviceId, ExecutionContext context) {
    UserDataSpecification userDataSpecification = serviceResourceService.getUserDataSpecification(appId, serviceId);
    if (userDataSpecification != null && isNotEmpty(userDataSpecification.getData())) {
      String userData = userDataSpecification.getData();
      String userDataAfterEvaluation = context.renderExpression(userData);
      return BaseEncoding.base64().encode(userDataAfterEvaluation.getBytes(Charsets.UTF_8));
    }
    return null;
  }

  private ElastiGroup generateOriginalConfigFromJson(
      String elastiGroupOriginalJson, SpotInstServiceSetup serviceSetup) {
    ElastiGroup elastiGroup = generateConfigFromJson(elastiGroupOriginalJson);
    ElastiGroupCapacity groupCapacity = elastiGroup.getCapacity();
    groupCapacity.setMaximum(serviceSetup.getMaxInstances());
    groupCapacity.setMinimum(serviceSetup.getMinInstances());
    groupCapacity.setTarget(serviceSetup.getTargetInstances());
    return elastiGroup;
  }

  private ElastiGroup generateConfigFromJson(String elastiGroupJson) {
    java.lang.reflect.Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
    Gson gson = new Gson();

    // Map<"group": {...entire config...}>, this is elastiGroupConfig json that spotinst exposes
    Map<String, Object> jsonConfigMap = gson.fromJson(elastiGroupJson, mapType);
    Map<String, Object> elastiGroupConfigMap = (Map<String, Object>) jsonConfigMap.get(GROUP_CONFIG_ELEMENT);
    String groupConfigJson = gson.toJson(elastiGroupConfigMap);
    return gson.fromJson(groupConfigJson, ElastiGroup.class);
  }

  public ActivityBuilder generateActivityBuilder(String appName, String appId, String commandName, Type type,
      ExecutionContext executionContext, String commandType, CommandUnitType commandUnitType, Environment environment,
      List<CommandUnit> commandUnits) {
    WorkflowStandardParams workflowStandardParams = executionContext.getContextElement(ContextElementType.STANDARD);
    notNullCheck("currentUser", workflowStandardParams.getCurrentUser(), USER);
    notNullCheck("workflowStandardParams", workflowStandardParams, USER);

    return Activity.builder()
        .applicationName(appName)
        .appId(appId)
        .commandName(commandName)
        .type(type)
        .commandType(commandType)
        .commandUnits(commandUnits)
        .commandUnitType(commandUnitType)
        .status(ExecutionStatus.RUNNING)
        .environmentId(environment.getUuid())
        .environmentType(environment.getEnvironmentType())
        .environmentName(environment.getName())
        .workflowType(executionContext.getWorkflowType())
        .workflowExecutionName(executionContext.getWorkflowExecutionName())
        .workflowExecutionId(executionContext.getWorkflowExecutionId())
        .workflowId(executionContext.getWorkflowId())
        .stateExecutionInstanceId(executionContext.getStateExecutionInstanceId())
        .stateExecutionInstanceName(executionContext.getStateExecutionInstanceName())
        .triggeredBy(TriggeredBy.builder()
                         .name(workflowStandardParams.getCurrentUser().getName())
                         .email(workflowStandardParams.getCurrentUser().getEmail())
                         .build());
  }

  public DelegateTask getDelegateTask(String accountId, String appId, TaskType taskType, String waitId, String envId,
      String infrastructureMappingId, Object[] parameters, int timeout) {
    return DelegateTask.builder()
        .async(true)
        .accountId(accountId)
        .appId(appId)
        .waitId(waitId)
        .data(TaskData.builder()
                  .taskType(taskType.name())
                  .parameters(parameters)
                  .timeout(TimeUnit.MINUTES.toMillis(timeout))
                  .build())
        .envId(envId)
        .infrastructureMappingId(infrastructureMappingId)
        .build();
  }

  public Activity createActivity(ExecutionContext executionContext, Artifact artifact, String stateType, String command,
      CommandUnitType commandUnitType, List<CommandUnit> commandUnits) {
    Application app = ((ExecutionContextImpl) executionContext).getApp();
    Environment env = ((ExecutionContextImpl) executionContext).getEnv();
    ActivityBuilder activityBuilder = generateActivityBuilder(app.getName(), app.getUuid(), command, Type.Command,
        executionContext, stateType, commandUnitType, env, commandUnits);

    if (artifact != null) {
      activityBuilder.artifactName(artifact.getDisplayName()).artifactId(artifact.getUuid());
    }

    return activityService.save(activityBuilder.build());
  }

  private int fetchTimeoutIntervalInMin(Integer timeoutIntervalInMin) {
    if (timeoutIntervalInMin == null || timeoutIntervalInMin.intValue() <= 0) {
      return 5;
    }

    return timeoutIntervalInMin.intValue();
  }

  private Integer fetchCurrentRunningCountForSetupRequest(SpotInstServiceSetup serviceSetup) {
    if (!serviceSetup.isUseCurrentRunningCount()) {
      return null;
    }

    if (serviceSetup.getCurrentRunningCount() == null || serviceSetup.getCurrentRunningCount().intValue() == 0) {
      return Integer.valueOf(2);
    }

    return serviceSetup.getCurrentRunningCount();
  }

  public int generateTimeOutForDelegateTask(Integer timeoutIntervalInMin) {
    return 5 + fetchTimeoutIntervalInMin(timeoutIntervalInMin);
  }

  public SpotInstCommandRequestBuilder generateSpotInstCommandRequest(
      AwsAmiInfrastructureMapping awsAmiInfrastructureMapping, ExecutionContext context) {
    // Details for SpotInstConfig
    SettingAttribute settingAttribute = settingsService.get(awsAmiInfrastructureMapping.getSpotinstCloudProvider());
    SpotInstConfig spotInstConfig = (SpotInstConfig) settingAttribute.getValue();
    List<EncryptedDataDetail> spotinstEncryptedDataDetails = secretManager.getEncryptionDetails(
        (EncryptableSetting) spotInstConfig, context.getAppId(), context.getWorkflowExecutionId());

    // Details for AwsConfig
    settingAttribute = settingsService.get(awsAmiInfrastructureMapping.getComputeProviderSettingId());
    AwsConfig awsConfig = (AwsConfig) settingAttribute.getValue();
    List<EncryptedDataDetail> awsEncryptedDataDetails = secretManager.getEncryptionDetails(
        (EncryptableSetting) awsConfig, context.getAppId(), context.getWorkflowExecutionId());

    return SpotInstCommandRequest.builder()
        .awsConfig(awsConfig)
        .awsEncryptionDetails(awsEncryptedDataDetails)
        .spotInstConfig(spotInstConfig)
        .spotinstEncryptionDetails(spotinstEncryptedDataDetails);
  }

  public ElastiGroup prepareNewElastiGroupConfigForRollback(SpotInstSetupContextElement setupContextElement) {
    ElastiGroup elastiGroup = setupContextElement.getNewElastiGroupOriginalConfig() != null
        ? setupContextElement.getNewElastiGroupOriginalConfig().clone()
        : null;
    if (elastiGroup != null) {
      elastiGroup.setCapacity(ElastiGroupCapacity.builder().maximum(0).minimum(0).target(0).build());
    }
    return elastiGroup;
  }

  public ElastiGroup prepareOldElastiGroupConfigForRollback(SpotInstSetupContextElement setupContextElement) {
    return setupContextElement.getOldElastiGroupOriginalConfig() != null
        ? setupContextElement.getOldElastiGroupOriginalConfig()
        : null;
  }
}
