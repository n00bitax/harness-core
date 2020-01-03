package software.wings.service.impl.marketplace.gcp;

import static io.harness.rule.OwnerRule.HITESH;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.Inject;

import io.harness.category.element.UnitTests;
import io.harness.rule.Owner;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import software.wings.WingsBaseTest;
import software.wings.beans.marketplace.gcp.GCPUsageReport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class GCPUsageReportServiceTest extends WingsBaseTest {
  private static final String TEST_ACCOUNT_ID = "TEST_ACCOUNT_ID";
  private static final String TEST_CONSUMER_ID = "TEST_CONSUMER_ID";
  private static final String TEST_OPERATION_ID = "TEST_OPERATION_ID";
  private static final String TEST_ENTITLEMENT_NAME = "TEST_ENTITLEMENT_NAME";

  @Inject @InjectMocks @Spy private GCPUsageReportServiceImpl gcpUsageReportService;

  @Before
  public void setUp() throws Exception {}

  @Test
  @Owner(developers = HITESH)
  @Category(UnitTests.class)
  public void createGCPUsageReport() {
    Instant startInstance = Instant.now().truncatedTo(ChronoUnit.HOURS);
    Instant endInstance = startInstance.plusSeconds(600);
    GCPUsageReport gcpUsageReport = new GCPUsageReport(
        TEST_ACCOUNT_ID, TEST_CONSUMER_ID, TEST_OPERATION_ID, TEST_ENTITLEMENT_NAME, startInstance, endInstance, 5);
    gcpUsageReportService.create(gcpUsageReport);
    Instant lastGCPUsageReportTime = gcpUsageReportService.fetchLastGCPUsageReportTime(TEST_ACCOUNT_ID);

    assertThat(lastGCPUsageReportTime).isNotNull();
    assertThat(lastGCPUsageReportTime).isEqualTo(endInstance);

    GCPUsageReport gcpUsageReportNew = new GCPUsageReport(
        TEST_ACCOUNT_ID, TEST_CONSUMER_ID, TEST_OPERATION_ID, TEST_ENTITLEMENT_NAME, startInstance, endInstance, 5);
    gcpUsageReportService.create(gcpUsageReportNew);
    Instant lastGCPUsageReportTimeLatest = gcpUsageReportService.fetchLastGCPUsageReportTime(TEST_ACCOUNT_ID);

    assertThat(lastGCPUsageReportTimeLatest).isNotNull();
    assertThat(lastGCPUsageReportTimeLatest).isEqualTo(endInstance);
  }
}
