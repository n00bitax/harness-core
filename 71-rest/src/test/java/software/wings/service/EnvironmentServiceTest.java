package software.wings.service;

import static io.harness.beans.PageRequest.PageRequestBuilder.aPageRequest;
import static io.harness.beans.PageResponse.PageResponseBuilder.aPageResponse;
import static io.harness.beans.SearchFilter.Operator.EQ;
import static io.harness.beans.SearchFilter.Operator.IN;
import static io.harness.pcf.model.PcfConstants.VARS_YML;
import static io.harness.rule.OwnerRule.ANSHUL;
import static io.harness.rule.OwnerRule.ANUBHAW;
import static io.harness.rule.OwnerRule.GEORGE;
import static io.harness.rule.OwnerRule.RAMA;
import static io.harness.rule.OwnerRule.SRINIVAS;
import static java.time.Duration.ofSeconds;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mongodb.morphia.mapping.Mapper.ID_KEY;
import static software.wings.beans.Environment.APP_ID_KEY;
import static software.wings.beans.Environment.Builder.anEnvironment;
import static software.wings.beans.Environment.EnvironmentType.PROD;
import static software.wings.beans.Environment.GLOBAL_ENV_ID;
import static software.wings.beans.PhysicalInfrastructureMapping.Builder.aPhysicalInfrastructureMapping;
import static software.wings.beans.ServiceTemplate.Builder.aServiceTemplate;
import static software.wings.beans.ServiceVariable.Type.TEXT;
import static software.wings.beans.appmanifest.ManifestFile.VALUES_YAML_KEY;
import static software.wings.beans.appmanifest.StoreType.Local;
import static software.wings.service.intfc.EnvironmentService.DEV_ENV;
import static software.wings.service.intfc.EnvironmentService.PROD_ENV;
import static software.wings.service.intfc.EnvironmentService.QA_ENV;
import static software.wings.service.intfc.ServiceVariableService.EncryptedFieldMode.MASKED;
import static software.wings.service.intfc.ServiceVariableService.EncryptedFieldMode.OBTAIN_VALUE;
import static software.wings.utils.WingsTestConstants.ACCOUNT_ID;
import static software.wings.utils.WingsTestConstants.APP_ID;
import static software.wings.utils.WingsTestConstants.APP_NAME;
import static software.wings.utils.WingsTestConstants.ENV_DESCRIPTION;
import static software.wings.utils.WingsTestConstants.ENV_ID;
import static software.wings.utils.WingsTestConstants.ENV_NAME;
import static software.wings.utils.WingsTestConstants.HOST_CONN_ATTR_ID;
import static software.wings.utils.WingsTestConstants.SERVICE_ID;
import static software.wings.utils.WingsTestConstants.SERVICE_NAME;
import static software.wings.utils.WingsTestConstants.SERVICE_TEMPLATE_ID;
import static software.wings.utils.WingsTestConstants.SERVICE_VARIABLE_ID;
import static software.wings.utils.WingsTestConstants.SERVICE_VARIABLE_NAME;
import static software.wings.utils.WingsTestConstants.SETTING_ID;
import static software.wings.utils.WingsTestConstants.TARGET_APP_ID;
import static software.wings.utils.WingsTestConstants.TEMPLATE_DESCRIPTION;
import static software.wings.utils.WingsTestConstants.TEMPLATE_ID;

import com.google.inject.Inject;

import io.harness.beans.PageRequest;
import io.harness.beans.PageResponse;
import io.harness.beans.SearchFilter;
import io.harness.beans.SearchFilter.Operator;
import io.harness.category.element.UnitTests;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.UnexpectedException;
import io.harness.exception.WingsException;
import io.harness.lock.AcquiredLock;
import io.harness.lock.PersistentLocker;
import io.harness.persistence.HQuery;
import io.harness.rule.Owner;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mongodb.morphia.Key;
import org.mongodb.morphia.query.FieldEnd;
import org.mongodb.morphia.query.Query;
import org.mongodb.morphia.query.UpdateOperations;
import software.wings.WingsBaseTest;
import software.wings.beans.Application;
import software.wings.beans.EntityType;
import software.wings.beans.EnvSummary;
import software.wings.beans.Environment;
import software.wings.beans.Environment.EnvironmentKeys;
import software.wings.beans.Environment.EnvironmentType;
import software.wings.beans.Event.Type;
import software.wings.beans.FeatureName;
import software.wings.beans.InfrastructureMapping;
import software.wings.beans.Notification;
import software.wings.beans.PhysicalInfrastructureMapping;
import software.wings.beans.Pipeline;
import software.wings.beans.Service;
import software.wings.beans.ServiceTemplate;
import software.wings.beans.ServiceVariable;
import software.wings.beans.appmanifest.AppManifestKind;
import software.wings.beans.appmanifest.ApplicationManifest;
import software.wings.beans.appmanifest.ManifestFile;
import software.wings.beans.container.KubernetesPayload;
import software.wings.beans.stats.CloneMetadata;
import software.wings.dl.WingsPersistence;
import software.wings.scheduler.BackgroundJobScheduler;
import software.wings.service.impl.AuditServiceHelper;
import software.wings.service.impl.EnvironmentServiceImpl;
import software.wings.service.intfc.ActivityService;
import software.wings.service.intfc.AppService;
import software.wings.service.intfc.ConfigService;
import software.wings.service.intfc.EnvironmentService;
import software.wings.service.intfc.FeatureFlagService;
import software.wings.service.intfc.InfrastructureDefinitionService;
import software.wings.service.intfc.InfrastructureMappingService;
import software.wings.service.intfc.NotificationService;
import software.wings.service.intfc.PipelineService;
import software.wings.service.intfc.ResourceLookupService;
import software.wings.service.intfc.ServiceResourceService;
import software.wings.service.intfc.ServiceTemplateService;
import software.wings.service.intfc.ServiceVariableService;
import software.wings.service.intfc.WorkflowService;
import software.wings.service.intfc.yaml.EntityUpdateService;
import software.wings.service.intfc.yaml.YamlDirectoryService;
import software.wings.service.intfc.yaml.YamlPushService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by anubhaw on 6/28/16.
 */
public class EnvironmentServiceTest extends WingsBaseTest {
  private static final String FILE_CONTENT = "fileContent";

  @Mock private Application application;
  @Mock private ActivityService activityService;
  @Mock private AppService appService;
  @Mock private ConfigService configService;
  @Mock private EntityUpdateService entityUpdateService;
  @Mock private NotificationService notificationService;
  @Mock private PipelineService pipelineService;
  @Mock private ServiceResourceService serviceResourceService;
  @Mock private ServiceTemplateService serviceTemplateService;
  @Mock private ServiceVariableService serviceVariableService;
  @Mock private WingsPersistence wingsPersistence;
  @Mock private WorkflowService workflowService;
  @Mock private YamlPushService yamlPushService;
  @Mock private YamlDirectoryService yamlDirectoryService;
  @Mock private AuditServiceHelper auditServiceHelper;
  @Mock private InfrastructureDefinitionService infrastructureDefinitionService;
  @Mock private FeatureFlagService featureFlagService;
  @Mock private InfrastructureMappingService infrastructureMappingService;
  @Mock private PersistentLocker persistentLocker;
  @Mock private AcquiredLock acquiredLock;

  @Inject @InjectMocks private WingsPersistence realWingsPersistence;
  @Inject @InjectMocks private EnvironmentService environmentService;
  @Inject @InjectMocks private ResourceLookupService resourceLookupService;

  @Spy @InjectMocks private EnvironmentService spyEnvService = new EnvironmentServiceImpl();

  @Mock private BackgroundJobScheduler jobScheduler;
  @Mock private HQuery<Environment> query;
  @Mock private FieldEnd end;
  @Mock private UpdateOperations<Environment> updateOperations;

  @Captor private ArgumentCaptor<Environment> environmentArgumentCaptor;

  /**
   * Sets up.
   *
   * @throws Exception the exception
   */
  @Before
  public void setUp() throws Exception {
    when(wingsPersistence.createQuery(Environment.class)).thenReturn(query);
    when(query.filter(any(), any())).thenReturn(query);
    when(wingsPersistence.createUpdateOperations(Environment.class)).thenReturn(updateOperations);
    when(updateOperations.set(any(), any())).thenReturn(updateOperations);
    when(updateOperations.unset(any())).thenReturn(updateOperations);
    when(appService.get(TARGET_APP_ID)).thenReturn(Application.Builder.anApplication().accountId(ACCOUNT_ID).build());
    when(appService.get(APP_ID)).thenReturn(Application.Builder.anApplication().accountId(ACCOUNT_ID).build());

    doNothing().when(auditServiceHelper).reportForAuditingUsingAccountId(anyString(), any(), any(), any());
    doNothing().when(auditServiceHelper).reportDeleteForAuditingUsingAccountId(anyString(), any());
    doNothing().when(auditServiceHelper).reportDeleteForAuditing(anyString(), any());
    doNothing().when(auditServiceHelper).reportForAuditingUsingAppId(anyString(), any(), any(), any());
  }

  /**
   * Should list environments.
   */
  @Test
  @Owner(developers = ANUBHAW)
  @Category(UnitTests.class)
  public void shouldListEnvironments() {
    Environment environment = anEnvironment().appId(APP_ID).uuid(ENV_ID).build();
    PageRequest<Environment> envPageRequest = new PageRequest<>();
    PageResponse<Environment> envPageResponse = new PageResponse<>();
    envPageResponse.setResponse(asList(environment));
    when(wingsPersistence.query(Environment.class, envPageRequest)).thenReturn(envPageResponse);

    ServiceTemplate serviceTemplate = aServiceTemplate().build();
    PageRequest<ServiceTemplate> serviceTemplatePageRequest = new PageRequest<>();
    serviceTemplatePageRequest.addFilter("appId", Operator.EQ, environment.getAppId());
    serviceTemplatePageRequest.addFilter("envId", EQ, environment.getUuid());
    PageResponse<ServiceTemplate> serviceTemplatePageResponse = new PageResponse<>();
    serviceTemplatePageResponse.setResponse(asList(serviceTemplate));
    when(serviceTemplateService.list(serviceTemplatePageRequest, false, OBTAIN_VALUE))
        .thenReturn(serviceTemplatePageResponse);

    PageResponse<Environment> environments = environmentService.list(envPageRequest, true, false, null);

    assertThat(environments).containsAll(asList(environment));
    assertThat(environments.get(0).getServiceTemplates()).containsAll(asList(serviceTemplate));
    verify(serviceTemplateService).list(serviceTemplatePageRequest, false, OBTAIN_VALUE);
  }

  /**
   * Should get environment.
   */
  @Test
  @Owner(developers = GEORGE)
  @Category(UnitTests.class)
  public void shouldGetEnvironment() {
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID))
        .thenReturn(anEnvironment().uuid(ENV_ID).appId(APP_ID).build());
    when(serviceTemplateService.list(any(PageRequest.class), eq(false), eq(OBTAIN_VALUE)))
        .thenReturn(new PageResponse<>());
    environmentService.get(APP_ID, ENV_ID, true);
    verify(wingsPersistence).getWithAppId(Environment.class, APP_ID, ENV_ID);
  }

  @Test
  @Owner(developers = SRINIVAS)
  @Category(UnitTests.class)
  public void shouldGetEnvironmentOnly() {
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID))
        .thenReturn(anEnvironment().uuid(ENV_ID).appId(APP_ID).build());
    Environment environment = environmentService.get(APP_ID, ENV_ID);
    assertThat(environment).isNotNull();
    verify(wingsPersistence).getWithAppId(Environment.class, APP_ID, ENV_ID);
  }
  @Test
  @Owner(developers = ANUBHAW)
  @Category(UnitTests.class)
  public void shouldReturnTrueForExistingEnvironmentInExistApi() {
    when(query.getKey()).thenReturn(new Key<>(Environment.class, "environments", ENV_ID));
    assertThat(environmentService.exist(APP_ID, ENV_ID)).isTrue();
    verify(query).filter(ID_KEY, ENV_ID);
    verify(query).filter("appId", APP_ID);
  }

  /**
   * Should save environment.
   */
  @Test
  @Owner(developers = ANUBHAW)
  @Category(UnitTests.class)
  public void shouldSaveEnvironment() {
    Environment environment = anEnvironment().appId(APP_ID).name(ENV_NAME).description(ENV_DESCRIPTION).build();
    Environment savedEnvironment =
        anEnvironment().appId(APP_ID).uuid(ENV_ID).name(ENV_NAME).description(ENV_DESCRIPTION).build();
    when(wingsPersistence.saveAndGet(Environment.class, environment)).thenReturn(savedEnvironment);

    environmentService.save(environment);
    verify(wingsPersistence).saveAndGet(Environment.class, environment);
    verify(serviceTemplateService).createDefaultTemplatesByEnv(savedEnvironment);
    verify(notificationService).sendNotificationAsync(any(Notification.class));
  }

  private void cloneEnvironmentWithInfraMapping(boolean differentApp, InfrastructureMapping infrastructureMapping) {
    if (differentApp) {
      when(appService.get(APP_ID)).thenReturn(application);
    }

    Environment environment =
        anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).description(ENV_DESCRIPTION).build();

    Environment clonedEnvironment = environment.cloneInternal();
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.saveAndGet(any(), any(Environment.class))).thenReturn(clonedEnvironment);

    PageRequest<ServiceTemplate> pageRequest = new PageRequest<>();
    pageRequest.addFilter("appId", SearchFilter.Operator.EQ, environment.getAppId());
    pageRequest.addFilter("envId", EQ, environment.getUuid());

    ServiceTemplate serviceTemplate = aServiceTemplate()
                                          .withUuid(TEMPLATE_ID)
                                          .withDescription(TEMPLATE_DESCRIPTION)
                                          .withServiceId(SERVICE_ID)
                                          .withEnvId(GLOBAL_ENV_ID)
                                          .withInfrastructureMappings(asList(infrastructureMapping))
                                          .build();

    ServiceTemplate clonedServiceTemplate = serviceTemplate.cloneInternal();
    PageResponse<ServiceTemplate> pageResponse = aPageResponse().withResponse(asList(serviceTemplate)).build();
    when(serviceTemplateService.list(pageRequest, false, OBTAIN_VALUE)).thenReturn(pageResponse);
    when(serviceTemplateService.save(any(ServiceTemplate.class))).thenReturn(clonedServiceTemplate);
    when(serviceTemplateService.get(APP_ID, serviceTemplate.getEnvId(), serviceTemplate.getUuid(), true, MASKED))
        .thenReturn(serviceTemplate);
    when(infrastructureMappingService.save(infrastructureMapping, true))
        .thenReturn(aPhysicalInfrastructureMapping().build());

    CloneMetadata cloneMetadata = CloneMetadata.builder().environment(environment).build();

    when(featureFlagService.isEnabled(any(FeatureName.class), any())).thenReturn(Boolean.TRUE);
    doNothing()
        .when(infrastructureDefinitionService)
        .cloneInfrastructureDefinitions(eq(APP_ID), eq(ENV_ID), eq(APP_ID), any());

    clonedEnvironment = environmentService.cloneEnvironment(APP_ID, ENV_ID, cloneMetadata);

    verify(wingsPersistence).getWithAppId(Environment.class, APP_ID, ENV_ID);
    verify(wingsPersistence).saveAndGet(any(), any(Environment.class));
    verify(serviceTemplateService).list(pageRequest, false, OBTAIN_VALUE);
    verify(serviceTemplateService).save(any(ServiceTemplate.class));
    verify(serviceTemplateService).get(APP_ID, serviceTemplate.getEnvId(), serviceTemplate.getUuid(), true, MASKED);
    verify(infrastructureDefinitionService, times(1))
        .cloneInfrastructureDefinitions(APP_ID, ENV_ID, APP_ID, clonedEnvironment.getUuid());
    verify(infrastructureMappingService, times(1)).save(infrastructureMapping, true);
  }

  private void shouldCloneEnvironment(boolean differentApp) {
    PhysicalInfrastructureMapping physicalInfrastructureMapping = aPhysicalInfrastructureMapping()
                                                                      .withHostConnectionAttrs(HOST_CONN_ATTR_ID)
                                                                      .withComputeProviderSettingId(SETTING_ID)
                                                                      .withAppId(APP_ID)
                                                                      .withEnvId(ENV_ID)
                                                                      .withServiceTemplateId(TEMPLATE_ID)
                                                                      .build();

    cloneEnvironmentWithInfraMapping(differentApp, physicalInfrastructureMapping);
  }

  /**
   * Should clone environment.
   */
  @Test
  @Owner(developers = RAMA)
  @Category(UnitTests.class)
  public void shouldCloneEnvironment() {
    shouldCloneEnvironment(false);
  }

  /**
   * Should clone environment.
   */
  @Test
  @Owner(developers = SRINIVAS)
  @Category(UnitTests.class)
  public void shouldCloneEnvironmentAcrossApp() {
    shouldCloneEnvironment(true);
  }

  /**
   * Should update environment.
   */
  @Test
  @Owner(developers = GEORGE)
  @Category(UnitTests.class)
  public void shouldUpdateEnvironment() {
    Environment savedEnv = anEnvironment().appId(APP_ID).uuid(ENV_ID).name("PROD").build();
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID)).thenReturn(savedEnv);
    Environment environment = anEnvironment()
                                  .appId(APP_ID)
                                  .uuid(ENV_ID)
                                  .name(ENV_NAME)
                                  .environmentType(PROD)
                                  .description(ENV_DESCRIPTION)
                                  .build();
    environmentService.update(environment);
    verify(wingsPersistence).update(savedEnv, updateOperations);
    verify(wingsPersistence, times(2)).getWithAppId(Environment.class, APP_ID, ENV_ID);
  }

  /**
   * Should delete environment.
   */
  @Test
  @Owner(developers = ANUBHAW)
  @Category(UnitTests.class)
  public void shouldDeleteEnvironment() {
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID))
        .thenReturn(anEnvironment().appId(APP_ID).uuid(ENV_ID).name("PROD").build());
    when(wingsPersistence.delete(any(Environment.class))).thenReturn(true);
    when(pipelineService.listPipelines(any(PageRequest.class))).thenReturn(aPageResponse().build());
    environmentService.delete(APP_ID, ENV_ID);
    InOrder inOrder = inOrder(wingsPersistence, serviceTemplateService, notificationService);
    inOrder.verify(wingsPersistence).getWithAppId(Environment.class, APP_ID, ENV_ID);
    inOrder.verify(wingsPersistence).delete(any(Environment.class));
    inOrder.verify(notificationService).sendNotificationAsync(any());
  }

  // We are not throwing an exception anymore and this will be ignored for now
  @Test
  @Owner(developers = ANUBHAW)
  @Category(UnitTests.class)
  public void shouldThrowExceptionOnReferencedEnvironmentDelete() {
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID))
        .thenReturn(anEnvironment().appId(APP_ID).uuid(ENV_ID).name("PROD").build());
    when(wingsPersistence.delete(any(Query.class))).thenReturn(true);
    when(pipelineService.listPipelines(any(PageRequest.class)))
        .thenReturn(aPageResponse().withResponse(asList(Pipeline.builder().name("PIPELINE_NAME").build())).build());
    List<String> pipelineNames = new ArrayList<>();
    pipelineNames.add("PIPELINE_NAME");
    when(pipelineService.obtainPipelineNamesReferencedByEnvironment(APP_ID, ENV_ID)).thenReturn(pipelineNames);
    assertThatThrownBy(() -> environmentService.delete(APP_ID, ENV_ID))
        .isInstanceOf(WingsException.class)
        .hasMessage("Environment is referenced by 1 pipeline [PIPELINE_NAME].");
  }

  @Test
  @Owner(developers = GEORGE)
  @Category(UnitTests.class)
  public void shouldPruneByApplication() {
    when(query.asList()).thenReturn(asList(anEnvironment().appId(APP_ID).uuid(ENV_ID).name("PROD").build()));
    when(wingsPersistence.delete(any(Environment.class))).thenReturn(true);
    when(pipelineService.listPipelines(any(PageRequest.class))).thenReturn(aPageResponse().build());
    environmentService.pruneByApplication(APP_ID);
    InOrder inOrder =
        inOrder(wingsPersistence, activityService, serviceTemplateService, notificationService, workflowService);
    inOrder.verify(wingsPersistence).delete(any(Environment.class));
    inOrder.verify(serviceTemplateService).pruneByEnvironment(APP_ID, ENV_ID);
  }

  @Test
  @Owner(developers = GEORGE)
  @Category(UnitTests.class)
  public void shouldPruneDescendingObjects() {
    environmentService.pruneDescendingEntities(APP_ID, ENV_ID);

    InOrder inOrder =
        inOrder(wingsPersistence, activityService, serviceTemplateService, notificationService, workflowService);
    inOrder.verify(serviceTemplateService).pruneByEnvironment(APP_ID, ENV_ID);
  }

  @Test
  @Owner(developers = GEORGE)
  @Category(UnitTests.class)
  public void shouldPruneDescendingObjectsSomeFailed() {
    doThrow(new WingsException("Forced exception")).when(serviceTemplateService).pruneByEnvironment(APP_ID, ENV_ID);

    assertThatThrownBy(() -> environmentService.pruneDescendingEntities(APP_ID, ENV_ID));

    InOrder inOrder =
        inOrder(wingsPersistence, activityService, serviceTemplateService, notificationService, workflowService);
    inOrder.verify(serviceTemplateService).pruneByEnvironment(APP_ID, ENV_ID);
  }

  /**
   * Should create default environments.
   */
  @Test
  @Owner(developers = ANUBHAW)
  @Category(UnitTests.class)
  public void shouldCreateDefaultEnvironments() {
    doReturn(anEnvironment().build()).when(spyEnvService).save(any(Environment.class));
    spyEnvService.createDefaultEnvironments(APP_ID);
    verify(spyEnvService, times(3)).save(environmentArgumentCaptor.capture());
    assertThat(environmentArgumentCaptor.getAllValues())
        .extracting(Environment::getName)
        .containsExactly(DEV_ENV, QA_ENV, PROD_ENV);
  }

  @Test
  @Owner(developers = SRINIVAS)
  @Category(UnitTests.class)
  public void shouldGetServicesWithOverridesEmpty() {
    Environment environment =
        anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).description(ENV_DESCRIPTION).build();
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID)).thenReturn(environment);

    PageRequest<ServiceTemplate> pageRequest = new PageRequest<>();
    pageRequest.addFilter("appId", SearchFilter.Operator.EQ, environment.getAppId());
    pageRequest.addFilter("envId", EQ, environment.getUuid());

    PhysicalInfrastructureMapping physicalInfrastructureMapping = aPhysicalInfrastructureMapping()
                                                                      .withHostConnectionAttrs(HOST_CONN_ATTR_ID)
                                                                      .withComputeProviderSettingId(SETTING_ID)
                                                                      .withAppId(APP_ID)
                                                                      .withEnvId(ENV_ID)
                                                                      .withServiceTemplateId(TEMPLATE_ID)
                                                                      .build();

    ServiceTemplate serviceTemplate = aServiceTemplate()
                                          .withUuid(TEMPLATE_ID)
                                          .withDescription(TEMPLATE_DESCRIPTION)
                                          .withServiceId(SERVICE_ID)
                                          .withEnvId(GLOBAL_ENV_ID)
                                          .withInfrastructureMappings(asList(physicalInfrastructureMapping))
                                          .build();

    PageResponse<ServiceTemplate> pageResponse = aPageResponse().withResponse(asList(serviceTemplate)).build();
    when(serviceTemplateService.list(pageRequest, false, OBTAIN_VALUE)).thenReturn(pageResponse);
    when(serviceTemplateService.get(APP_ID, serviceTemplate.getEnvId(), serviceTemplate.getUuid(), true, MASKED))
        .thenReturn(serviceTemplate);

    List<Service> services = environmentService.getServicesWithOverrides(APP_ID, ENV_ID);
    assertThat(services).isNotNull().size().isEqualTo(0);

    verify(serviceVariableService).getServiceVariablesByTemplate(APP_ID, ENV_ID, serviceTemplate, MASKED);
    verify(configService)
        .getConfigFileByTemplate(environment.getAppId(), environment.getUuid(), serviceTemplate.getUuid());
  }

  @Test
  @Owner(developers = SRINIVAS)
  @Category(UnitTests.class)
  public void shouldObtainEnvironmentSummaries() {
    when(query.project(EnvironmentKeys.name, true)).thenReturn(query);
    when(query.project(APP_ID_KEY, true)).thenReturn(query);
    when(query.project(EnvironmentKeys.environmentType, true)).thenReturn(query);
    when(query.project(Environment.ID_KEY, true)).thenReturn(query);
    when(query.field(Environment.ID_KEY)).thenReturn(end);
    when(end.in(asList(ENV_ID))).thenReturn(query);
    when(query.asList())
        .thenReturn(asList(Environment.Builder.anEnvironment().appId(APP_ID).uuid(ENV_ID).name(ENV_NAME).build()));
    List<EnvSummary> environmentSummaries = environmentService.obtainEnvironmentSummaries(APP_ID, asList(ENV_ID));
    assertThat(environmentSummaries).isNotEmpty();
    assertThat(environmentSummaries).extracting(EnvSummary::getName).contains(ENV_NAME);
    assertThat(environmentSummaries).extracting(EnvSummary::getUuid).contains(ENV_ID);
    assertThat(environmentSummaries).extracting(EnvSummary::getEnvironmentType).contains(EnvironmentType.NON_PROD);
  }

  @Test
  @Owner(developers = SRINIVAS)
  @Category(UnitTests.class)
  public void shouldGetServicesWithOverrides() {
    Environment environment =
        anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).description(ENV_DESCRIPTION).build();
    when(wingsPersistence.getWithAppId(Environment.class, APP_ID, ENV_ID)).thenReturn(environment);

    PageRequest<ServiceTemplate> pageRequest = new PageRequest<>();
    pageRequest.addFilter("appId", SearchFilter.Operator.EQ, environment.getAppId());
    pageRequest.addFilter("envId", EQ, environment.getUuid());

    PhysicalInfrastructureMapping physicalInfrastructureMapping = aPhysicalInfrastructureMapping()
                                                                      .withHostConnectionAttrs(HOST_CONN_ATTR_ID)
                                                                      .withComputeProviderSettingId(SETTING_ID)
                                                                      .withAppId(APP_ID)
                                                                      .withEnvId(ENV_ID)
                                                                      .withServiceTemplateId(TEMPLATE_ID)
                                                                      .build();

    ServiceTemplate serviceTemplate = aServiceTemplate()
                                          .withUuid(TEMPLATE_ID)
                                          .withDescription(TEMPLATE_DESCRIPTION)
                                          .withServiceId(SERVICE_ID)
                                          .withEnvId(GLOBAL_ENV_ID)
                                          .withInfrastructureMappings(asList(physicalInfrastructureMapping))
                                          .build();

    PageResponse<ServiceTemplate> pageResponse = aPageResponse().withResponse(asList(serviceTemplate)).build();
    when(serviceTemplateService.list(pageRequest, false, OBTAIN_VALUE)).thenReturn(pageResponse);
    when(serviceTemplateService.get(APP_ID, serviceTemplate.getEnvId(), serviceTemplate.getUuid(), true, MASKED))
        .thenReturn(serviceTemplate);

    ServiceVariable serviceVariable = ServiceVariable.builder()
                                          .envId(ENV_ID)
                                          .entityType(EntityType.SERVICE_TEMPLATE)
                                          .entityId(TEMPLATE_ID)
                                          .templateId(TEMPLATE_ID)
                                          .name(SERVICE_VARIABLE_NAME)
                                          .type(TEXT)
                                          .value("8080".toCharArray())
                                          .build();
    serviceVariable.setAppId(APP_ID);
    serviceVariable.setUuid(SERVICE_VARIABLE_ID);

    PageResponse<ServiceVariable> serviceVariableResponse =
        aPageResponse().withResponse(asList(serviceVariable)).build();
    when(serviceVariableService.getServiceVariablesByTemplate(APP_ID, ENV_ID, serviceTemplate, MASKED))
        .thenReturn(serviceVariableResponse);

    Service service = Service.builder().uuid(SERVICE_ID).name(SERVICE_NAME).appId(APP_ID).build();
    PageRequest<Service> servicePageRequest = aPageRequest()
                                                  .withLimit(PageRequest.UNLIMITED)
                                                  .addFilter("appId", EQ, environment.getAppId())
                                                  .addFilter("uuid", IN, asList(SERVICE_ID).toArray())
                                                  .addFieldsExcluded("appContainer")
                                                  .build();
    PageResponse<Service> servicesResponse = aPageResponse().withResponse(asList(service)).build();

    when(serviceResourceService.list(servicePageRequest, false, false, false, null)).thenReturn(servicesResponse);

    List<Service> services = environmentService.getServicesWithOverrides(APP_ID, ENV_ID);

    assertThat(services).isNotNull().size().isEqualTo(1);
    verify(serviceVariableService).getServiceVariablesByTemplate(APP_ID, ENV_ID, serviceTemplate, MASKED);
    verify(configService, times(0))
        .getConfigFileByTemplate(environment.getAppId(), environment.getUuid(), serviceTemplate.getUuid());
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testCreateValues() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();

    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);

    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();
    manifestFile = environmentService.createValues(APP_ID, ENV_ID, null, manifestFile, null);

    assertThat(manifestFile.getFileContent()).isEqualTo(FILE_CONTENT);
    assertThat(manifestFile.getFileName()).isEqualTo(VALUES_YAML_KEY);
    assertThat(manifestFile.getAppId()).isEqualTo(APP_ID);

    ApplicationManifest applicationManifest =
        realWingsPersistence.get(ApplicationManifest.class, manifestFile.getApplicationManifestId());
    assertThat(applicationManifest.getAppId()).isEqualTo(APP_ID);
    assertThat(applicationManifest.getKind()).isEqualTo(AppManifestKind.VALUES);
    assertThat(applicationManifest.getEnvId()).isEqualTo(ENV_ID);
    assertThat(applicationManifest.getStoreType()).isEqualTo(Local);
    assertThat(applicationManifest.getServiceId()).isNull();
    assertThat(applicationManifest.getGitFileConfig()).isNull();
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testCreateValuesWithExistingAppManifest() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();
    ApplicationManifest applicationManifest =
        ApplicationManifest.builder().storeType(Local).kind(AppManifestKind.VALUES).envId(ENV_ID).build();
    applicationManifest.setAppId(APP_ID);

    realWingsPersistence.save(applicationManifest);
    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);

    PageRequest<ServiceTemplate> pageRequest = new PageRequest<>();
    pageRequest.addFilter("appId", EQ, environment.getAppId());
    pageRequest.addFilter("envId", EQ, environment.getUuid());
    when(serviceTemplateService.list(pageRequest, false, OBTAIN_VALUE)).thenReturn(aPageResponse().build());

    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();
    manifestFile = environmentService.createValues(APP_ID, ENV_ID, null, manifestFile, AppManifestKind.VALUES);

    assertThat(manifestFile.getFileContent()).isEqualTo(FILE_CONTENT);
    assertThat(manifestFile.getFileName()).isEqualTo(VALUES_YAML_KEY);
    assertThat(manifestFile.getAppId()).isEqualTo(APP_ID);
    assertThat(manifestFile.getApplicationManifestId()).isEqualTo(applicationManifest.getUuid());
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testUpdateValues() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();
    ApplicationManifest applicationManifest =
        ApplicationManifest.builder().storeType(Local).kind(AppManifestKind.VALUES).envId(ENV_ID).build();
    applicationManifest.setAppId(APP_ID);
    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();

    realWingsPersistence.save(applicationManifest);
    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);
    realWingsPersistence.save(manifestFile);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);

    PageRequest<ServiceTemplate> pageRequest = new PageRequest<>();
    pageRequest.addFilter("appId", EQ, environment.getAppId());
    pageRequest.addFilter("envId", EQ, environment.getUuid());
    when(serviceTemplateService.list(pageRequest, false, OBTAIN_VALUE)).thenReturn(aPageResponse().build());

    manifestFile.setFileContent("updated" + FILE_CONTENT);
    manifestFile.setFileName("ABC");
    manifestFile = environmentService.updateValues(APP_ID, ENV_ID, null, manifestFile, AppManifestKind.VALUES);

    assertThat(manifestFile.getFileContent()).isEqualTo("updated" + FILE_CONTENT);
    assertThat(manifestFile.getFileName()).isEqualTo(VALUES_YAML_KEY);
    assertThat(manifestFile.getAppId()).isEqualTo(APP_ID);
    assertThat(manifestFile.getApplicationManifestId()).isEqualTo(applicationManifest.getUuid());
  }

  @Test(expected = InvalidRequestException.class)
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testUpdateValuesWithException() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).build();
    Application application = Application.Builder.anApplication().uuid(APP_ID).build();

    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    environmentService.updateValues(APP_ID, ENV_ID, null, ManifestFile.builder().build(), null);
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testCreateValuesWithEnvServiceOverride() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();
    Service service = Service.builder().name(SERVICE_NAME).appId(APP_ID).uuid(SERVICE_ID).build();

    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);
    realWingsPersistence.save(service);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);
    when(serviceResourceService.get(APP_ID, SERVICE_ID, false)).thenReturn(service);

    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();
    manifestFile = environmentService.createValues(APP_ID, ENV_ID, SERVICE_ID, manifestFile, AppManifestKind.VALUES);

    assertThat(manifestFile.getFileContent()).isEqualTo(FILE_CONTENT);
    assertThat(manifestFile.getFileName()).isEqualTo(VALUES_YAML_KEY);
    assertThat(manifestFile.getAppId()).isEqualTo(APP_ID);

    ApplicationManifest applicationManifest =
        realWingsPersistence.get(ApplicationManifest.class, manifestFile.getApplicationManifestId());
    assertThat(applicationManifest.getAppId()).isEqualTo(APP_ID);
    assertThat(applicationManifest.getServiceId()).isEqualTo(SERVICE_ID);
    assertThat(applicationManifest.getKind()).isEqualTo(AppManifestKind.VALUES);
    assertThat(applicationManifest.getEnvId()).isEqualTo(ENV_ID);
    assertThat(applicationManifest.getStoreType()).isEqualTo(Local);
    assertThat(applicationManifest.getGitFileConfig()).isNull();
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testCreateValuesWithExistingAppManifestForEnvServiceOverride() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Service service = Service.builder().name(SERVICE_NAME).appId(APP_ID).uuid(SERVICE_ID).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();
    ApplicationManifest applicationManifest = ApplicationManifest.builder()
                                                  .storeType(Local)
                                                  .kind(AppManifestKind.VALUES)
                                                  .envId(ENV_ID)
                                                  .serviceId(SERVICE_ID)
                                                  .build();
    applicationManifest.setAppId(APP_ID);

    realWingsPersistence.save(applicationManifest);
    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);
    realWingsPersistence.save(service);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);
    when(serviceResourceService.get(APP_ID, SERVICE_ID, false)).thenReturn(service);

    PageRequest<ServiceTemplate> pageRequest = new PageRequest<>();
    pageRequest.addFilter("appId", EQ, environment.getAppId());
    pageRequest.addFilter("envId", EQ, environment.getUuid());
    when(serviceTemplateService.list(pageRequest, false, OBTAIN_VALUE)).thenReturn(aPageResponse().build());

    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();
    manifestFile = environmentService.createValues(APP_ID, ENV_ID, SERVICE_ID, manifestFile, AppManifestKind.VALUES);

    assertThat(manifestFile.getFileContent()).isEqualTo(FILE_CONTENT);
    assertThat(manifestFile.getFileName()).isEqualTo(VALUES_YAML_KEY);
    assertThat(manifestFile.getAppId()).isEqualTo(APP_ID);
    assertThat(manifestFile.getApplicationManifestId()).isEqualTo(applicationManifest.getUuid());
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testUpdateValuesWithEnvServiceOverride() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Service service = Service.builder().name(SERVICE_NAME).appId(APP_ID).uuid(SERVICE_ID).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();
    ApplicationManifest applicationManifest = ApplicationManifest.builder()
                                                  .storeType(Local)
                                                  .kind(AppManifestKind.VALUES)
                                                  .envId(ENV_ID)
                                                  .serviceId(SERVICE_ID)
                                                  .build();
    applicationManifest.setAppId(APP_ID);
    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();

    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);
    realWingsPersistence.save(applicationManifest);
    realWingsPersistence.saveAndGet(ManifestFile.class, manifestFile);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);
    when(serviceResourceService.get(APP_ID, SERVICE_ID, false)).thenReturn(service);

    PageRequest<ServiceTemplate> pageRequest = new PageRequest<>();
    pageRequest.addFilter("appId", EQ, environment.getAppId());
    pageRequest.addFilter("envId", EQ, environment.getUuid());
    when(serviceTemplateService.list(pageRequest, false, OBTAIN_VALUE)).thenReturn(aPageResponse().build());

    manifestFile.setFileContent("updated" + FILE_CONTENT);
    manifestFile.setFileName("ABC");
    manifestFile = environmentService.updateValues(APP_ID, ENV_ID, SERVICE_ID, manifestFile, AppManifestKind.VALUES);

    assertThat(manifestFile.getFileContent()).isEqualTo("updated" + FILE_CONTENT);
    assertThat(manifestFile.getFileName()).isEqualTo(VALUES_YAML_KEY);
    assertThat(manifestFile.getAppId()).isEqualTo(APP_ID);
    assertThat(manifestFile.getApplicationManifestId()).isEqualTo(applicationManifest.getUuid());
  }

  @Test(expected = InvalidRequestException.class)
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testUpdateValuesWithEnvServiceOverrideException() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).build();
    Application application = Application.Builder.anApplication().uuid(APP_ID).build();
    Service service = Service.builder().name(SERVICE_NAME).appId(APP_ID).uuid(SERVICE_ID).build();

    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(serviceResourceService.get(APP_ID, SERVICE_ID, false)).thenReturn(service);
    environmentService.updateValues(APP_ID, ENV_ID, SERVICE_ID, ManifestFile.builder().build(), null);
  }

  @Test
  @Owner(developers = SRINIVAS)
  @Category(UnitTests.class)
  public void testCloneEnvironmentWithoutValidateInfraMappings() {
    PhysicalInfrastructureMapping physicalInfrastructureMapping = aPhysicalInfrastructureMapping()
                                                                      .withHostConnectionAttrs(HOST_CONN_ATTR_ID)
                                                                      .withComputeProviderSettingId(SETTING_ID)
                                                                      .withAppId(APP_ID)
                                                                      .withEnvId(ENV_ID)
                                                                      .withServiceTemplateId(TEMPLATE_ID)
                                                                      .build();

    cloneEnvironmentWithInfraMapping(false, physicalInfrastructureMapping);
    verify(infrastructureMappingService, times(1)).save(physicalInfrastructureMapping, true);
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testCreateEnvPcfOverrides() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();

    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);

    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();
    manifestFile = environmentService.createValues(APP_ID, ENV_ID, null, manifestFile, AppManifestKind.PCF_OVERRIDE);

    assertThat(manifestFile.getFileContent()).isEqualTo(FILE_CONTENT);
    assertThat(manifestFile.getFileName()).isEqualTo(VARS_YML);
    assertThat(manifestFile.getAppId()).isEqualTo(APP_ID);

    ApplicationManifest applicationManifest =
        realWingsPersistence.get(ApplicationManifest.class, manifestFile.getApplicationManifestId());
    assertThat(applicationManifest.getAppId()).isEqualTo(APP_ID);
    assertThat(applicationManifest.getKind()).isEqualTo(AppManifestKind.PCF_OVERRIDE);
    assertThat(applicationManifest.getEnvId()).isEqualTo(ENV_ID);
    assertThat(applicationManifest.getStoreType()).isEqualTo(Local);
    assertThat(applicationManifest.getServiceId()).isNull();
    assertThat(applicationManifest.getGitFileConfig()).isNull();
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testCreateEnvPcfOverridesWithException() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    Application application =
        Application.Builder.anApplication().name(APP_NAME).uuid(APP_ID).accountId(ACCOUNT_ID).build();

    realWingsPersistence.save(environment);
    realWingsPersistence.save(application);

    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(wingsPersistence.get(Application.class, APP_ID)).thenReturn(application);

    ManifestFile manifestFile = ManifestFile.builder().fileContent(FILE_CONTENT).build();
    try {
      environmentService.createValues(APP_ID, ENV_ID, null, manifestFile, AppManifestKind.K8S_MANIFEST);
      fail("Should not reach here.");
    } catch (InvalidRequestException e) {
      assertThat(e.getMessage()).isEqualTo("Unhandled application manifest kind K8S_MANIFEST");
    }
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testSetConfigMapYamlForService() {
    Environment environment = anEnvironment().uuid(ENV_ID).appId(APP_ID).name(ENV_NAME).build();
    when(environmentService.get(APP_ID, ENV_ID)).thenReturn(environment);
    when(appService.getAccountIdByAppId(APP_ID)).thenReturn(ACCOUNT_ID);

    when(persistentLocker.waitToAcquireLock(Environment.class, ENV_ID, ofSeconds(5), ofSeconds(10)))
        .thenReturn(acquiredLock);

    environmentService.setConfigMapYamlForService(
        APP_ID, ENV_ID, SERVICE_TEMPLATE_ID, KubernetesPayload.builder().advancedConfig("configMapYaml").build());

    Map<String, String> configMapYamls = new HashMap<>();
    configMapYamls.put(SERVICE_TEMPLATE_ID, "configMapYaml");
    verify(updateOperations, times(1)).set("configMapYamlByServiceTemplateId", configMapYamls);
    verify(yamlPushService, times(1))
        .pushYamlChangeSet(ACCOUNT_ID, environment, environment, Type.UPDATE, false, false);
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testSetConfigMapYamlForServiceForException() {
    when(persistentLocker.waitToAcquireLock(Environment.class, ENV_ID, ofSeconds(5), ofSeconds(10))).thenReturn(null);
    try {
      environmentService.setConfigMapYamlForService(
          APP_ID, ENV_ID, SERVICE_TEMPLATE_ID, KubernetesPayload.builder().advancedConfig("configMapYaml").build());
      fail("Should not reach here.");
    } catch (UnexpectedException e) {
      assertThat(e.getMessage()).isEqualTo("The persistent lock was not acquired");
    }
  }

  @Test
  @Owner(developers = ANSHUL)
  @Category(UnitTests.class)
  public void testGetOnNonExistentEnvironment() {
    try {
      environmentService.get(APP_ID, ENV_ID, false);
      fail("Should not reach here.");
    } catch (InvalidRequestException e) {
      assertThat(e.getMessage()).isEqualTo("Environment doesn't exist");
    }
  }
}
