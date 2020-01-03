package software.wings.service.impl.analysis;

import static io.harness.data.structure.UUIDGenerator.generateUuid;
import static io.harness.rule.OwnerRule.KAMAL;
import static io.harness.rule.OwnerRule.PRANJAL;
import static io.harness.rule.OwnerRule.PRAVEEN;
import static io.harness.rule.OwnerRule.RAGHU;
import static io.harness.rule.OwnerRule.SOWMYA;
import static io.harness.rule.OwnerRule.SRIRAM;
import static io.harness.rule.OwnerRule.UJJAWAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;

import com.fasterxml.jackson.core.type.TypeReference;
import io.harness.beans.DelegateTask;
import io.harness.beans.PageRequest;
import io.harness.beans.PageResponse;
import io.harness.beans.PageResponse.PageResponseBuilder;
import io.harness.category.element.UnitTests;
import io.harness.rule.Owner;
import io.harness.security.encryption.EncryptedDataDetail;
import io.harness.serializer.YamlUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.wings.WingsBaseTest;
import software.wings.beans.Environment.EnvironmentType;
import software.wings.beans.SettingAttribute;
import software.wings.beans.SplunkConfig;
import software.wings.beans.TaskType;
import software.wings.beans.User;
import software.wings.common.VerificationConstants;
import software.wings.dl.WingsPersistence;
import software.wings.metrics.MetricType;
import software.wings.security.AppPermissionSummary;
import software.wings.security.AppPermissionSummary.EnvInfo;
import software.wings.security.PermissionAttribute.Action;
import software.wings.security.UserPermissionInfo;
import software.wings.service.impl.CloudWatchServiceImpl;
import software.wings.service.impl.apm.APMMetricInfo;
import software.wings.service.impl.appdynamics.AppdynamicsTimeSeries;
import software.wings.service.impl.cloudwatch.AwsNameSpace;
import software.wings.service.impl.cloudwatch.CloudWatchMetric;
import software.wings.service.impl.newrelic.NewRelicMetricValueDefinition;
import software.wings.service.impl.splunk.SplunkDataCollectionInfoV2;
import software.wings.service.intfc.AuthService;
import software.wings.service.intfc.DelegateService;
import software.wings.service.intfc.SettingsService;
import software.wings.service.intfc.security.SecretManager;
import software.wings.service.intfc.verification.CVConfigurationService;
import software.wings.sm.StateType;
import software.wings.sm.states.APMVerificationState.MetricCollectionInfo;
import software.wings.sm.states.DatadogState;
import software.wings.sm.states.DatadogState.Metric;
import software.wings.verification.HeatMapResolution;
import software.wings.verification.apm.APMCVServiceConfiguration;
import software.wings.verification.appdynamics.AppDynamicsCVServiceConfiguration;
import software.wings.verification.cloudwatch.CloudWatchCVServiceConfiguration;
import software.wings.verification.datadog.DatadogCVServiceConfiguration;
import software.wings.verification.dynatrace.DynaTraceCVServiceConfiguration;
import software.wings.verification.newrelic.NewRelicCVServiceConfiguration;
import software.wings.verification.prometheus.PrometheusCVServiceConfiguration;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Created by Praveen on 5/31/2018
 */
public class ContinuousVerificationServiceImplTest extends WingsBaseTest {
  private String accountId;
  private String appId;
  private String stateExecutionId;
  private String workflowId;
  private String workflowExecutionId;
  private String serviceId;
  private String envId;
  private User user;

  @Mock private AuthService mockAuthService;
  @Mock private WingsPersistence mockWingsPersistence;
  @Mock private UserPermissionInfo mockUserPermissionInfo;
  @Mock private CVConfigurationService cvConfigurationService;
  @InjectMocks private ContinuousVerificationServiceImpl continuousVerificationService;

  @Before
  public void setupMocks() throws IllegalAccessException {
    accountId = UUID.randomUUID().toString();
    appId = UUID.randomUUID().toString();
    stateExecutionId = UUID.randomUUID().toString();
    workflowId = UUID.randomUUID().toString();
    workflowExecutionId = UUID.randomUUID().toString();
    serviceId = UUID.randomUUID().toString();
    envId = UUID.randomUUID().toString();
    user = new User();

    MockitoAnnotations.initMocks(this);

    PageResponse<ContinuousVerificationExecutionMetaData> r =
        PageResponseBuilder.aPageResponse().withResponse(Arrays.asList(getExecutionMetadata())).build();
    PageResponse<ContinuousVerificationExecutionMetaData> rEmpty = PageResponseBuilder.aPageResponse().build();
    when(mockWingsPersistence.query(any(), any(PageRequest.class))).thenReturn(r).thenReturn(rEmpty);

    when(mockUserPermissionInfo.getAppPermissionMapInternal()).thenReturn(new HashMap<String, AppPermissionSummary>() {
      { put(appId, buildAppPermissionSummary()); }
    });

    when(mockAuthService.getUserPermissionInfo(accountId, user, false)).thenReturn(mockUserPermissionInfo);
    FieldUtils.writeField(continuousVerificationService, "cvConfigurationService", cvConfigurationService, true);
  }

  private ContinuousVerificationExecutionMetaData getExecutionMetadata() {
    return ContinuousVerificationExecutionMetaData.builder()
        .accountId(accountId)
        .applicationId(appId)
        .appName("dummy")
        .artifactName("cv dummy artifact")
        .envName("cv dummy env")
        .envId(envId)
        .phaseName("dummy phase")
        .pipelineName("dummy pipeline")
        .workflowName("dummy workflow")
        .pipelineStartTs(1519200000000L)
        .workflowStartTs(1519200000000L)
        .serviceId(serviceId)
        .serviceName("dummy service")
        .stateType(StateType.APM_VERIFICATION)
        .workflowId(workflowId)
        .workflowExecutionId(workflowExecutionId)
        .build();
  }

  private AppPermissionSummary buildAppPermissionSummary() {
    Map<Action, Set<String>> servicePermissions = new HashMap<Action, Set<String>>() {
      { put(Action.READ, Sets.newHashSet(serviceId)); }
    };
    Map<Action, Set<EnvInfo>> envPermissions = new HashMap<Action, Set<EnvInfo>>() {
      {
        put(Action.READ, Sets.newHashSet(EnvInfo.builder().envId(envId).envType(EnvironmentType.PROD.name()).build()));
      }
    };
    Map<Action, Set<String>> pipelinePermissions = new HashMap<Action, Set<String>>() {
      { put(Action.READ, Sets.newHashSet()); }
    };
    Map<Action, Set<String>> workflowPermissions = new HashMap<Action, Set<String>>() {
      { put(Action.READ, Sets.newHashSet(workflowId)); }
    };

    return AppPermissionSummary.builder()
        .servicePermissions(servicePermissions)
        .envPermissions(envPermissions)
        .workflowPermissions(workflowPermissions)
        .pipelinePermissions(pipelinePermissions)
        .build();
  }
  @Test
  @Owner(developers = SRIRAM)
  @Category(UnitTests.class)
  public void testNullUser() throws ParseException, IllegalAccessException {
    setupMocks();
    LinkedHashMap<Long,
        LinkedHashMap<String,
            LinkedHashMap<String,
                LinkedHashMap<String, LinkedHashMap<String, List<ContinuousVerificationExecutionMetaData>>>>>>
        execData =
            continuousVerificationService.getCVExecutionMetaData(accountId, 1519200000000L, 1519200000001L, null);

    assertThat(execData).isNotNull();
    assertThat(execData).hasSize(0);
  }

  @Test
  @Owner(developers = SRIRAM)
  @Category(UnitTests.class)
  public void testAllValidPermissions() throws ParseException {
    LinkedHashMap<Long,
        LinkedHashMap<String,
            LinkedHashMap<String,
                LinkedHashMap<String, LinkedHashMap<String, List<ContinuousVerificationExecutionMetaData>>>>>>
        execData =
            continuousVerificationService.getCVExecutionMetaData(accountId, 1519200000000L, 1519200000001L, user);

    assertThat(execData).isNotNull();
    assertThat(execData).hasSize(1);
  }

  @Test
  @Owner(developers = PRAVEEN)
  @Category(UnitTests.class)
  public void testNoPermissionsForEnvironment() throws ParseException {
    AppPermissionSummary permissionSummary = buildAppPermissionSummary();
    permissionSummary.setEnvPermissions(new HashMap<>());
    when(mockUserPermissionInfo.getAppPermissionMapInternal()).thenReturn(new HashMap<String, AppPermissionSummary>() {
      { put(appId, permissionSummary); }
    });

    LinkedHashMap<Long,
        LinkedHashMap<String,
            LinkedHashMap<String,
                LinkedHashMap<String, LinkedHashMap<String, List<ContinuousVerificationExecutionMetaData>>>>>>
        execData =
            continuousVerificationService.getCVExecutionMetaData(accountId, 1519200000000L, 1519200000001L, user);

    assertThat(execData).isNotNull();
    assertThat(execData).isEmpty();
  }

  @Test
  @Owner(developers = PRAVEEN)
  @Category(UnitTests.class)
  public void testNoPermissionsForService() throws ParseException {
    AppPermissionSummary permissionSummary = buildAppPermissionSummary();
    permissionSummary.setServicePermissions(new HashMap<>());
    when(mockUserPermissionInfo.getAppPermissionMapInternal()).thenReturn(new HashMap<String, AppPermissionSummary>() {
      { put(appId, permissionSummary); }
    });
    LinkedHashMap<Long,
        LinkedHashMap<String,
            LinkedHashMap<String,
                LinkedHashMap<String, LinkedHashMap<String, List<ContinuousVerificationExecutionMetaData>>>>>>
        execData =
            continuousVerificationService.getCVExecutionMetaData(accountId, 1519200000000L, 1519200000001L, user);
    assertThat(execData).isNotNull();
    assertThat(execData).hasSize(0);
  }

  @Test
  @Owner(developers = PRANJAL)
  @Category(UnitTests.class)
  public void testDataDogMetricEndPointCreation() {
    String expectedDockerCPUMetricURL =
        "query?api_key=${apiKey}&application_key=${applicationKey}&from=${start_time_seconds}&to=${end_time_seconds}&query=docker.cpu.usage{cluster-name:harness-test}.rollup(avg,60)";
    String expectedDockerMEMMetricURL =
        "query?api_key=${apiKey}&application_key=${applicationKey}&from=${start_time_seconds}&to=${end_time_seconds}&query=docker.mem.rss{cluster-name:harness-test}.rollup(avg,60)/docker.mem.limit{cluster-name:harness-test}.rollup(avg,60)*100";
    Map<String, String> dockerMetrics = new HashMap<>();
    dockerMetrics.put("cluster-name:harness-test", "docker.cpu.usage,docker.mem.rss");

    String expectedECSMetricURL =
        "query?api_key=${apiKey}&application_key=${applicationKey}&from=${start_time_seconds}&to=${end_time_seconds}&query=ecs.fargate.cpu.user{cluster-name:sdktest}.rollup(avg,60)";
    Map<String, String> ecsMetrics = new HashMap<>();
    ecsMetrics.put("cluster-name:sdktest", "ecs.fargate.cpu.user");

    String expectedCustomMetricURL =
        "query?api_key=${apiKey}&application_key=${applicationKey}&from=${start_time_seconds}&to=${end_time_seconds}&query=ec2.cpu{service_name:harness}.rollup(avg,60)";
    Map<String, Set<Metric>> customMetricsMap = new HashMap<>();
    Set<Metric> metrics = new HashSet<>();
    metrics.add(Metric.builder()
                    .metricName("ec2.cpu")
                    .displayName("ec2 cpu")
                    .mlMetricType("VALUE")
                    .datadogMetricType("Custom")
                    .build());
    customMetricsMap.put("service_name:harness", metrics);
    Map<String, List<APMMetricInfo>> metricEndPoints =
        continuousVerificationService.createDatadogMetricEndPointMap(dockerMetrics, ecsMetrics, null, customMetricsMap);

    assertThat(4).isEqualTo(metricEndPoints.size());
    assertThat(metricEndPoints.keySet().contains(expectedDockerCPUMetricURL)).isTrue();
    assertThat(metricEndPoints.keySet().contains(expectedDockerMEMMetricURL)).isTrue();
    assertThat(metricEndPoints.keySet().contains(expectedECSMetricURL)).isTrue();
    assertThat(metricEndPoints.keySet().contains(expectedCustomMetricURL)).isTrue();
  }

  @Test
  @Owner(developers = UJJAWAL)
  @Category(UnitTests.class)
  public void testHeatMapResolutionEnum() {
    long endTime = System.currentTimeMillis();

    long twelveHours = endTime - TimeUnit.HOURS.toMillis(12);
    HeatMapResolution heatMapResolution = HeatMapResolution.getResolution(twelveHours, endTime);
    assertThat(heatMapResolution).isEqualTo(HeatMapResolution.TWELVE_HOURS);

    int twelveHoursResolutionDurationInMinutes = VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES;
    assertThat(heatMapResolution.getDurationOfHeatMapUnit(heatMapResolution))
        .isEqualTo(twelveHoursResolutionDurationInMinutes);

    assertThat(heatMapResolution.getEventsPerHeatMapUnit(heatMapResolution))
        .isEqualTo(twelveHoursResolutionDurationInMinutes / VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES);

    long oneDay = endTime - TimeUnit.DAYS.toMillis(1);
    heatMapResolution = HeatMapResolution.getResolution(oneDay, endTime);
    assertThat(heatMapResolution).isEqualTo(HeatMapResolution.ONE_DAY);

    int oneDayResolutionDurationInMinutes = 2 * VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES;
    assertThat(heatMapResolution.getDurationOfHeatMapUnit(heatMapResolution))
        .isEqualTo(oneDayResolutionDurationInMinutes);

    assertThat(heatMapResolution.getEventsPerHeatMapUnit(heatMapResolution))
        .isEqualTo(oneDayResolutionDurationInMinutes / VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES);

    long sevenDays = endTime - TimeUnit.DAYS.toMillis(7);
    heatMapResolution = HeatMapResolution.getResolution(sevenDays, endTime);
    assertThat(heatMapResolution).isEqualTo(HeatMapResolution.SEVEN_DAYS);

    int sevenDayResolutionDurationInMinutes = 16 * VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES;
    assertThat(heatMapResolution.getDurationOfHeatMapUnit(heatMapResolution))
        .isEqualTo(sevenDayResolutionDurationInMinutes);

    assertThat(heatMapResolution.getEventsPerHeatMapUnit(heatMapResolution))
        .isEqualTo(sevenDayResolutionDurationInMinutes / VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES);

    long thirtyDays = endTime - TimeUnit.DAYS.toMillis(30);
    heatMapResolution = HeatMapResolution.getResolution(thirtyDays, endTime);
    assertThat(heatMapResolution).isEqualTo(HeatMapResolution.THIRTY_DAYS);

    int thirtyDayResolutionDurationInMinutes = 48 * VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES;
    assertThat(heatMapResolution.getDurationOfHeatMapUnit(heatMapResolution))
        .isEqualTo(thirtyDayResolutionDurationInMinutes);

    assertThat(heatMapResolution.getEventsPerHeatMapUnit(heatMapResolution))
        .isEqualTo(thirtyDayResolutionDurationInMinutes / VerificationConstants.CRON_POLL_INTERVAL_IN_MINUTES);
  }

  @Test
  @Owner(developers = KAMAL)
  @Category(UnitTests.class)
  public void testCollectCVData_withAllCorrectParams() throws IllegalAccessException {
    String cvTaskId = generateUuid();
    DataCollectionInfoV2 dataCollectionInfoV2 = SplunkDataCollectionInfoV2.builder()
                                                    .accountId(accountId)
                                                    .connectorId(generateUuid())
                                                    .stateExecutionId(generateUuid())
                                                    .startTime(Instant.now().minus(10, ChronoUnit.MINUTES))
                                                    .endTime(Instant.now())
                                                    .applicationId(generateUuid())
                                                    .query("query")
                                                    .hostnameField("hostnameField")
                                                    .build();
    SplunkConfig splunkConfig = mock(SplunkConfig.class);
    DelegateService delegateService = mock(DelegateService.class);
    SecretManager secretManager = mock(SecretManager.class);
    SettingsService settingsService = mock(SettingsService.class);
    FieldUtils.writeField(continuousVerificationService, "delegateService", delegateService, true);
    FieldUtils.writeField(continuousVerificationService, "secretManager", secretManager, true);
    FieldUtils.writeField(continuousVerificationService, "settingsService", settingsService, true);
    SettingAttribute settingAttribute = mock(SettingAttribute.class);
    when(settingsService.get(eq(dataCollectionInfoV2.getConnectorId()))).thenReturn(settingAttribute);
    when(settingAttribute.getValue()).thenReturn(splunkConfig);
    List<EncryptedDataDetail> encryptedDataDetails = new ArrayList<>();
    encryptedDataDetails.add(mock(EncryptedDataDetail.class));
    when(secretManager.getEncryptionDetails(any(), anyString(), anyString())).thenReturn(encryptedDataDetails);

    continuousVerificationService.collectCVData(cvTaskId, dataCollectionInfoV2);
    ArgumentCaptor<DelegateTask> argumentCaptor = ArgumentCaptor.forClass(DelegateTask.class);
    verify(delegateService).queueTask(argumentCaptor.capture());
    DelegateTask delegateTask = argumentCaptor.getValue();
    assertThat(delegateTask.getAccountId()).isEqualTo(dataCollectionInfoV2.getAccountId());
    assertThat(delegateTask.getData().getParameters()).hasSize(1);
    SplunkDataCollectionInfoV2 params = (SplunkDataCollectionInfoV2) delegateTask.getData().getParameters()[0];
    assertThat(params).isEqualTo(dataCollectionInfoV2);
    assertThat(delegateTask.getData().getTaskType()).isEqualTo(TaskType.SPLUNK_COLLECT_LOG_DATAV2.toString());
    assertThat(params.getCvTaskId()).isEqualTo(cvTaskId);
    assertThat(params.getEncryptedDataDetails()).isEqualTo(encryptedDataDetails);
    assertThat(params.getSplunkConfig()).isEqualTo(splunkConfig);
  }

  @Test
  @Owner(developers = KAMAL)
  @Category(UnitTests.class)
  public void testCollectCVData_validationFailures() throws IllegalAccessException {
    String cvTaskId = generateUuid();
    DataCollectionInfoV2 dataCollectionInfoV2 = SplunkDataCollectionInfoV2.builder()
                                                    .accountId(accountId)
                                                    .connectorId(generateUuid())
                                                    .stateExecutionId(generateUuid())
                                                    .startTime(Instant.now().minus(10, ChronoUnit.MINUTES))
                                                    .endTime(Instant.now())
                                                    .applicationId(generateUuid())
                                                    .hostnameField("hostnameField")
                                                    .build();
    SplunkConfig splunkConfig = mock(SplunkConfig.class);
    DelegateService delegateService = mock(DelegateService.class);
    SecretManager secretManager = mock(SecretManager.class);
    SettingsService settingsService = mock(SettingsService.class);
    FieldUtils.writeField(continuousVerificationService, "delegateService", delegateService, true);
    FieldUtils.writeField(continuousVerificationService, "secretManager", secretManager, true);
    FieldUtils.writeField(continuousVerificationService, "settingsService", settingsService, true);
    SettingAttribute settingAttribute = mock(SettingAttribute.class);
    when(settingsService.get(eq(dataCollectionInfoV2.getConnectorId()))).thenReturn(settingAttribute);
    when(settingAttribute.getValue()).thenReturn(splunkConfig);
    List<EncryptedDataDetail> encryptedDataDetails = new ArrayList<>();
    encryptedDataDetails.add(mock(EncryptedDataDetail.class));
    when(secretManager.getEncryptionDetails(any(), anyString(), anyString())).thenReturn(encryptedDataDetails);

    assertThatThrownBy(() -> continuousVerificationService.collectCVData(cvTaskId, dataCollectionInfoV2))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @Owner(developers = RAGHU)
  @Category(UnitTests.class)
  public void testGetAppdynamicsMetricType() {
    AppDynamicsCVServiceConfiguration cvConfig = AppDynamicsCVServiceConfiguration.builder()
                                                     .appDynamicsApplicationId(generateUuid())
                                                     .tierId(generateUuid())
                                                     .build();
    cvConfig.setStateType(StateType.APP_DYNAMICS);
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, AppdynamicsTimeSeries.RESPONSE_TIME_95.getMetricName()))
        .isEqualTo(MetricType.RESP_TIME.name());
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, AppdynamicsTimeSeries.CALLS_PER_MINUTE.getMetricName()))
        .isEqualTo(MetricType.THROUGHPUT.name());
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, AppdynamicsTimeSeries.ERRORS_PER_MINUTE.getMetricName()))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, AppdynamicsTimeSeries.STALL_COUNT.getMetricName()))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, AppdynamicsTimeSeries.AVG_RESPONSE_TIME.getMetricName()))
        .isEqualTo(MetricType.RESP_TIME.name());
    assertThat(continuousVerificationService.getMetricType(
                   cvConfig, AppdynamicsTimeSeries.NUMBER_OF_SLOW_CALLS.getMetricName()))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, generateUuid())).isNull();
  }

  @Test
  @Owner(developers = RAGHU)
  @Category(UnitTests.class)
  public void testGetNewRelicMetricType() {
    NewRelicCVServiceConfiguration cvConfig =
        NewRelicCVServiceConfiguration.builder().applicationId(generateUuid()).build();
    cvConfig.setStateType(StateType.NEW_RELIC);
    assertThat(continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.REQUSET_PER_MINUTE))
        .isEqualTo(MetricType.THROUGHPUT.name());
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.AVERAGE_RESPONSE_TIME))
        .isEqualTo(MetricType.RESP_TIME.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.ERROR))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.APDEX_SCORE))
        .isEqualTo(MetricType.APDEX.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, generateUuid())).isNull();
  }

  @Test
  @Owner(developers = RAGHU)
  @Category(UnitTests.class)
  public void testGetDynatraceMetricType() {
    DynaTraceCVServiceConfiguration cvConfig = DynaTraceCVServiceConfiguration.builder().build();
    cvConfig.setStateType(StateType.DYNA_TRACE);
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.CLIENT_SIDE_FAILURE_RATE))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.ERROR_COUNT_HTTP_4XX))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.ERROR_COUNT_HTTP_5XX))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.REQUEST_PER_MINUTE))
        .isEqualTo(MetricType.THROUGHPUT.name());
    assertThat(
        continuousVerificationService.getMetricType(cvConfig, NewRelicMetricValueDefinition.SERVER_SIDE_FAILURE_RATE))
        .isEqualTo(MetricType.ERROR.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, generateUuid())).isNull();
  }

  @Test
  @Owner(developers = RAGHU)
  @Category(UnitTests.class)
  public void testGetPrometheusMetricType() {
    PrometheusCVServiceConfiguration cvConfig =
        PrometheusCVServiceConfiguration.builder()
            .timeSeriesToAnalyze(Lists.newArrayList(TimeSeries.builder()
                                                        .txnName(generateUuid())
                                                        .metricName("metric1")
                                                        .metricType(MetricType.ERROR.name())
                                                        .url(generateUuid())
                                                        .build(),
                TimeSeries.builder()
                    .txnName(generateUuid())
                    .metricName("metric2")
                    .metricType(MetricType.THROUGHPUT.name())
                    .url(generateUuid())
                    .build()))
            .build();
    cvConfig.setStateType(StateType.PROMETHEUS);
    assertThat(continuousVerificationService.getMetricType(cvConfig, "metric1")).isEqualTo(MetricType.ERROR.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, "metric2"))
        .isEqualTo(MetricType.THROUGHPUT.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, generateUuid())).isNull();
  }

  @Test
  @Owner(developers = RAGHU)
  @Category(UnitTests.class)
  public void testGetAPMMetricType() {
    APMCVServiceConfiguration cvConfig =
        APMCVServiceConfiguration.builder()
            .metricCollectionInfos(Lists.newArrayList(
                MetricCollectionInfo.builder().metricName("metric1").metricType(MetricType.ERROR).build(),
                MetricCollectionInfo.builder().metricName("metric2").metricType(MetricType.THROUGHPUT).build()))
            .build();
    cvConfig.setStateType(StateType.APM_VERIFICATION);
    assertThat(continuousVerificationService.getMetricType(cvConfig, "metric1")).isEqualTo(MetricType.ERROR.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, "metric2"))
        .isEqualTo(MetricType.THROUGHPUT.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, generateUuid())).isNull();
  }

  @Test
  @Owner(developers = RAGHU)
  @Category(UnitTests.class)
  public void testGetCloudWatchMetricType() {
    final Map<AwsNameSpace, List<CloudWatchMetric>> awsNameSpaceMetrics = CloudWatchServiceImpl.fetchMetrics();
    CloudWatchCVServiceConfiguration cvConfig =
        CloudWatchCVServiceConfiguration.builder()
            .ec2Metrics(awsNameSpaceMetrics.get(AwsNameSpace.EC2))
            .ecsMetrics(Collections.singletonMap(generateUuid(), awsNameSpaceMetrics.get(AwsNameSpace.ECS)))
            .loadBalancerMetrics(Collections.singletonMap(generateUuid(), awsNameSpaceMetrics.get(AwsNameSpace.ELB)))
            .lambdaFunctionsMetrics(
                Collections.singletonMap(generateUuid(), awsNameSpaceMetrics.get(AwsNameSpace.LAMBDA)))
            .build();
    cvConfig.setStateType(StateType.CLOUD_WATCH);
    awsNameSpaceMetrics.forEach(
        (awsNameSpace, metrics)
            -> metrics.forEach(cloudWatchMetric
                -> assertThat(continuousVerificationService.getMetricType(cvConfig, cloudWatchMetric.getMetricName()))
                       .isEqualTo(cloudWatchMetric.getMetricType())));
    assertThat(continuousVerificationService.getMetricType(cvConfig, generateUuid())).isNull();
  }

  @Test
  @Owner(developers = RAGHU)
  @Category(UnitTests.class)
  public void testGetDatadogetricType() throws IOException {
    YamlUtils yamlUtils = new YamlUtils();
    URL url = DatadogState.class.getResource("/apm/datadog_metrics.yml");
    String yaml = Resources.toString(url, Charsets.UTF_8);
    Map<String, List<Metric>> metricsMap = yamlUtils.read(yaml, new TypeReference<Map<String, List<Metric>>>() {});

    StringBuilder dockerMetrics = new StringBuilder();
    metricsMap.get("Docker").forEach(metric -> dockerMetrics.append(metric.getMetricName()).append(","));
    dockerMetrics.deleteCharAt(dockerMetrics.lastIndexOf(","));

    StringBuilder ecsMetrics = new StringBuilder();
    metricsMap.get("ECS").forEach(metric -> ecsMetrics.append(metric.getMetricName()).append(","));
    ecsMetrics.deleteCharAt(ecsMetrics.lastIndexOf(","));

    DatadogCVServiceConfiguration cvConfig =
        DatadogCVServiceConfiguration.builder()
            .dockerMetrics(Collections.singletonMap(generateUuid(), dockerMetrics.toString()))
            .ecsMetrics(Collections.singletonMap(generateUuid(), ecsMetrics.toString()))
            .customMetrics(Collections.singletonMap(generateUuid(),
                Sets.newHashSet(
                    Metric.builder().displayName("metric1").mlMetricType(MetricType.THROUGHPUT.name()).build(),
                    Metric.builder().displayName("metric2").mlMetricType(MetricType.ERROR.name()).build())))
            .build();

    cvConfig.setStateType(StateType.DATA_DOG);
    metricsMap.get("Docker").forEach(metric
        -> assertThat(continuousVerificationService.getMetricType(cvConfig, metric.getMetricName()))
               .isEqualTo(MetricType.INFRA.name()));
    metricsMap.get("ECS").forEach(metric
        -> assertThat(continuousVerificationService.getMetricType(cvConfig, metric.getMetricName()))
               .isEqualTo(MetricType.INFRA.name()));
    assertThat(continuousVerificationService.getMetricType(cvConfig, "metric1"))
        .isEqualTo(MetricType.THROUGHPUT.name());
    assertThat(continuousVerificationService.getMetricType(cvConfig, "metric2")).isEqualTo(MetricType.ERROR.name());

    assertThat(continuousVerificationService.getMetricType(cvConfig, generateUuid())).isNull();
  }

  @Test
  @Owner(developers = SOWMYA)
  @Category(UnitTests.class)
  public void testGetCVExecutionMetaData_NoEntries() throws IllegalAccessException {
    FieldUtils.writeField(continuousVerificationService, "wingsPersistence", wingsPersistence, true);
    ContinuousVerificationExecutionMetaData cvMetaData =
        continuousVerificationService.getCVExecutionMetaData(stateExecutionId);
    assertThat(cvMetaData).isNull();
  }

  @Test
  @Owner(developers = SOWMYA)
  @Category(UnitTests.class)
  public void testGetCVExecutionMetaData_NonNullEntries() throws IllegalAccessException {
    FieldUtils.writeField(continuousVerificationService, "wingsPersistence", wingsPersistence, true);
    ContinuousVerificationExecutionMetaData savedData =
        ContinuousVerificationExecutionMetaData.builder().stateExecutionId(stateExecutionId).build();
    savedData.setUuid(generateUuid());
    wingsPersistence.save(savedData);
    ContinuousVerificationExecutionMetaData cvMetaData =
        continuousVerificationService.getCVExecutionMetaData(stateExecutionId);
    assertThat(cvMetaData).isNotNull();
    assertThat(cvMetaData.getUuid()).isEqualTo(savedData.getUuid());
  }
}
