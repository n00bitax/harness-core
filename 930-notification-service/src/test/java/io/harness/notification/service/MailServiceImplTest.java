package io.harness.notification.service;

import static io.harness.rule.OwnerRule.ANKUSH;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import io.harness.CategoryTest;
import io.harness.NotificationRequest;
import io.harness.category.element.UnitTests;
import io.harness.delegate.beans.NotificationTaskResponse;
import io.harness.notification.SmtpConfig;
import io.harness.notification.beans.NotificationProcessingResponse;
import io.harness.notification.exception.NotificationException;
import io.harness.notification.remote.dto.EmailSettingDTO;
import io.harness.notification.remote.dto.NotificationSettingDTO;
import io.harness.notification.service.MailServiceImpl.EmailTemplate;
import io.harness.notification.service.api.NotificationSettingsService;
import io.harness.notification.service.api.NotificationTemplateService;
import io.harness.notification.service.senders.MailSenderImpl;
import io.harness.rule.Owner;
import io.harness.serializer.YamlUtils;
import io.harness.service.DelegateGrpcClientWrapper;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import lombok.SneakyThrows;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class MailServiceImplTest extends CategoryTest {
  @Mock private NotificationSettingsService notificationSettingsService;
  @Mock private NotificationTemplateService notificationTemplateService;
  @Mock private YamlUtils yamlUtils;
  @Mock private SmtpConfig smtpConfigDefault;
  @Mock private MailSenderImpl mailSender;
  @Mock private DelegateGrpcClientWrapper delegateGrpcClientWrapper;
  private MailServiceImpl mailService;
  private String accountId = "accountId";
  private String mailTemplateName = "email_test";
  private String emailAdress = "email@harness.io";
  private String id = "id";
  private EmailTemplate emailTemplate = new EmailTemplate();

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    mailService = new MailServiceImpl(notificationSettingsService, notificationTemplateService, yamlUtils,
        smtpConfigDefault, mailSender, delegateGrpcClientWrapper);
    emailTemplate.setBody("this is test mail");
    emailTemplate.setSubject("test notification");
  }

  @Test
  @Owner(developers = ANKUSH)
  @Category(UnitTests.class)
  public void sendNotificaiton_EmptyRequest() {
    NotificationRequest notificationRequest = NotificationRequest.newBuilder().build();
    NotificationProcessingResponse notificationProcessingResponse = mailService.send(notificationRequest);
    assertTrue(notificationProcessingResponse.equals(NotificationProcessingResponse.trivialResponseWithNoRetries));
  }

  @Test
  @Owner(developers = ANKUSH)
  @Category(UnitTests.class)
  public void sendNotificaiton_OnlyIdInRequest() {
    NotificationRequest notificationRequest = NotificationRequest.newBuilder().setId(id).build();
    NotificationProcessingResponse notificationProcessingResponse = mailService.send(notificationRequest);
    assertTrue(notificationProcessingResponse.equals(NotificationProcessingResponse.trivialResponseWithNoRetries));
  }

  @Test
  @Owner(developers = ANKUSH)
  @Category(UnitTests.class)
  public void sendNotificaiton_NoTemplateIdInRequest() {
    NotificationRequest notificationRequest =
        NotificationRequest.newBuilder()
            .setId(id)
            .setAccountId(accountId)
            .setEmail(
                NotificationRequest.Email.newBuilder().addAllEmailIds(Collections.singletonList(emailAdress)).build())
            .build();
    NotificationProcessingResponse notificationProcessingResponse = mailService.send(notificationRequest);
    assertTrue(notificationProcessingResponse.equals(NotificationProcessingResponse.trivialResponseWithNoRetries));
  }

  @Test
  @Owner(developers = ANKUSH)
  @Category(UnitTests.class)
  public void sendNotificaiton_NoRecipientInRequest() {
    NotificationRequest notificationRequest = NotificationRequest.newBuilder()
                                                  .setId(id)
                                                  .setAccountId(accountId)
                                                  .setEmail(NotificationRequest.Email.newBuilder()
                                                                .setTemplateId(mailTemplateName)
                                                                .addAllEmailIds(Collections.EMPTY_LIST)
                                                                .build())
                                                  .build();
    NotificationProcessingResponse notificationProcessingResponse = mailService.send(notificationRequest);
    assertTrue(notificationProcessingResponse.equals(NotificationProcessingResponse.trivialResponseWithNoRetries));
  }

  @SneakyThrows
  @Test
  @Owner(developers = ANKUSH)
  @Category(UnitTests.class)
  public void sendNotificaiton_ValidCase() {
    NotificationRequest notificationRequest = NotificationRequest.newBuilder()
                                                  .setId(id)
                                                  .setAccountId(accountId)
                                                  .setEmail(NotificationRequest.Email.newBuilder()
                                                                .setTemplateId(mailTemplateName)
                                                                .addAllEmailIds(Collections.singletonList(emailAdress))
                                                                .build())
                                                  .build();
    NotificationProcessingResponse notificationExpectedResponse =
        NotificationProcessingResponse.builder().result(Arrays.asList(true, false)).shouldRetry(false).build();
    when(notificationTemplateService.getTemplateAsString(eq(mailTemplateName), any()))
        .thenReturn(Optional.empty(), Optional.of("This is a test notification"));
    when(notificationSettingsService.getSendNotificationViaDelegate(eq(accountId))).thenReturn(false);
    when(notificationSettingsService.getSmtpConfig(eq(accountId))).thenReturn(Optional.of(smtpConfigDefault));
    when(mailSender.send(any(), any(), any(), any())).thenReturn(notificationExpectedResponse);
    when(yamlUtils.read(any(), (TypeReference<EmailTemplate>) any())).thenReturn(emailTemplate);

    NotificationProcessingResponse notificationProcessingResponse = mailService.send(notificationRequest);
    assertTrue(notificationProcessingResponse.equals(NotificationProcessingResponse.trivialResponseWithNoRetries));

    notificationProcessingResponse = mailService.send(notificationRequest);
    assertEquals(notificationExpectedResponse, notificationProcessingResponse);

    notificationRequest = NotificationRequest.newBuilder()
                              .setId(id)
                              .setAccountId(accountId)
                              .setEmail(NotificationRequest.Email.newBuilder()
                                            .setTemplateId(mailTemplateName)
                                            .addAllEmailIds(Collections.singletonList(emailAdress))
                                            .build())
                              .build();
    notificationExpectedResponse =
        NotificationProcessingResponse.builder().result(Arrays.asList(true, false)).shouldRetry(false).build();
    when(notificationTemplateService.getTemplateAsString(eq(mailTemplateName), any()))
        .thenReturn(Optional.of("this is test notification"));
    when(notificationSettingsService.getSendNotificationViaDelegate(eq(accountId))).thenReturn(true);
    when(delegateGrpcClientWrapper.executeSyncTask(any()))
        .thenReturn(NotificationTaskResponse.builder().processingResponse(notificationExpectedResponse).build());
    notificationProcessingResponse = mailService.send(notificationRequest);
    assertEquals(notificationExpectedResponse, notificationProcessingResponse);
  }

  @Test
  @Owner(developers = ANKUSH)
  @Category(UnitTests.class)
  public void sendTestNotification_CheckAllGaurds() {
    final NotificationSettingDTO notificationSettingDTO1 = EmailSettingDTO.builder().build();
    assertThatThrownBy(() -> mailService.sendTestNotification(notificationSettingDTO1))
        .isInstanceOf(NotificationException.class);

    final NotificationSettingDTO notificationSettingDTO2 = EmailSettingDTO.builder().accountId(accountId).build();
    assertThatThrownBy(() -> mailService.sendTestNotification(notificationSettingDTO2))
        .isInstanceOf(NotificationException.class);

    final NotificationSettingDTO notificationSettingDTO3 =
        EmailSettingDTO.builder().recipient("email@harness.io").build();
    assertThatThrownBy(() -> mailService.sendTestNotification(notificationSettingDTO3))
        .isInstanceOf(NotificationException.class);
  }

  @SneakyThrows
  @Test
  @Owner(developers = ANKUSH)
  @Category(UnitTests.class)
  public void sendTestNotificaiton_ValidRequest() {
    final NotificationSettingDTO notificationSettingDTO4 =
        EmailSettingDTO.builder().accountId(accountId).recipient("email@harness.io").build();
    NotificationProcessingResponse notificationExpectedResponse =
        NotificationProcessingResponse.builder().result(Arrays.asList(true)).shouldRetry(false).build();
    when(mailSender.send(any(), any(), any(), any())).thenReturn(notificationExpectedResponse);
    when(notificationTemplateService.getTemplateAsString(any(), any()))
        .thenReturn(Optional.of("This is a test notification"));
    when(yamlUtils.read(any(), (TypeReference<EmailTemplate>) any())).thenReturn(emailTemplate);
    boolean response = mailService.sendTestNotification(notificationSettingDTO4);
    assertTrue(response);
  }
}