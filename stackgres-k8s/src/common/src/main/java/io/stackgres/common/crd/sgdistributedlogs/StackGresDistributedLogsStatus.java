/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.crd.sgdistributedlogs;

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
import io.stackgres.common.crd.sgcluster.StackGresClusterInstalledExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterPodStatus;

@JsonDeserialize
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@RegisterForReflection
public class StackGresDistributedLogsStatus implements KubernetesResource {

  private static final long serialVersionUID = -1L;

  @JsonProperty("conditions")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @Valid
  private List<StackGresDistributedLogsCondition> conditions = new ArrayList<>();

  @JsonProperty("postgresExtensions")
  @Valid
  private List<StackGresClusterInstalledExtension> postgresExtensions;

  @JsonProperty("podStatuses")
  @Valid
  private List<StackGresClusterPodStatus> podStatuses;

  @JsonProperty("databases")
  @Valid
  private List<StackGresDistributedLogsStatusDatabase> databases = new ArrayList<>();

  @JsonProperty("connectedClusters")
  @Valid
  private List<StackGresDistributedLogsStatusCluster> connectedClusters = new ArrayList<>();

  @JsonProperty("fluentdConfigHash")
  private String fluentdConfigHash;

  public List<StackGresDistributedLogsCondition> getConditions() {
    return conditions;
  }

  public void setConditions(List<StackGresDistributedLogsCondition> conditions) {
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

  public List<StackGresDistributedLogsStatusDatabase> getDatabases() {
    return databases;
  }

  public void setDatabases(List<StackGresDistributedLogsStatusDatabase> databases) {
    this.databases = databases;
  }

  public List<StackGresDistributedLogsStatusCluster> getConnectedClusters() {
    return connectedClusters;
  }

  public void setConnectedClusters(List<StackGresDistributedLogsStatusCluster> connectedClusters) {
    this.connectedClusters = connectedClusters;
  }

  public String getFluentdConfigHash() {
    return fluentdConfigHash;
  }

  public void setFluentdConfigHash(String fluentdConfigHash) {
    this.fluentdConfigHash = fluentdConfigHash;
  }

  @Override
  public int hashCode() {
    return Objects.hash(conditions, connectedClusters, databases, fluentdConfigHash, podStatuses,
        postgresExtensions);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StackGresDistributedLogsStatus)) {
      return false;
    }
    StackGresDistributedLogsStatus other = (StackGresDistributedLogsStatus) obj;
    return Objects.equals(conditions, other.conditions)
        && Objects.equals(connectedClusters, other.connectedClusters)
        && Objects.equals(databases, other.databases)
        && Objects.equals(fluentdConfigHash, other.fluentdConfigHash)
        && Objects.equals(podStatuses, other.podStatuses)
        && Objects.equals(postgresExtensions, other.postgresExtensions);
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
