/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterInstalledExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterPodStatus;
import io.stackgres.common.crd.sgcluster.StackGresClusterStatus;
import io.stackgres.common.extension.ExtensionManager.ExtensionInstaller;
import io.stackgres.common.extension.ExtensionManager.ExtensionUninstaller;
import io.stackgres.operatorframework.reconciliation.ReconciliationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ExtensionReconciliator<T extends ExtensionReconciliatorContext> {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExtensionReconciliator.class);

  private final String podName;
  private final ExtensionManager extensionManager;
  private final boolean skipSharedLibrariesOverwrites;

  public ExtensionReconciliator(String podName, ExtensionManager extensionManager,
      boolean skipSharedLibrariesOverwrites) {
    this.podName = podName;
    this.extensionManager = extensionManager;
    this.skipSharedLibrariesOverwrites = skipSharedLibrariesOverwrites;
  }

  @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION",
      justification = "False positives")
  public ReconciliationResult<Boolean> reconcile(KubernetesClient client, T context)
      throws Exception {
    final ImmutableList.Builder<Exception> exceptions = ImmutableList.builder();
    final StackGresCluster cluster = context.getCluster();
    final ImmutableList<StackGresClusterInstalledExtension> extensions = context.getExtensions();
    if (cluster.getStatus() == null) {
      cluster.setStatus(new StackGresClusterStatus());
    }
    if (cluster.getStatus().getPodStatuses() == null) {
      cluster.getStatus().setPodStatuses(new ArrayList<>());
    }
    if (cluster.getStatus().getPodStatuses().stream()
        .noneMatch(podStatus -> podStatus.getName().equals(podName))) {
      StackGresClusterPodStatus podStatus = new StackGresClusterPodStatus();
      podStatus.setName(podName);
      podStatus.setInstalledPostgresExtensions(new ArrayList<>());
      cluster.getStatus().getPodStatuses().add(podStatus);
    }
    final StackGresClusterPodStatus podStatus = cluster.getStatus().getPodStatuses().stream()
        .filter(status -> status.getName().equals(podName))
        .findAny().get();
    if (podStatus.getInstalledPostgresExtensions() == null) {
      podStatus.setInstalledPostgresExtensions(new ArrayList<>());
    }
    final List<StackGresClusterInstalledExtension> installedExtensions =
        podStatus.getInstalledPostgresExtensions();
    LOGGER.info("Reconcile postgres extensions...");
    boolean clusterUpdated = false;
    final List<StackGresClusterInstalledExtension> extensionToUninstall = installedExtensions
        .stream()
        .filter(installedExtension -> extensions.stream()
            .noneMatch(installedExtension::same))
        .collect(Collectors.toList());

    for (StackGresClusterInstalledExtension installedExtension : extensionToUninstall) {
      ExtensionUninstaller extensionUninstaller = extensionManager.getExtensionUninstaller(
          context, installedExtension);
      try {
        if (!skipSharedLibrariesOverwrites) {
          if (extensionUninstaller.isExtensionInstalled()) {
            LOGGER.info("Removing extension {}",
                ExtensionUtil.getDescription(installedExtension));
            extensionUninstaller.uninstallExtension();
          }
          installedExtensions.remove(installedExtension);
          clusterUpdated = true;
        } else {
          LOGGER.info("Skip uninstallation of extension {}",
              ExtensionUtil.getDescription(installedExtension));
          if (!Optional.ofNullable(podStatus.getPendingRestart()).orElse(false)) {
            podStatus.setPendingRestart(true);
            clusterUpdated = true;
          }
        }
      } catch (Exception ex) {
        exceptions.add(ex);
        onUninstallException(client, cluster, ExtensionUtil.getDescription(installedExtension),
            podName, ex);
      }
    }
    for (StackGresClusterInstalledExtension extension : extensions) {
      try {
        final ExtensionInstaller extensionInstaller = Optional.ofNullable(
            extensionManager.getExtensionInstaller(context, extension))
            .orElseThrow(() -> new IllegalStateException(
                "Can not find extension " + ExtensionUtil.getDescription(extension)));
        if (!extensionInstaller.isExtensionInstalled()
            && (!skipSharedLibrariesOverwrites
                || !extensionInstaller.isExtensionPendingOverwrite())) {
          LOGGER.info("Download extension {}", ExtensionUtil.getDescription(extension));
          extensionInstaller.downloadAndExtract();
          LOGGER.info("Verify extension {}", ExtensionUtil.getDescription(extension));
          extensionInstaller.verify();
          if (skipSharedLibrariesOverwrites
              && extensionInstaller.doesInstallOverwriteAnySharedLibrary()) {
            LOGGER.info("Skip installation of extension {}",
                ExtensionUtil.getDescription(extension));
            if (!extensionInstaller.isExtensionPendingOverwrite()) {
              extensionInstaller.setExtensionAsPending();
            }
            if (!Optional.ofNullable(podStatus.getPendingRestart()).orElse(false)) {
              podStatus.setPendingRestart(true);
              clusterUpdated = true;
            }
          } else {
            LOGGER.info("Install extension {}", ExtensionUtil.getDescription(extension));
            extensionInstaller.installExtension();
          }
        } else {
          if (!extensionInstaller.isLinksCreated()) {
            LOGGER.info("Create links for extension {}", ExtensionUtil.getDescription(extension));
            extensionInstaller.createExtensionLinks();
          }
        }
        if (installedExtensions
            .stream()
            .noneMatch(anInstalledExtension -> anInstalledExtension.equals(extension))) {
          installedExtensions.stream()
              .filter(anInstalledExtension -> anInstalledExtension.same(extension))
              .peek(previousInstalledExtension -> LOGGER.info("Extension upgraded from {} to {}",
                  ExtensionUtil.getDescription(previousInstalledExtension),
                  ExtensionUtil.getDescription(extension)))
              .findAny()
              .ifPresent(installedExtensions::remove);
          installedExtensions.add(extension);
          clusterUpdated = true;
        }
      } catch (Exception ex) {
        exceptions.add(ex);
        onInstallException(client, cluster, ExtensionUtil.getDescription(extension),
            podName, ex);
      }
    }
    if (!skipSharedLibrariesOverwrites
        && Optional.ofNullable(podStatus.getPendingRestart()).orElse(false)) {
      podStatus.setPendingRestart(false);
      clusterUpdated = true;
    }
    LOGGER.info("Reconciliation of postgres extensions completed");
    return new ReconciliationResult<>(clusterUpdated, exceptions.build());
  }

  protected abstract void onUninstallException(KubernetesClient client, StackGresCluster cluster,
      String extension, String podName, Exception ex);

  protected abstract void onInstallException(KubernetesClient client, StackGresCluster cluster,
      String extension, String podName, Exception ex);

}
