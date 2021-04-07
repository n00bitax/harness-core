package io.harness.cdng.manifest.mappers;

import static io.harness.annotations.dev.HarnessTeam.CDP;
import static io.harness.cdng.manifest.ManifestType.HelmChart;
import static io.harness.cdng.manifest.ManifestType.K8Manifest;
import static io.harness.cdng.manifest.ManifestType.Kustomize;
import static io.harness.cdng.manifest.ManifestType.OpenshiftParam;
import static io.harness.cdng.manifest.ManifestType.OpenshiftTemplate;
import static io.harness.cdng.manifest.ManifestType.VALUES;
import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.ngpipeline.common.ParameterFieldHelper.getParameterFieldValue;

import static java.lang.String.format;

import io.harness.annotations.dev.OwnedBy;
import io.harness.cdng.manifest.ManifestStoreType;
import io.harness.cdng.manifest.yaml.GcsStoreConfig;
import io.harness.cdng.manifest.yaml.GitStoreConfig;
import io.harness.cdng.manifest.yaml.HelmChartManifestOutcome;
import io.harness.cdng.manifest.yaml.K8sManifestOutcome;
import io.harness.cdng.manifest.yaml.KustomizeManifestOutcome;
import io.harness.cdng.manifest.yaml.ManifestAttributes;
import io.harness.cdng.manifest.yaml.ManifestOutcome;
import io.harness.cdng.manifest.yaml.OpenshiftManifestOutcome;
import io.harness.cdng.manifest.yaml.OpenshiftParamManifestOutcome;
import io.harness.cdng.manifest.yaml.S3StoreConfig;
import io.harness.cdng.manifest.yaml.StoreConfig;
import io.harness.cdng.manifest.yaml.ValuesManifestOutcome;
import io.harness.cdng.manifest.yaml.kinds.HelmChartManifest;
import io.harness.cdng.manifest.yaml.kinds.K8sManifest;
import io.harness.cdng.manifest.yaml.kinds.KustomizeManifest;
import io.harness.cdng.manifest.yaml.kinds.OpenshiftManifest;
import io.harness.cdng.manifest.yaml.kinds.OpenshiftParamManifest;
import io.harness.cdng.manifest.yaml.kinds.ValuesManifest;
import io.harness.delegate.beans.storeconfig.FetchType;
import io.harness.exception.InvalidArgumentsException;
import io.harness.pms.yaml.ParameterField;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.tuple.Pair;

@UtilityClass
@OwnedBy(CDP)
public class ManifestOutcomeMapper {
  public List<ManifestOutcome> toManifestOutcome(List<ManifestAttributes> manifestAttributesList) {
    return manifestAttributesList.stream()
        .map(ManifestOutcomeMapper::toManifestOutcome)
        .collect(Collectors.toCollection(LinkedList::new));
  }

  public ManifestOutcome toManifestOutcome(ManifestAttributes manifestAttributes) {
    switch (manifestAttributes.getKind()) {
      case K8Manifest:
        return getK8sOutcome(manifestAttributes);
      case VALUES:
        return getValuesOutcome(manifestAttributes);
      case HelmChart:
        return getHelmChartOutcome(manifestAttributes);
      case Kustomize:
        return getKustomizeOutcome(manifestAttributes);
      case OpenshiftTemplate:
        return getOpenshiftOutcome(manifestAttributes);
      case OpenshiftParam:
        return getOpenshiftParamOutcome(manifestAttributes);
      default:
        throw new UnsupportedOperationException(
            format("Unknown Artifact Config type: [%s]", manifestAttributes.getKind()));
    }
  }

  private K8sManifestOutcome getK8sOutcome(ManifestAttributes manifestAttributes) {
    K8sManifest k8sManifest = (K8sManifest) manifestAttributes;
    boolean skipResourceVersioning = !ParameterField.isNull(k8sManifest.getSkipResourceVersioning())
        && k8sManifest.getSkipResourceVersioning().getValue();
    validateManifestStoreConfig(k8sManifest.getStoreConfig(), k8sManifest.getIdentifier());

    return K8sManifestOutcome.builder()
        .identifier(k8sManifest.getIdentifier())
        .store(k8sManifest.getStoreConfig())
        .skipResourceVersioning(skipResourceVersioning)
        .build();
  }

  private ValuesManifestOutcome getValuesOutcome(ManifestAttributes manifestAttributes) {
    ValuesManifest attributes = (ValuesManifest) manifestAttributes;
    validateManifestStoreConfig(attributes.getStoreConfig(), attributes.getIdentifier());
    return ValuesManifestOutcome.builder()
        .identifier(attributes.getIdentifier())
        .store(attributes.getStoreConfig())
        .build();
  }

  private HelmChartManifestOutcome getHelmChartOutcome(ManifestAttributes manifestAttributes) {
    HelmChartManifest helmChartManifest = (HelmChartManifest) manifestAttributes;
    boolean skipResourceVersioning = !ParameterField.isNull(helmChartManifest.getSkipResourceVersioning())
        && helmChartManifest.getSkipResourceVersioning().getValue();
    String manifestStoreKind = helmChartManifest.getStoreConfig().getKind();
    String chartName = null;
    String chartVersion = null;

    if (!ManifestStoreType.isInGitSubset(manifestStoreKind)) {
      if (ParameterField.isNull(helmChartManifest.getChartName())) {
        throw new InvalidArgumentsException(
            Pair.of("chartName", format("required for %s store type", manifestStoreKind)));
      }

      chartName = helmChartManifest.getChartName().getValue();
    } else {
      if (!ParameterField.isNull(helmChartManifest.getChartName())) {
        throw new InvalidArgumentsException(
            Pair.of("chartName", format("not allowed for %s store type", manifestStoreKind)));
      }
    }

    if (!ParameterField.isNull(helmChartManifest.getChartVersion())) {
      if (ManifestStoreType.isInGitSubset(manifestStoreKind)) {
        throw new InvalidArgumentsException(
            Pair.of("chartVersion", format("not allowed for %s store", manifestStoreKind)));
      }

      chartVersion = helmChartManifest.getChartVersion().getValue();
    }

    validateManifestStoreConfig(helmChartManifest.getStoreConfig(), helmChartManifest.getIdentifier());

    return HelmChartManifestOutcome.builder()
        .identifier(helmChartManifest.getIdentifier())
        .store(helmChartManifest.getStoreConfig())
        .chartName(chartName)
        .chartVersion(chartVersion)
        .helmVersion(helmChartManifest.getHelmVersion())
        .skipResourceVersioning(skipResourceVersioning)
        .commandFlags(helmChartManifest.getCommandFlags())
        .build();
  }

  private KustomizeManifestOutcome getKustomizeOutcome(ManifestAttributes manifestAttributes) {
    KustomizeManifest kustomizeManifest = (KustomizeManifest) manifestAttributes;
    boolean skipResourceVersioning = !ParameterField.isNull(kustomizeManifest.getSkipResourceVersioning())
        && kustomizeManifest.getSkipResourceVersioning().getValue();
    String pluginPath =
        !ParameterField.isNull(kustomizeManifest.getPluginPath()) ? kustomizeManifest.getPluginPath().getValue() : null;
    validateManifestStoreConfig(kustomizeManifest.getStoreConfig(), kustomizeManifest.getIdentifier());
    return KustomizeManifestOutcome.builder()
        .identifier(kustomizeManifest.getIdentifier())
        .store(kustomizeManifest.getStoreConfig())
        .skipResourceVersioning(skipResourceVersioning)
        .pluginPath(pluginPath)
        .build();
  }

  private OpenshiftManifestOutcome getOpenshiftOutcome(ManifestAttributes manifestAttributes) {
    OpenshiftManifest openshiftManifest = (OpenshiftManifest) manifestAttributes;
    boolean skipResourceVersioning = !ParameterField.isNull(openshiftManifest.getSkipResourceVersioning())
        && openshiftManifest.getSkipResourceVersioning().getValue();
    validateManifestStoreConfig(openshiftManifest.getStoreConfig(), openshiftManifest.getIdentifier());

    return OpenshiftManifestOutcome.builder()
        .identifier(openshiftManifest.getIdentifier())
        .store(openshiftManifest.getStoreConfig())
        .skipResourceVersioning(skipResourceVersioning)
        .build();
  }

  private OpenshiftParamManifestOutcome getOpenshiftParamOutcome(ManifestAttributes manifestAttributes) {
    OpenshiftParamManifest attributes = (OpenshiftParamManifest) manifestAttributes;
    validateManifestStoreConfig(attributes.getStoreConfig(), attributes.getIdentifier());

    return OpenshiftParamManifestOutcome.builder()
        .identifier(attributes.getIdentifier())
        .store(attributes.getStoreConfig())
        .build();
  }

  private void validateManifestStoreConfig(StoreConfig storeConfig, String manifestIdentifier) {
    if (ManifestStoreType.isInGitSubset(storeConfig.getKind())) {
      GitStoreConfig gitStoreConfig = (GitStoreConfig) storeConfig;

      if (ParameterField.isNull(gitStoreConfig.getConnectorRef())
          || isEmpty(getParameterFieldValue(gitStoreConfig.getConnectorRef()))) {
        throw new InvalidArgumentsException(
            format("Missing or empty connectorRef in %s store spec for manifest with identifier: %s",
                storeConfig.getKind(), manifestIdentifier));
      }

      if (FetchType.BRANCH == gitStoreConfig.getGitFetchType()) {
        if (isNotEmpty(getParameterFieldValue(gitStoreConfig.getCommitId()))) {
          throw new InvalidArgumentsException(Pair.of("commitId", "Not allowed for gitFetchType: Branch"));
        }

        if (ParameterField.isNull(gitStoreConfig.getBranch())
            || isEmpty(getParameterFieldValue(gitStoreConfig.getBranch()))) {
          throw new InvalidArgumentsException(Pair.of("branch", "Cannot be empty or null for gitFetchType: Branch"));
        }
      }

      if (FetchType.COMMIT == gitStoreConfig.getGitFetchType()) {
        if (isNotEmpty(getParameterFieldValue(gitStoreConfig.getBranch()))) {
          throw new InvalidArgumentsException(Pair.of("branch", "Not allowed for gitFetchType: Commit"));
        }

        if (ParameterField.isNull(gitStoreConfig.getCommitId())
            || isEmpty(getParameterFieldValue(gitStoreConfig.getCommitId()))) {
          throw new InvalidArgumentsException(Pair.of("commitId", "Cannot be empty or null for gitFetchType: Commit"));
        }
      }

      return;
    }

    if (ManifestStoreType.S3.equals(storeConfig.getKind())) {
      S3StoreConfig s3StoreConfig = (S3StoreConfig) storeConfig;

      if (ParameterField.isNull(s3StoreConfig.getConnectorRef())
          || isEmpty(getParameterFieldValue(s3StoreConfig.getConnectorRef()))) {
        throw new InvalidArgumentsException(format(
            "Missing or empty connectorRef in S3 store spec for manifest with identifier: %s", manifestIdentifier));
      }

      if (ParameterField.isNull(s3StoreConfig.getRegion())
          || isEmpty(getParameterFieldValue(s3StoreConfig.getRegion()))) {
        throw new InvalidArgumentsException(Pair.of("region", "Cannot be empty or null for S3 store"));
      }

      if (ParameterField.isNull(s3StoreConfig.getBucketName())
          || isEmpty(getParameterFieldValue(s3StoreConfig.getBucketName()))) {
        throw new InvalidArgumentsException(Pair.of("bucketName", "Cannot be empty or null for S3 store"));
      }

      return;
    }

    if (ManifestStoreType.GCS.equals(storeConfig.getKind())) {
      GcsStoreConfig gcsStoreConfig = (GcsStoreConfig) storeConfig;

      if (ParameterField.isNull(gcsStoreConfig.getConnectorRef())
          || isEmpty(getParameterFieldValue(gcsStoreConfig.getConnectorRef()))) {
        throw new InvalidArgumentsException(format(
            "Missing or empty connectorRef in Gcs store spec for manifest with identifier: %s", manifestIdentifier));
      }

      if (ParameterField.isNull(gcsStoreConfig.getBucketName())
          || isEmpty(getParameterFieldValue(gcsStoreConfig.getBucketName()))) {
        throw new InvalidArgumentsException(Pair.of("bucketName", "Cannot be empty or null for Gcs store"));
      }
    }
  }
}
