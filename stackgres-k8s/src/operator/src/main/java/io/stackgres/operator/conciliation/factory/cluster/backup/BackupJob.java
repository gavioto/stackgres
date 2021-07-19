/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.collect.ImmutableList;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectFieldSelectorBuilder;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.CustomResource;
import io.stackgres.common.ClusterStatefulSetPath;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.StackGresComponent;
import io.stackgres.common.StackGresContext;
import io.stackgres.common.StackGresUtil;
import io.stackgres.common.StackgresClusterContainers;
import io.stackgres.common.crd.sgbackup.BackupPhase;
import io.stackgres.common.crd.sgbackup.StackGresBackup;
import io.stackgres.common.crd.sgbackup.StackGresBackupProcess;
import io.stackgres.common.crd.sgbackup.StackGresBackupStatus;
import io.stackgres.common.crd.sgbackupconfig.StackGresBackupConfig;
import io.stackgres.common.crd.sgbackupconfig.StackGresBackupConfigSpec;
import io.stackgres.common.crd.sgbackupconfig.StackGresBaseBackupConfig;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.ResourceGenerator;
import io.stackgres.operator.conciliation.cluster.StackGresClusterContext;
import io.stackgres.operator.conciliation.cluster.StackGresVersion;
import io.stackgres.operator.conciliation.factory.ResourceFactory;
import io.stackgres.operator.conciliation.factory.cluster.patroni.ClusterEnvironmentVariablesFactory;
import io.stackgres.operator.conciliation.factory.cluster.patroni.ClusterEnvironmentVariablesFactoryDiscoverer;
import io.stackgres.operator.conciliation.factory.cluster.patroni.ClusterStatefulSetVolumeConfig;
import io.stackgres.operator.conciliation.factory.cluster.patroni.PatroniRoleGenerator;
import io.stackgres.operatorframework.resource.ResourceUtil;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@OperatorVersionBinder(startAt = StackGresVersion.V09, stopAt = StackGresVersion.V10)
public class BackupJob
    implements ResourceGenerator<StackGresClusterContext> {

  private static final Logger LOGGER = LoggerFactory.getLogger(BackupJob.class);

  private final ClusterEnvironmentVariablesFactoryDiscoverer<StackGresClusterContext>
      clusterEnvVarFactoryDiscoverer;

  private final LabelFactory<StackGresCluster> labelFactory;
  private final ResourceFactory<StackGresClusterContext, PodSecurityContext> podSecurityFactory;

  @Inject
  public BackupJob(ClusterEnvironmentVariablesFactoryDiscoverer<StackGresClusterContext>
                       clusterEnvVarFactoryDiscoverer,
                   LabelFactory<StackGresCluster> labelFactory,
                   ResourceFactory<StackGresClusterContext,
                       PodSecurityContext> podSecurityFactory) {
    super();
    this.clusterEnvVarFactoryDiscoverer = clusterEnvVarFactoryDiscoverer;
    this.labelFactory = labelFactory;
    this.podSecurityFactory = podSecurityFactory;
  }

  public static String backupJobName(StackGresBackup backup) {
    String name = backup.getMetadata().getName();
    return ResourceUtil.resourceName(
        name + StackGresUtil.BACKUP_SUFFIX);
  }

  @Override
  public Stream<HasMetadata> generateResource(StackGresClusterContext context) {
    return context.getBackupConfig().map(backupConfig -> context.getBackups().stream()
        .filter(backup -> !Optional.ofNullable(backup.getStatus())
            .map(StackGresBackupStatus::getProcess)
            .map(StackGresBackupProcess::getStatus)
            .map(status -> status.equals(BackupPhase.COMPLETED.label()))
            .orElse(false)
            && Seq.seq(backup.getMetadata().getAnnotations())
            .noneMatch(Tuple.tuple(
                StackGresContext.SCHEDULED_BACKUP_KEY, StackGresContext.RIGHT_VALUE)::equals))
        .map(backup -> createBackupJob(backup, context))
        .filter(Optional::isPresent)
        .map(Optional::get))
        .orElse(Stream.of());
  }

  private Optional<HasMetadata> createBackupJob(StackGresBackup backup,
                                                StackGresClusterContext context) {
    String namespace = backup.getMetadata().getNamespace();
    String name = backup.getMetadata().getName();
    String cluster = backup.getSpec().getSgCluster();
    Map<String, String> labels = labelFactory.backupPodLabels(context.getSource());
    return context.getBackupConfig()
        .map(backupConfig -> {
          final VolumeMount utilsVolumeMount = ClusterStatefulSetVolumeConfig.TEMPLATES
              .volumeMount(context, volumeMountBuilder -> volumeMountBuilder
                  .withSubPath(ClusterStatefulSetPath
                      .LOCAL_BIN_SHELL_UTILS_PATH.filename())
                  .withMountPath(ClusterStatefulSetPath.LOCAL_BIN_SHELL_UTILS_PATH.path())
                  .withReadOnly(true));
          final VolumeMount backupVolumeMount = ClusterStatefulSetVolumeConfig.TEMPLATES
              .volumeMount(context, volumeMountBuilder -> volumeMountBuilder
                  .withSubPath(ClusterStatefulSetPath.LOCAL_BIN_CREATE_BACKUP_SH_PATH
                      .filename())
                  .withMountPath(ClusterStatefulSetPath.LOCAL_BIN_CREATE_BACKUP_SH_PATH
                      .path())
                  .withReadOnly(true));
          return new JobBuilder()
              .withNewMetadata()
              .withNamespace(namespace)
              .withName(backupJobName(backup))
              .withLabels(labels)
              .withOwnerReferences(ImmutableList.of(ResourceUtil.getOwnerReference(backup)))
              .endMetadata()
              .withNewSpec()
              .withBackoffLimit(3)
              .withCompletions(1)
              .withParallelism(1)
              .withNewTemplate()
              .withNewMetadata()
              .withNamespace(namespace)
              .withName(backupJobName(backup))
              .withLabels(labels)
              .endMetadata()
              .withNewSpec()
              .withSecurityContext(podSecurityFactory.createResource(context))
              .withRestartPolicy("OnFailure")
              .withServiceAccountName(PatroniRoleGenerator.roleName(context))
              .withContainers(new ContainerBuilder()
                  .withName("create-backup")
                  .withImage(StackGresComponent.KUBECTL.findLatestImageName())
                  .withImagePullPolicy("IfNotPresent")
                  .withEnv(ImmutableList.<EnvVar>builder()
                      .addAll(getClusterEnvVars(context))
                      .add(new EnvVarBuilder()
                              .withName("CLUSTER_NAMESPACE")
                              .withValue(namespace)
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_NAME")
                              .withValue(name)
                              .build(),
                          new EnvVarBuilder()
                              .withName("CLUSTER_NAME")
                              .withValue(cluster)
                              .build(),
                          new EnvVarBuilder()
                              .withName("CRONJOB_NAME")
                              .withValue(cluster + StackGresUtil.BACKUP_SUFFIX)
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_IS_PERMANENT")
                              .withValue(Optional.ofNullable(backup.getSpec()
                                  .getManagedLifecycle())
                                  .map(managedLifecycle -> !managedLifecycle)
                                  .map(String::valueOf)
                                  .orElse("true"))
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_CONFIG_CRD_NAME")
                              .withValue(CustomResource.getCRDName(StackGresBackupConfig.class))
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_CONFIG")
                              .withValue(backupConfig.getMetadata().getName())
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_CRD_KIND")
                              .withValue(HasMetadata.getKind(StackGresBackup.class))
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_CRD_NAME")
                              .withValue(CustomResource.getCRDName(StackGresBackup.class))
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_CRD_APIVERSION")
                              .withValue(HasMetadata.getApiVersion(StackGresBackup.class))
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_PHASE_RUNNING")
                              .withValue(BackupPhase.RUNNING.label())
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_PHASE_COMPLETED")
                              .withValue(BackupPhase.COMPLETED.label())
                              .build(),
                          new EnvVarBuilder()
                              .withName("BACKUP_PHASE_FAILED")
                              .withValue(BackupPhase.FAILED.label())
                              .build(),
                          new EnvVarBuilder()
                              .withName("PATRONI_ROLE_KEY")
                              .withValue(StackGresContext.ROLE_KEY)
                              .build(),
                          new EnvVarBuilder()
                              .withName("PATRONI_PRIMARY_ROLE")
                              .withValue(StackGresContext.PRIMARY_ROLE)
                              .build(),
                          new EnvVarBuilder()
                              .withName("PATRONI_REPLICA_ROLE")
                              .withValue(StackGresContext.REPLICA_ROLE)
                              .build(),
                          new EnvVarBuilder()
                              .withName("PATRONI_CLUSTER_LABELS")
                              .withValue(labelFactory.patroniClusterLabels(context.getSource())
                                  .entrySet()
                                  .stream()
                                  .map(e -> e.getKey() + "=" + e.getValue())
                                  .collect(Collectors.joining(",")))
                              .build(),
                          new EnvVarBuilder()
                              .withName("PATRONI_CONTAINER_NAME")
                              .withValue(StackgresClusterContainers.PATRONI)
                              .build(),
                          new EnvVarBuilder().withName("POD_NAME")
                              .withValueFrom(
                                  new EnvVarSourceBuilder()
                                      .withFieldRef(
                                          new ObjectFieldSelectorBuilder()
                                              .withFieldPath("metadata.name")
                                              .build())
                                      .build())
                              .build(),
                          new EnvVarBuilder()
                              .withName("RETAIN")
                              .withValue(Optional.of(backupConfig.getSpec())
                                  .map(StackGresBackupConfigSpec::getBaseBackups)
                                  .map(StackGresBaseBackupConfig::getRetention)
                                  .map(String::valueOf)
                                  .orElse("5"))
                              .build(),
                          new EnvVarBuilder()
                              .withName("WINDOW")
                              .withValue("3600")
                              .build())
                      .build())
                  .withCommand("/bin/bash", "-e" + (LOGGER.isTraceEnabled() ? "x" : ""),
                      ClusterStatefulSetPath.LOCAL_BIN_CREATE_BACKUP_SH_PATH.path())
                  .withVolumeMounts(backupVolumeMount, utilsVolumeMount)
                  .build())
              .withVolumes(new VolumeBuilder(ClusterStatefulSetVolumeConfig.TEMPLATES
                  .volume(context))
                  .editConfigMap()
                  .withDefaultMode(0555) // NOPMD
                  .endConfigMap()
                  .build())
              .endSpec()
              .endTemplate()
              .endSpec()
              .build();
        });
  }

  private List<EnvVar> getClusterEnvVars(StackGresClusterContext context) {
    List<EnvVar> clusterEnvVars = new ArrayList<>();

    List<ClusterEnvironmentVariablesFactory<StackGresClusterContext>> clusterEnvVarFactories =
        clusterEnvVarFactoryDiscoverer.discoverFactories(context);

    clusterEnvVarFactories.forEach(envVarFactory ->
        clusterEnvVars.addAll(envVarFactory.buildEnvironmentVariables(context)));
    return clusterEnvVars;
  }
}
