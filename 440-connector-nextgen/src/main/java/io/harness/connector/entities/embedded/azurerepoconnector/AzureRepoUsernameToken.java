/*
 * Copyright 2020 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.connector.entities.embedded.azurerepoconnector;

import lombok.Builder;
import lombok.Value;
import org.hibernate.validator.constraints.NotEmpty;
import org.springframework.data.annotation.TypeAlias;

@Value
@Builder
@TypeAlias("io.harness.connector.entities.embedded.azurerepoconnector.AzureRepoUsernameToken")
public class AzureRepoUsernameToken implements AzureRepoHttpAuth {
  String username;
  String usernameRef;
  @NotEmpty String tokenRef;
}