/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.jobs.dbops.clusterrestart;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgdbops.StackGresDbOps;
import io.stackgres.common.crd.sgdbops.StackGresDbOpsRestartStatus;
import io.stackgres.common.crd.sgdbops.StackGresDbOpsStatus;
import io.stackgres.common.resource.CustomResourceFinder;
import io.stackgres.common.resource.CustomResourceScheduler;
import io.stackgres.jobs.dbops.ClusterRestartStateHandler;
import io.stackgres.jobs.dbops.DatabaseOperation;
import io.stackgres.jobs.dbops.DatabaseOperationJob;
import io.stackgres.jobs.dbops.StateHandler;

@ApplicationScoped
@DatabaseOperation("restart")
public class RestartJob implements DatabaseOperationJob {

  @Inject
  CustomResourceFinder<StackGresDbOps> dbOpsFinder;

  @Inject
  CustomResourceScheduler<StackGresDbOps> dbOpsScheduler;

  @Inject
  @StateHandler("restart")
  ClusterRestartStateHandler restartStateHandler;

  @Override
  public Uni<StackGresDbOps> runJob(StackGresDbOps dbOps, StackGresCluster cluster) {
    return restartStateHandler.restartCluster(dbOps)
        .onFailure().invoke(ex -> reportFailure(dbOps, ex));
  }

  private void reportFailure(StackGresDbOps dbOps, Throwable ex) {
    String message = ex.getMessage();
    String dbOpsName = dbOps.getMetadata().getName();
    String namespace = dbOps.getMetadata().getNamespace();

    dbOpsFinder.findByNameAndNamespace(dbOpsName, namespace)
        .ifPresent(savedDbOps -> {
          if (savedDbOps.getStatus() == null) {
            savedDbOps.setStatus(new StackGresDbOpsStatus());
          }

          if (savedDbOps.getStatus().getRestart() == null) {
            savedDbOps.getStatus().setRestart(new StackGresDbOpsRestartStatus());
          }

          savedDbOps.getStatus().getRestart().setFailure(message);

          dbOpsScheduler.update(savedDbOps);
        });
  }

}
