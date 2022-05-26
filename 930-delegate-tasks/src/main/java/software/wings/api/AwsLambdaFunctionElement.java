/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.api;

import static io.harness.annotations.dev.HarnessModule._957_CG_BEANS;
import static io.harness.annotations.dev.HarnessTeam.CDP;

import io.harness.annotations.dev.OwnedBy;
import io.harness.annotations.dev.TargetModule;
import io.harness.context.ContextElementType;

import software.wings.api.AwsLambdaContextElement.FunctionMeta;
import software.wings.beans.AwsConfig;
import software.wings.sm.ContextElement;

import lombok.Builder;
import lombok.Value;

/**
 * The type Aws lambda context element.
 */
@Value
@Builder
@OwnedBy(CDP)
@TargetModule(_957_CG_BEANS)
public class AwsLambdaFunctionElement implements ContextElement {
  private String uuid;
  private String name;

  private AwsConfig awsConfig;
  private String region;
  private FunctionMeta functionArn;

  @Override
  public ContextElementType getElementType() {
    return ContextElementType.AWS_LAMBDA_FUNCTION;
  }

  @Override
  public ContextElement cloneMin() {
    return this;
  }
}