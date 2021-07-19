/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.mutation.cluster;

import java.util.List;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.fge.jackson.jsonpointer.JsonPointer;
import com.github.fge.jsonpatch.AddOperation;
import com.github.fge.jsonpatch.JsonPatchOperation;
import com.github.fge.jsonpatch.ReplaceOperation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterInstalledExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterSpec;
import io.stackgres.common.crd.sgcluster.StackGresClusterStatus;
import io.stackgres.operator.common.StackGresClusterReview;
import io.stackgres.operator.mutation.AbstractExtensionsMutator;
import io.stackgres.operator.mutation.ClusterExtensionMetadataManager;

@ApplicationScoped
public class ExtensionsMutator
    extends AbstractExtensionsMutator<StackGresCluster, StackGresClusterReview>
    implements ClusterMutator {

  private final ClusterExtensionMetadataManager extensionMetadataManager;
  private final ObjectMapper objectMapper;

  @Inject
  public ExtensionsMutator(ClusterExtensionMetadataManager extensionMetadataManager,
      ObjectMapper objectMapper) {
    super();
    this.extensionMetadataManager = extensionMetadataManager;
    this.objectMapper = objectMapper;
  }

  @Override
  protected ClusterExtensionMetadataManager getExtensionMetadataManager() {
    return extensionMetadataManager;
  }

  @Override
  protected ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  @Override
  protected Optional<List<StackGresClusterInstalledExtension>> getToInstallExtensions(
      StackGresCluster cluster) {
    return Optional.of(cluster)
        .map(StackGresCluster::getStatus)
        .map(StackGresClusterStatus::getPostgresExtensions);
  }

  @Override
  protected StackGresCluster getCluster(StackGresCluster cluster) {
    return cluster;
  }

  @Override
  protected List<StackGresClusterExtension> getExtensions(StackGresCluster cluster) {
    return Optional.of(cluster)
        .map(StackGresCluster::getSpec)
        .map(StackGresClusterSpec::getPostgresExtensions)
        .orElse(ImmutableList.of());
  }

  @Override
  protected ImmutableList<StackGresClusterExtension> getDefaultExtensions(
      StackGresCluster cluster) {
    return ImmutableList.of(
        getExtension(cluster, "plpgsql"),
        getExtension(cluster, "pg_stat_statements"),
        getExtension(cluster, "dblink"),
        getExtension(cluster, "plpython3u"));
  }

  @Override
  protected void onExtension(Builder<JsonPatchOperation> operations,
      StackGresClusterExtension extension, int index,
      StackGresClusterInstalledExtension installedExtension) {
    final JsonPointer extensionVersionPointer =
        CLUSTER_CONFIG_POINTER.append("postgresExtensions")
        .append(index).append("version");
    final TextNode extensionVersion = new TextNode(installedExtension.getVersion());
    if (extension.getVersion() == null) {
      operations.add(new AddOperation(extensionVersionPointer, extensionVersion));
    } else if (!installedExtension.getVersion().equals(extension.getVersion())) {
      operations.add(new ReplaceOperation(extensionVersionPointer, extensionVersion));
    }
  }

}
