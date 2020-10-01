package io.harness.batch.processing.billing.writer.support;

import static io.harness.rule.OwnerRule.HITESH;
import static org.assertj.core.api.Assertions.assertThat;

import io.harness.CategoryTest;
import io.harness.category.element.UnitTests;
import io.harness.rule.Owner;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ClusterDataGenerationValidatorTest extends CategoryTest {
  @InjectMocks private ClusterDataGenerationValidator clusterDataGenerationValidator;

  @Test
  @Owner(developers = HITESH)
  @Category(UnitTests.class)
  public void shouldReturnFalseForCluster() {
    boolean shouldGenerateClusterData =
        clusterDataGenerationValidator.shouldGenerateClusterData("hW63Ny6rQaaGsKkVjE0pJA", "5ee1584f2aa4186d1c1852de");
    assertThat(shouldGenerateClusterData).isFalse();
  }

  @Test
  @Owner(developers = HITESH)
  @Category(UnitTests.class)
  public void shouldReturnTrueForCluster() {
    boolean shouldGenerateClusterData =
        clusterDataGenerationValidator.shouldGenerateClusterData("hW63Ny6rQaaGsKkVjE0pJA", "5ee154f2aa4186d1c1852de");
    boolean shouldGenerateClusterDataRes =
        clusterDataGenerationValidator.shouldGenerateClusterData("h63Ny6rQaaGsKkVjE0pJA", "5ee1584f2aa4186d1c1852d");
    assertThat(shouldGenerateClusterData).isTrue();
    assertThat(shouldGenerateClusterDataRes).isTrue();
  }
}
