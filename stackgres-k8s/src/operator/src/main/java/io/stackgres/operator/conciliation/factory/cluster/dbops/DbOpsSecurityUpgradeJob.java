/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.dbops;

import static io.stackgres.operator.conciliation.factory.cluster.dbops.DbOpsUtil.jobName;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.OperatorProperty;
import io.stackgres.common.StackGresProperty;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgdbops.StackGresDbOps;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.cluster.StackGresClusterContext;
import io.stackgres.operator.conciliation.cluster.StackGresVersion;
import io.stackgres.operator.conciliation.factory.ResourceFactory;

@Singleton
@OperatorVersionBinder(startAt = StackGresVersion.V09, stopAt = StackGresVersion.V10)
@OpJob("securityUpgrade")
public class DbOpsSecurityUpgradeJob implements JobFactory {

  public static final String IMAGE_NAME = "docker.io/stackgres/jobs:%s";

  private final LabelFactory<StackGresCluster> labelFactory;
  private final ResourceFactory<StackGresClusterContext, PodSecurityContext> podSecurityFactory;

  @Inject
  public DbOpsSecurityUpgradeJob(
      LabelFactory<StackGresCluster> labelFactory,
      ResourceFactory<StackGresClusterContext, PodSecurityContext> podSecurityFactory) {
    this.labelFactory = labelFactory;
    this.podSecurityFactory = podSecurityFactory;
  }

  @Override
  public Job createJob(StackGresClusterContext context, StackGresDbOps dbOps) {
    String namespace = dbOps.getMetadata().getNamespace();
    final Map<String, String> labels = labelFactory.dbOpsPodLabels(context.getSource());
    return new JobBuilder()
        .withNewMetadata()
        .withNamespace(namespace)
        .withName(jobName(dbOps, "security-upgrade"))
        .withLabels(labels)
        .endMetadata()
        .withNewSpec()
        .withBackoffLimit(0)
        .withCompletions(1)
        .withParallelism(1)
        .withNewTemplate()
        .withNewMetadata()
        .withNamespace(namespace)
        .withName(jobName(dbOps))
        .withLabels(labels)
        .endMetadata()
        .withNewSpec()
        .withSecurityContext(podSecurityFactory.createResource(context))
        .withRestartPolicy("Never")
        .withServiceAccountName(DbOpsRole.roleName(context))
        .withContainers(new ContainerBuilder()
            .withName("security-upgrade")
            .withImagePullPolicy("IfNotPresent")
            .withImage(String.format(IMAGE_NAME,
                StackGresProperty.OPERATOR_IMAGE_VERSION.getString()))
            .addToEnv(new EnvVarBuilder()
                    .withName(OperatorProperty.OPERATOR_NAME.getEnvironmentVariableName())
                    .withValue(OperatorProperty.OPERATOR_NAME.getString())
                    .build(),
                new EnvVarBuilder()
                    .withName(OperatorProperty.OPERATOR_NAMESPACE.getEnvironmentVariableName())
                    .withValue(OperatorProperty.OPERATOR_NAMESPACE.getString())
                    .build(),
                new EnvVarBuilder()
                    .withName("JOB_NAMESPACE")
                    .withValue(namespace)
                    .build(),
                new EnvVarBuilder()
                    .withName(StackGresProperty.OPERATOR_VERSION.getEnvironmentVariableName())
                    .withValue(StackGresProperty.OPERATOR_VERSION.getString())
                    .build(),
                new EnvVarBuilder()
                    .withName("CRD_UPGRADE")
                    .withValue(Boolean.FALSE.toString())
                    .build(),
                new EnvVarBuilder()
                    .withName("CONVERSION_WEBHOOKS")
                    .withValue(Boolean.FALSE.toString())
                    .build(),
                new EnvVarBuilder()
                    .withName("DATABASE_OPERATION_JOB")
                    .withValue(Boolean.TRUE.toString())
                    .build(),
                new EnvVarBuilder()
                    .withName("DATABASE_OPERATION_CR_NAME")
                    .withValue(dbOps.getMetadata().getName())
                    .build(),
                new EnvVarBuilder()
                    .withName("POD_NAME")
                    .withNewValueFrom()
                    .withNewFieldRef()
                    .withFieldPath("metadata.name")
                    .endFieldRef()
                    .endValueFrom()
                    .build())
            .build())
        .endSpec()
        .endTemplate()
        .endSpec()
        .build();
  }
}
