package software.wings;

import static org.mockito.Mockito.mock;

import com.google.inject.AbstractModule;

import software.wings.delegatetasks.DelegateFileManager;
import software.wings.helpers.ext.amazons3.AmazonS3Service;
import software.wings.helpers.ext.amazons3.AmazonS3ServiceImpl;
import software.wings.helpers.ext.artifactory.ArtifactoryService;
import software.wings.helpers.ext.artifactory.ArtifactoryServiceImpl;
import software.wings.service.impl.AmazonS3BuildServiceImpl;
import software.wings.service.impl.ArtifactoryBuildServiceImpl;
import software.wings.service.impl.EcrBuildServiceImpl;
import software.wings.service.impl.MaintenanceServiceImpl;
import software.wings.service.impl.appdynamics.AppdynamicsDelegateServiceImpl;
import software.wings.service.impl.elk.ElkDelegateServiceImpl;
import software.wings.service.impl.newrelic.NewRelicDelgateServiceImpl;
import software.wings.service.impl.security.EncryptionServiceImpl;
import software.wings.service.impl.security.SecretManagementDelegateServiceImpl;
import software.wings.service.impl.splunk.SplunkDelegateServiceImpl;
import software.wings.service.impl.sumo.SumoDelegateServiceImpl;
import software.wings.service.intfc.AmazonS3BuildService;
import software.wings.service.intfc.ArtifactoryBuildService;
import software.wings.service.intfc.EcrBuildService;
import software.wings.service.intfc.MaintenanceService;
import software.wings.service.intfc.appdynamics.AppdynamicsDelegateService;
import software.wings.service.intfc.elk.ElkDelegateService;
import software.wings.service.intfc.newrelic.NewRelicDelegateService;
import software.wings.service.intfc.security.EncryptionService;
import software.wings.service.intfc.security.SecretManagementDelegateService;
import software.wings.service.intfc.splunk.SplunkDelegateService;
import software.wings.service.intfc.sumo.SumoDelegateService;

public class WingsTestModule extends AbstractModule {
  @Override
  protected void configure() {
    DelegateFileManager mockDelegateFileManager = mock(DelegateFileManager.class);
    bind(DelegateFileManager.class).toInstance(mockDelegateFileManager);
    bind(AmazonS3BuildService.class).to(AmazonS3BuildServiceImpl.class);
    bind(AmazonS3Service.class).to(AmazonS3ServiceImpl.class);
    bind(NewRelicDelegateService.class).to(NewRelicDelgateServiceImpl.class);
    bind(AppdynamicsDelegateService.class).to(AppdynamicsDelegateServiceImpl.class);
    bind(EncryptionService.class).to(EncryptionServiceImpl.class);
    bind(SecretManagementDelegateService.class).to(SecretManagementDelegateServiceImpl.class);
    bind(ElkDelegateService.class).to(ElkDelegateServiceImpl.class);
    bind(SplunkDelegateService.class).to(SplunkDelegateServiceImpl.class);
    bind(SumoDelegateService.class).to(SumoDelegateServiceImpl.class);
    bind(ArtifactoryBuildService.class).to(ArtifactoryBuildServiceImpl.class);
    bind(ArtifactoryService.class).to(ArtifactoryServiceImpl.class);
    bind(EcrBuildService.class).to(EcrBuildServiceImpl.class);
  }
}
