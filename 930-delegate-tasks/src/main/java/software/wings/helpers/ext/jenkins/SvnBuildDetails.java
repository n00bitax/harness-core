/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.helpers.ext.jenkins;

import static io.harness.annotations.dev.HarnessModule._960_API_SERVICES;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.annotations.dev.TargetModule;

import com.offbytwo.jenkins.model.BaseModel;

/**
 * Created by peeyushaggarwal on 5/12/16.
 */
@OwnedBy(HarnessTeam.CDC)
@TargetModule(_960_API_SERVICES)
public class SvnBuildDetails extends BaseModel {
  private SvnChangeSet changeSet;

  /**
   * Gets change set.
   *
   * @return the change set
   */
  public SvnChangeSet getChangeSet() {
    return changeSet;
  }

  /**
   * Sets change set.
   *
   * @param changeSet the change set
   */
  public void setChangeSet(SvnChangeSet changeSet) {
    this.changeSet = changeSet;
  }
}