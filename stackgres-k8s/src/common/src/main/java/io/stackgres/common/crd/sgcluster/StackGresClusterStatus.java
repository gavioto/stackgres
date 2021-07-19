/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.crd.sgcluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;

@JsonDeserialize
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@RegisterForReflection
public class StackGresClusterStatus implements KubernetesResource {

  private static final long serialVersionUID = 4714141925270158016L;

  @JsonProperty("conditions")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @Valid
  private List<StackGresClusterCondition> conditions = new ArrayList<>();

  @JsonProperty("postgresExtensions")
  @Valid
  private List<StackGresClusterInstalledExtension> postgresExtensions;

  @JsonProperty("podStatuses")
  @Valid
  private List<StackGresClusterPodStatus> podStatuses;

  @JsonProperty("dbOps")
  @Valid
  private StackGresClusterDbOpsStatus dbOps;

  public List<StackGresClusterCondition> getConditions() {
    return conditions;
  }

  public void setConditions(List<StackGresClusterCondition> conditions) {
    this.conditions = conditions;
  }

  public List<StackGresClusterInstalledExtension> getPostgresExtensions() {
    return postgresExtensions;
  }

  public void setPostgresExtensions(
      List<StackGresClusterInstalledExtension> postgresExtensions) {
    this.postgresExtensions = postgresExtensions;
  }

  public List<StackGresClusterPodStatus> getPodStatuses() {
    return podStatuses;
  }

  public void setPodStatuses(List<StackGresClusterPodStatus> podStatuses) {
    this.podStatuses = podStatuses;
  }

  public StackGresClusterDbOpsStatus getDbOps() {
    return dbOps;
  }

  public void setDbOps(StackGresClusterDbOpsStatus dbOps) {
    this.dbOps = dbOps;
  }

  @Override
  public int hashCode() {
    return Objects.hash(conditions, dbOps, podStatuses, postgresExtensions);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StackGresClusterStatus)) {
      return false;
    }
    StackGresClusterStatus other = (StackGresClusterStatus) obj;
    return Objects.equals(conditions, other.conditions) && Objects.equals(dbOps, other.dbOps)
        && Objects.equals(podStatuses, other.podStatuses)
        && Objects.equals(postgresExtensions, other.postgresExtensions);
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
