/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.sm.states.azure;

import static io.harness.annotations.dev.HarnessTeam.CDC;

import io.harness.annotations.dev.OwnedBy;

import software.wings.api.ContextElementParamMapper;
import software.wings.sm.ExecutionContext;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;

@OwnedBy(CDC)
public class AzureVMSSSetupContextElementParamMapper implements ContextElementParamMapper {
  private final AzureVMSSSetupContextElement element;

  public AzureVMSSSetupContextElementParamMapper(AzureVMSSSetupContextElement element) {
    this.element = element;
  }

  @Override
  public Map<String, Object> paramMap(ExecutionContext context) {
    Map<String, Object> map = new HashMap<>();
    map.put("newVMSSName", this.element.getNewVirtualMachineScaleSetName());
    map.put("oldVMSSName", this.element.getOldVirtualMachineScaleSetName());

    return ImmutableMap.of("azurevmss", map);
  }
}