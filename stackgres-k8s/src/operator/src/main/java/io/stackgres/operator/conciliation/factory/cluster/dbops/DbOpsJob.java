/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.dbops;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.CustomResource;
import io.stackgres.common.ClusterStatefulSetPath;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.StackGresComponent;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgdbops.DbOpsStatusCondition;
import io.stackgres.common.crd.sgdbops.StackGresDbOps;
import io.stackgres.common.resource.ResourceUtil;
import io.stackgres.operator.cluster.factory.ClusterStatefulSetEnvironmentVariables;
import io.stackgres.operator.conciliation.cluster.StackGresClusterContext;
import io.stackgres.operator.conciliation.factory.ResourceFactory;
import io.stackgres.operator.conciliation.factory.cluster.patroni.ClusterStatefulSetVolumeConfig;
import org.jooq.lambda.Seq;
import org.jooq.lambda.Unchecked;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;

public abstract class DbOpsJob implements JobFactory {

  private static final Pattern UPPERCASE_PATTERN = Pattern.compile("(\\p{javaUpperCase})");

  private final ResourceFactory<StackGresClusterContext, PodSecurityContext> podSecurityFactory;
  private final ClusterStatefulSetEnvironmentVariables clusterStatefulSetEnvironmentVariables;
  private final ImmutableMap<DbOpsStatusCondition, String> conditions;
  protected LabelFactory<StackGresCluster> labelFactory;

  @Inject
  public DbOpsJob(ResourceFactory<StackGresClusterContext, PodSecurityContext> podSecurityFactory,
                  ClusterStatefulSetEnvironmentVariables clusterStatefulSetEnvironmentVariables,
                  LabelFactory<StackGresCluster> labelFactory, JsonMapper jsonMapper) {
    this.podSecurityFactory = podSecurityFactory;
    this.clusterStatefulSetEnvironmentVariables = clusterStatefulSetEnvironmentVariables;
    this.labelFactory = labelFactory;
    this.conditions = Optional.ofNullable(jsonMapper)
        .map(om -> Seq.of(DbOpsStatusCondition.values())
            .map(c -> Tuple.tuple(c, c.getCondition()))
            .peek(t -> t.v2.setLastTransitionTime("$LAST_TRANSITION_TIME"))
            .map(t -> t.map2(Unchecked.function(jsonMapper::writeValueAsString)))
            .collect(ImmutableMap.toImmutableMap(Tuple2::v1, Tuple2::v2)))
        .orElse(ImmutableMap.of());
  }

  public String jobName(StackGresDbOps dbOps) {
    return DbOpsUtil.jobName(dbOps, getOperation(dbOps));
  }

  protected String getOperation(StackGresDbOps dbOps) {
    return dbOps.getSpec().getOp();
  }

  protected boolean isExclusiveOp() {
    return false;
  }

  protected List<EnvVar> getRunEnvVars(StackGresClusterContext context,
                                       StackGresDbOps dbOps) {
    return ImmutableList.of();
  }

  protected List<EnvVar> getSetResultEnvVars(StackGresClusterContext context,
                                             StackGresDbOps dbOps) {
    return ImmutableList.of();
  }

  protected String getRunImage(StackGresClusterContext context) {
    return StackGresComponent.PATRONI.findImageName(
        StackGresComponent.LATEST,
        ImmutableMap.of(StackGresComponent.POSTGRESQL,
            context.getSource().getSpec().getPostgresVersion()));
  }

  protected abstract ClusterStatefulSetPath getRunScript();

  protected String getSetResultImage() {
    return StackGresComponent.KUBECTL.findLatestImageName();
  }

  protected ClusterStatefulSetPath getSetResultScript() {
    return null;
  }

  @Override
  public Job createJob(StackGresClusterContext context, StackGresDbOps dbOps) {
    final String retries = String.valueOf(DbOpsUtil.getCurrentRetry(dbOps));
    List<EnvVar> runEnvVars = getRunEnvVars(context, dbOps);
    List<EnvVar> setResultEnvVars = getSetResultEnvVars(context, dbOps);
    final String namespace = dbOps.getMetadata().getNamespace();
    final String name = dbOps.getMetadata().getName();
    final Map<String, String> labels = labelFactory.dbOpsPodLabels(context.getSource());
    final String timeout = DbOpsUtil.getTimeout(dbOps);
    return new JobBuilder()
        .withNewMetadata()
        .withNamespace(namespace)
        .withName(jobName(dbOps))
        .withLabels(labels)
        .withOwnerReferences(ImmutableList.of(ResourceUtil.getOwnerReference(dbOps)))
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
        .withInitContainers(new ContainerBuilder()
            .withName("set-dbops-running")
            .withImage(getSetResultImage())
            .withImagePullPolicy("IfNotPresent")
            .withEnv(ImmutableList.<EnvVar>builder()
                .addAll(clusterStatefulSetEnvironmentVariables.listResources(context))
                .add(
                    new EnvVarBuilder()
                        .withName("OP_NAME")
                        .withValue(dbOps.getSpec().getOp())
                        .build(),
                    new EnvVarBuilder()
                        .withName("NORMALIZED_OP_NAME")
                        .withValue(UPPERCASE_PATTERN
                            .matcher(dbOps.getSpec().getOp())
                            .replaceAll(result -> " " + result.group(1).toLowerCase(Locale.US)))
                        .build(),
                    new EnvVarBuilder()
                        .withName("KEBAB_OP_NAME")
                        .withValue(UPPERCASE_PATTERN
                            .matcher(dbOps.getSpec().getOp())
                            .replaceAll(result -> "-" + result.group(1).toLowerCase(Locale.US)))
                        .build(),
                    new EnvVarBuilder()
                        .withName("CLUSTER_NAMESPACE")
                        .withValue(namespace)
                        .build(),
                    new EnvVarBuilder()
                        .withName("DB_OPS_NAME")
                        .withValue(name)
                        .build(),
                    new EnvVarBuilder()
                        .withName("DB_OPS_CRD_NAME")
                        .withValue(CustomResource.getCRDName(StackGresDbOps.class))
                        .build(),
                    new EnvVarBuilder()
                        .withName("CURRENT_RETRY")
                        .withValue(retries)
                        .build())
                .addAll(Seq.of(DbOpsStatusCondition.values())
                    .map(c -> new EnvVarBuilder()
                        .withName("CONDITION_" + c.name())
                        .withValue(conditions.get(c))
                        .build())
                    .toList())
                .build())
            .withCommand("/bin/sh", "-ex",
                ClusterStatefulSetPath.LOCAL_BIN_SET_DBOPS_RUNNING_SH_PATH.path())
            .withVolumeMounts(
                ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                    volumeMountBuilder -> volumeMountBuilder
                        .withSubPath(ClusterStatefulSetPath.LOCAL_BIN_SET_DBOPS_RUNNING_SH_PATH
                            .filename())
                        .withMountPath(ClusterStatefulSetPath.LOCAL_BIN_SET_DBOPS_RUNNING_SH_PATH
                            .path())
                        .withReadOnly(true)),
                ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                    volumeMountBuilder -> volumeMountBuilder
                        .withSubPath(ClusterStatefulSetPath.LOCAL_BIN_SHELL_UTILS_PATH.filename())
                        .withMountPath(ClusterStatefulSetPath.LOCAL_BIN_SHELL_UTILS_PATH.path())
                        .withReadOnly(true)))
            .build())
        .withContainers(
            new ContainerBuilder()
                .withName("run-dbops")
                .withImage(getRunImage(context))
                .withImagePullPolicy("IfNotPresent")
                .withEnv(ImmutableList.<EnvVar>builder()
                    .addAll(clusterStatefulSetEnvironmentVariables
                        .listResources(context))
                    .add(
                        new EnvVarBuilder()
                            .withName("OP_NAME")
                            .withValue(dbOps.getSpec().getOp())
                            .build(),
                        new EnvVarBuilder()
                            .withName("EXCLUSIVE_OP")
                            .withValue(String.valueOf(isExclusiveOp()))
                            .build(),
                        new EnvVarBuilder()
                            .withName("NORMALIZED_OP_NAME")
                            .withValue(UPPERCASE_PATTERN
                                .matcher(dbOps.getSpec().getOp())
                                .replaceAll(result -> " " + result.group(1)
                                    .toLowerCase(Locale.US)))
                            .build(),
                        new EnvVarBuilder()
                            .withName("KEBAB_OP_NAME")
                            .withValue(UPPERCASE_PATTERN
                                .matcher(dbOps.getSpec().getOp())
                                .replaceAll(result -> "-" + result.group(1)
                                    .toLowerCase(Locale.US)))
                            .build(),
                        new EnvVarBuilder()
                            .withName("RUN_SCRIPT_PATH")
                            .withValue(Optional.ofNullable(getRunScript())
                                .map(ClusterStatefulSetPath::path)
                                .orElse(""))
                            .build(),
                        new EnvVarBuilder()
                            .withName("TIMEOUT")
                            .withValue(timeout)
                            .build())
                    .addAll(runEnvVars)
                    .build())
                .withCommand("/bin/sh", "-ex",
                    ClusterStatefulSetPath.LOCAL_BIN_RUN_DBOPS_SH_PATH.path())
                .withVolumeMounts(
                    ClusterStatefulSetVolumeConfig.SHARED.volumeMount(context),
                    ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                        volumeMountBuilder -> volumeMountBuilder
                            .withSubPath(ClusterStatefulSetPath
                                .LOCAL_BIN_RUN_DBOPS_SH_PATH.filename())
                            .withMountPath(ClusterStatefulSetPath
                                .LOCAL_BIN_RUN_DBOPS_SH_PATH.path())
                            .withReadOnly(true)),
                    ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                        volumeMountBuilder -> volumeMountBuilder
                            .withSubPath(ClusterStatefulSetPath
                                .LOCAL_BIN_SHELL_UTILS_PATH.filename())
                            .withMountPath(ClusterStatefulSetPath
                                .LOCAL_BIN_SHELL_UTILS_PATH.path())
                            .withReadOnly(true)),
                    ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                        volumeMountBuilder -> volumeMountBuilder
                            .withSubPath(getRunScript().filename())
                            .withMountPath(getRunScript().path())
                            .withReadOnly(true)))
                .build(),
            new ContainerBuilder()
                .withName("set-dbops-result")
                .withImage(StackGresComponent.KUBECTL.findLatestImageName())
                .withImagePullPolicy("IfNotPresent")
                .withEnv(ImmutableList.<EnvVar>builder()
                    .addAll(clusterStatefulSetEnvironmentVariables
                        .listResources(context))
                    .add(
                        new EnvVarBuilder()
                            .withName("OP_NAME")
                            .withValue(dbOps.getSpec().getOp())
                            .build(),
                        new EnvVarBuilder()
                            .withName("NORMALIZED_OP_NAME")
                            .withValue(UPPERCASE_PATTERN
                                .matcher(dbOps.getSpec().getOp())
                                .replaceAll(result -> " "
                                    + result.group(1).toLowerCase(Locale.US)))
                            .build(),
                        new EnvVarBuilder()
                            .withName("KEBAB_OP_NAME")
                            .withValue(UPPERCASE_PATTERN
                                .matcher(dbOps.getSpec().getOp())
                                .replaceAll(result -> "-"
                                    + result.group(1).toLowerCase(Locale.US)))
                            .build(),
                        new EnvVarBuilder()
                            .withName("SET_RESULT_SCRIPT_PATH")
                            .withValue(Optional.ofNullable(getSetResultScript())
                                .map(ClusterStatefulSetPath::path)
                                .orElse(""))
                            .build(),
                        new EnvVarBuilder()
                            .withName("CLUSTER_NAMESPACE")
                            .withValue(namespace)
                            .build(),
                        new EnvVarBuilder()
                            .withName("DB_OPS_NAME")
                            .withValue(name)
                            .build(),
                        new EnvVarBuilder()
                            .withName("DB_OPS_CRD_NAME")
                            .withValue(CustomResource.getCRDName(StackGresDbOps.class))
                            .build(),
                        new EnvVarBuilder()
                            .withName("JOB_POD_LABELS")
                            .withValue(Seq.seq(labels)
                                .append(Tuple.tuple("job-name", jobName(dbOps)))
                                .map(t -> t.v1 + "=" + t.v2).toString(","))
                            .build())
                    .addAll(Seq.of(DbOpsStatusCondition.values())
                        .map(c -> new EnvVarBuilder()
                            .withName("CONDITION_" + c.name())
                            .withValue(conditions.get(c))
                            .build())
                        .toList())
                    .addAll(setResultEnvVars)
                    .build())
                .withCommand("/bin/sh", "-ex",
                    ClusterStatefulSetPath.LOCAL_BIN_SET_DBOPS_RESULT_SH_PATH.path())
                .withVolumeMounts(
                    ClusterStatefulSetVolumeConfig.SHARED.volumeMount(context),
                    ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                        volumeMountBuilder -> volumeMountBuilder
                            .withSubPath(
                                ClusterStatefulSetPath
                                    .LOCAL_BIN_SET_DBOPS_RESULT_SH_PATH.filename())
                            .withMountPath(
                                ClusterStatefulSetPath
                                    .LOCAL_BIN_SET_DBOPS_RESULT_SH_PATH.path())
                            .withReadOnly(true)),
                    ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                        volumeMountBuilder -> volumeMountBuilder
                            .withSubPath(ClusterStatefulSetPath
                                .LOCAL_BIN_SHELL_UTILS_PATH.filename())
                            .withMountPath(ClusterStatefulSetPath.LOCAL_BIN_SHELL_UTILS_PATH.path())
                            .withReadOnly(true)))
                .addAllToVolumeMounts(Optional.ofNullable(getSetResultScript())
                    .map(script -> ClusterStatefulSetVolumeConfig.TEMPLATES.volumeMount(context,
                        volumeMountBuilder -> volumeMountBuilder
                            .withSubPath(script.filename())
                            .withMountPath(script.path())
                            .withReadOnly(true)))
                    .stream()
                    .collect(Collectors.toList()))
                .build())
        .withVolumes(
            ClusterStatefulSetVolumeConfig.SHARED.volume(context),
            new VolumeBuilder(ClusterStatefulSetVolumeConfig.TEMPLATES.volume(context))
                .editConfigMap()
                .withDefaultMode(0555) // NOPMD
                .endConfigMap()
                .build())
        .endSpec()
        .endTemplate()
        .endSpec()
        .build();
  }
}
