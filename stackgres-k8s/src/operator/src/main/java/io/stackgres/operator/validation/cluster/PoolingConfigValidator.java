/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.validation.cluster;

import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.stackgres.operator.customresource.sgcluster.StackGresCluster;
import io.stackgres.operator.resource.KubernetesCustomResourceFinder;
import io.stackgres.operator.sidecars.pgbouncer.customresources.StackGresPgbouncerConfig;
import io.stackgres.operator.validation.StackgresClusterReview;
import io.stackgres.operatorframework.ValidationFailed;

@ApplicationScoped
public class PoolingConfigValidator implements ClusterValidator {

  private KubernetesCustomResourceFinder<StackGresPgbouncerConfig> configFinder;

  @Inject
  public PoolingConfigValidator(
      KubernetesCustomResourceFinder<StackGresPgbouncerConfig> configFinder) {
    this.configFinder = configFinder;
  }

  @Override
  public void validate(StackgresClusterReview review) throws ValidationFailed {

    StackGresCluster cluster = review.getRequest().getObject();
    String poolingConfig = cluster.getSpec().getConnectionPoolingConfig();

    switch (review.getRequest().getOperation()) {
      case CREATE:
        checkIfPoolingConfigExists(review, "Pooling config " + poolingConfig
            + " not found");
        break;
      case UPDATE:
        checkIfPoolingConfigExists(review, "Cannot update to pooling config "
            + poolingConfig + " because it doesn't exists");
        break;
      default:
    }

  }

  private void checkIfPoolingConfigExists(StackgresClusterReview review,
                                          String onError) throws ValidationFailed {

    StackGresCluster cluster = review.getRequest().getObject();
    String poolingConfig = cluster.getSpec().getConnectionPoolingConfig();
    String namespace = review.getRequest().getObject().getMetadata().getNamespace();

    Optional<StackGresPgbouncerConfig> poolingConfigOpt = configFinder
        .findByNameAndNamespace(poolingConfig, namespace);

    if (!poolingConfigOpt.isPresent()) {
      throw new ValidationFailed(onError);
    }
  }

}