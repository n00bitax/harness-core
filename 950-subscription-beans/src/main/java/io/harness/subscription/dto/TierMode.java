/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.subscription.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public enum TierMode {
  @JsonProperty("volume") VOLUME,
  @JsonProperty("graduated") GRADUATED;

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static TierMode fromString(String mode) {
    for (TierMode modeEnum : TierMode.values()) {
      if (modeEnum.name().equalsIgnoreCase(mode)) {
        return modeEnum;
      }
    }
    throw new IllegalArgumentException("Invalid value: " + mode);
  }
}