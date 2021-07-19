/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.mutation.cluster;

import java.util.Collections;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import com.github.fge.jackson.jsonpointer.JsonPointer;
import com.github.fge.jsonpatch.JsonPatchOperation;
import io.fabric8.kubernetes.client.CustomResource;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.resource.CustomResourceFinder;
import io.stackgres.common.resource.CustomResourceScheduler;
import io.stackgres.operator.common.StackGresClusterReview;
import io.stackgres.operator.initialization.DefaultCustomResourceFactory;
import io.stackgres.operatorframework.admissionwebhook.Operation;

public abstract class AbstractDefaultResourceMutator<T extends CustomResource<?, ?>>
    implements ClusterMutator {

  @Inject
  DefaultCustomResourceFactory<T> resourceFactory;

  @Inject
  CustomResourceFinder<T> finder;

  @Inject
  CustomResourceScheduler<T> scheduler;

  private transient JsonPointer targetPointer;

  @PostConstruct
  public void init() throws NoSuchFieldException {
    targetPointer = getTargetPointer();
  }

  @Override
  public List<JsonPatchOperation> mutate(StackGresClusterReview review) {
    if (review.getRequest().getOperation() == Operation.CREATE) {
      T defaultResource = resourceFactory.buildResource();

      StackGresCluster targetCluster = review.getRequest().getObject();
      String targetNamespace = targetCluster.getMetadata().getNamespace();

      String defaultResourceName = defaultResource.getMetadata().getName();

      if (applyDefault(targetCluster)) {

        if (!finder.findByNameAndNamespace(defaultResourceName, targetNamespace).isPresent()) {
          defaultResource.getMetadata().setNamespace(targetNamespace);
          scheduler.create(defaultResource);
        }

        return Collections.singletonList(
            buildAddOperation(targetPointer, defaultResourceName));
      }

    }
    return Collections.emptyList();
  }

  protected boolean applyDefault(StackGresCluster targetCluster) {
    return isTargetPropertyEmpty(targetCluster);
  }

  protected boolean isTargetPropertyEmpty(StackGresCluster targetCluster) {
    return isEmpty(getTargetPropertyValue(targetCluster));
  }

  protected abstract String getTargetPropertyValue(StackGresCluster targetCluster);

  protected abstract JsonPointer getTargetPointer() throws NoSuchFieldException;

  public void setResourceFactory(DefaultCustomResourceFactory<T> resourceFactory) {
    this.resourceFactory = resourceFactory;
  }

  public void setFinder(CustomResourceFinder<T> finder) {
    this.finder = finder;
  }

  public void setScheduler(CustomResourceScheduler<T> scheduler) {
    this.scheduler = scheduler;
  }
}
