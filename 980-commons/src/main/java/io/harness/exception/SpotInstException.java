/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.exception;

import static io.harness.annotations.dev.HarnessTeam.CDP;
import static io.harness.eraro.ErrorCode.SPOTINST_NULL_ERROR;

import io.harness.annotations.dev.OwnedBy;
import io.harness.eraro.Level;

@OwnedBy(CDP)
public class SpotInstException extends WingsException {
  private static final String MESSAGE_ARG = "message";

  public SpotInstException(String message) {
    super(message, null, SPOTINST_NULL_ERROR, Level.ERROR, null, null);
    super.param(MESSAGE_ARG, message);
  }
}