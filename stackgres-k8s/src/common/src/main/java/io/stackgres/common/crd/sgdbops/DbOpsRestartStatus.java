/*
 * Copyright (C) 2021 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.crd.sgdbops;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

@JsonDeserialize
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@RegisterForReflection
public class DbOpsRestartStatus {

  @JsonProperty("primaryInstance")
  private String primaryInstance;

  @JsonProperty("initialInstances")
  private List<String> initialInstances;

  @JsonProperty("pendingToRestartInstances")
  private List<String> pendingToRestartInstances;

  @JsonProperty("restartedInstances")
  private List<String> restartedInstances;

  @JsonProperty("switchoverInitiated")
  private String switchoverInitiated;

  @JsonProperty("failure")
  private String failure;

  public String getPrimaryInstance() {
    return primaryInstance;
  }

  public void setPrimaryInstance(String primaryInstance) {
    this.primaryInstance = primaryInstance;
  }

  public List<String> getInitialInstances() {
    return initialInstances;
  }

  public void setInitialInstances(List<String> initialInstances) {
    this.initialInstances = initialInstances;
  }

  public List<String> getPendingToRestartInstances() {
    return pendingToRestartInstances;
  }

  public void setPendingToRestartInstances(List<String> pendingToRestartInstances) {
    this.pendingToRestartInstances = pendingToRestartInstances;
  }

  public List<String> getRestartedInstances() {
    return restartedInstances;
  }

  public void setRestartedInstances(List<String> restartedInstances) {
    this.restartedInstances = restartedInstances;
  }

  public String getSwitchoverInitiated() {
    return switchoverInitiated;
  }

  public void setSwitchoverInitiated(String switchoverInitiated) {
    this.switchoverInitiated = switchoverInitiated;
  }

  public String getFailure() {
    return failure;
  }

  public void setFailure(String failure) {
    this.failure = failure;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DbOpsRestartStatus that = (DbOpsRestartStatus) o;
    return Objects.equals(primaryInstance, that.primaryInstance)
        && Objects.equals(initialInstances, that.initialInstances)
        && Objects.equals(pendingToRestartInstances, that.pendingToRestartInstances)
        && Objects.equals(restartedInstances, that.restartedInstances)
        && Objects.equals(switchoverInitiated, that.switchoverInitiated)
        && Objects.equals(failure, that.failure);
  }

  @Override
  public int hashCode() {
    return Objects.hash(primaryInstance, initialInstances, pendingToRestartInstances,
        restartedInstances, switchoverInitiated, failure);
  }
}