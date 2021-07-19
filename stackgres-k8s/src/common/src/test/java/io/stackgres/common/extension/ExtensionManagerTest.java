/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.common.extension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.stackgres.common.ClusterContext;
import io.stackgres.common.ClusterStatefulSetPath;
import io.stackgres.common.FileSystemHandler;
import io.stackgres.common.StackGresComponent;
import io.stackgres.common.WebClientFactory;
import io.stackgres.common.WebClientFactory.WebClient;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterInstalledExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterList;
import io.stackgres.common.extension.ExtensionManager.ExtensionInstaller;
import io.stackgres.testutil.JsonUtil;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@RunWith(MockitoJUnitRunner.class)
public class ExtensionManagerTest {

  private static final URI REPOSITORY = URI.create("https://extensions.stackgres.io/postgres/repository?skipHostVerification=true");

  private static final String POSTGRES_VERSION =
      StackGresComponent.POSTGRESQL.getOrderedVersions().findFirst().get();

  private static final String POSTGRES_MAJOR_VERSION =
      StackGresComponent.POSTGRESQL.getOrderedMajorVersions().findFirst().get();

  private static final String BUILD_VERSION =
      StackGresComponent.POSTGRESQL.getOrderedBuildVersions().findFirst().get();

  private static final String BUILD_MAJOR_VERSION =
      StackGresComponent.POSTGRESQL.getOrderedBuildMajorVersions().findFirst().get();

  @Mock
  private WebClientFactory webClientFactory;

  @Mock
  private WebClient webClient;

  @Mock
  private FileSystemHandler fileSystemHandler;

  private ExtensionMetadataManager extensionMetadataManager;

  private ExtensionManager extensionManager;

  @BeforeEach
  void setUp() throws Exception {
    extensionMetadataManager = new ExtensionMetadataManager(webClientFactory,
        ImmutableList.of(REPOSITORY)) {};
    extensionManager = new ExtensionManager(extensionMetadataManager,
        webClientFactory, fileSystemHandler) { };
  }

  private ClusterContext context(StackGresCluster cluster) {
    return new ClusterContext() {
      @Override
      public Map<String, String> getEnvironmentVariables() {
        return ImmutableMap.of(
            "POSTGRES_VERSION", POSTGRES_VERSION,
            "POSTGRES_MAJOR_VERSION", POSTGRES_MAJOR_VERSION,
            "BUILD_VERSION", BUILD_VERSION,
            "BUILD_MAJOR_VERSION", BUILD_MAJOR_VERSION);
      }

      @Override
      public StackGresCluster getCluster() {
        return cluster;
      }
    };
  }

  private StackGresExtensions getExtensions() throws Exception {
    StackGresExtensions extensions = JsonUtil
        .readFromJson("extension_metadata/index.json",
            StackGresExtensions.class);
    extensions.getPublishers().get(0).setPublicKey(IOUtils.toString(
        getClass().getResourceAsStream("/test.pub"), StandardCharsets.UTF_8));
    extensions.getExtensions().add(getExtension());
    return extensions;
  }

  private StackGresExtension getExtension() {
    StackGresExtension extension = new StackGresExtension();
    extension.setName("timescaledb");
    extension.setPublisher("com.ongres");
    extension.setRepository(null);
    extension.setChannels(ImmutableMap.of(ExtensionUtil.DEFAULT_CHANNEL, "1.7.1"));
    extension.setVersions(new ArrayList<>());
    StackGresExtensionVersion version = new StackGresExtensionVersion();
    version.setVersion("1.7.1");
    version.setAvailableFor(new ArrayList<>());
    StackGresExtensionVersionTarget target = new StackGresExtensionVersionTarget();
    target.setPostgresVersion(POSTGRES_MAJOR_VERSION);
    target.setBuild(BUILD_MAJOR_VERSION);
    version.getAvailableFor().add(target);
    extension.getVersions().add(version);
    return extension;
  }

  private StackGresExtensionMetadata getExtensionMetadata() throws Exception {
    return new StackGresExtensionMetadata(
        getExtension(),
        getExtension().getVersions().get(0),
        getExtension().getVersions().get(0).getAvailableFor().get(0),
        getExtensions().getPublishers().get(0));
  }

  private StackGresClusterExtension getClusterExtension() {
    StackGresClusterExtension extension = new StackGresClusterExtension();
    extension.setName("timescaledb");
    return extension;
  }

  private StackGresClusterInstalledExtension getInstalledExtension() {
    StackGresClusterInstalledExtension installedExtension = new StackGresClusterInstalledExtension();
    installedExtension.setName("timescaledb");
    installedExtension.setPublisher("com.ongres");
    installedExtension.setRepository(REPOSITORY.toASCIIString());
    installedExtension.setVersion("1.7.1");
    installedExtension.setPostgresVersion(POSTGRES_MAJOR_VERSION);
    installedExtension.setBuild(BUILD_MAJOR_VERSION);
    return installedExtension;
  }

  private StackGresCluster getCluster() {
    StackGresCluster cluster = JsonUtil
        .readFromJson("stackgres_cluster/list.json",
            StackGresClusterList.class).getItems().get(0);
    cluster.getSpec().setPostgresVersion(POSTGRES_VERSION);
    return cluster;
  }

  @Test
  void testDownloadAndExtractExtension() throws Exception {
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    when(webClient.getInputStream(any()))
        .then(invocation -> getClass().getResourceAsStream("/test.tar"));
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    extensionManager.getExtensionInstaller(context(cluster), extension).downloadAndExtract();
    verify(webClientFactory, times(2)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(1)).getInputStream(
        eq(ExtensionUtil.getExtensionPackageUri(REPOSITORY, extension, getExtensionMetadata())));
    verify(fileSystemHandler, times(0)).newInputStream(any());
    verify(fileSystemHandler, times(0)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(2)).createDirectories(any());
    verify(fileSystemHandler, times(2)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))));
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(2)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(1)).copyOrReplace(any(),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster))).resolve("test.tgz.sha256")));
    verify(fileSystemHandler, times(1)).copyOrReplace(any(),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster))).resolve("test.tgz")));
    verify(fileSystemHandler, times(2)).setPosixFilePermissions(any(), any());
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster))).resolve("test.tgz.sha256")), eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster))).resolve("test.tgz")), eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ)));
    verify(fileSystemHandler, times(0)).deleteIfExists(any());
  }

  @Test
  void testVerifyExtension() throws Exception {
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    final String extensionPackageName = ExtensionUtil.getExtensionPackageName(getInstalledExtension());
    when(fileSystemHandler
        .newInputStream(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.SHA256_SUFFIX))))
        .then(invocation -> getClass().getResourceAsStream("/test.tgz.sha256"));
    when(fileSystemHandler
        .newInputStream(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX))))
        .then(invocation -> getClass().getResourceAsStream("/test.tgz"));
    extensionManager.getExtensionInstaller(context(cluster), extension).verify();;
    verify(webClientFactory, times(1)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(
        eq(ExtensionUtil.getExtensionPackageUri(REPOSITORY, extension, getExtensionMetadata())));
    verify(fileSystemHandler, times(2)).newInputStream(any());
    verify(fileSystemHandler, times(1)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.SHA256_SUFFIX)));
    verify(fileSystemHandler, times(1)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX)));
    verify(fileSystemHandler, times(0)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(0)).createDirectories(any());
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(0)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(0)).setPosixFilePermissions(any(), any());
    verify(fileSystemHandler, times(0)).deleteIfExists(any());
  }

  @Test
  void testInstallExtension() throws Exception {
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    final String extensionPackageName = ExtensionUtil.getExtensionPackageName(getInstalledExtension());
    when(fileSystemHandler
        .newInputStream(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX))))
        .then(invocation -> getClass().getResourceAsStream("/test.tgz"));
    when(fileSystemHandler
        .list(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB_PATH.path(context(cluster))))))
        .thenReturn(Stream.of(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB_PATH.path(context(cluster)))
            .resolve("test.so")));
    ExtensionInstaller extensionInstaller = extensionManager.getExtensionInstaller(context(cluster), extension);
    extensionInstaller.installExtension();
    verify(webClientFactory, times(1)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(any());
    verify(fileSystemHandler, times(1)).newInputStream(any());
    verify(fileSystemHandler, times(1)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX)));
    verify(fileSystemHandler, times(2)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(1)).createOrReplaceFile(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.INSTALLED_SUFFIX)));
    verify(fileSystemHandler, times(1)).createOrReplaceFile(
        eq(Paths.get(ClusterStatefulSetPath.PG_RELOCATED_LIB_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.LINKS_CREATED_SUFFIX)));
    verify(fileSystemHandler, times(17)).createDirectories(any());
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr")));
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib")));
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql")));
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12")));
    verify(fileSystemHandler, times(2)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/lib")));
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/bin")));
    verify(fileSystemHandler, times(4)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))));
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share")));
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql")));
    verify(fileSystemHandler, times(1)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12")));
    verify(fileSystemHandler, times(3)).createDirectories(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension")));
    verify(fileSystemHandler, times(3)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(1)).createOrReplaceSymbolicLink(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))
            .resolve("test.so.1")),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))
            .resolve("test.so.1.0")));
    verify(fileSystemHandler, times(1)).createOrReplaceSymbolicLink(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))
            .resolve("test.so.2")),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))
            .resolve("test.so.2.0")));
    verify(fileSystemHandler, times(1)).createOrReplaceSymbolicLink(
        eq(Paths.get(ClusterStatefulSetPath.PG_RELOCATED_LIB_PATH.path(context(cluster)))
            .resolve("test.so")),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB_PATH.path(context(cluster)))
            .resolve("test.so")));
    verify(fileSystemHandler, times(4)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(1)).copyOrReplace(any(),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/lib").resolve("test.so")));
    verify(fileSystemHandler, times(1)).copyOrReplace(any(),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension").resolve("test.control")));
    verify(fileSystemHandler, times(1)).copyOrReplace(any(),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension").resolve("test.sql")));
    verify(fileSystemHandler, times(1)).copyOrReplace(any(),
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))
            .resolve("test.so.1.0")));
    verify(fileSystemHandler, times(15)).setPosixFilePermissions(any(), any());
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/lib")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/bin")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/lib").resolve("test.so")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension").resolve("test.control")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension").resolve("test.sql")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ)));
    verify(fileSystemHandler, times(1)).setPosixFilePermissions(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_LIB64_PATH.path(context(cluster)))
            .resolve("test.so.1.0")),
        eq(ImmutableSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ)));
    verify(fileSystemHandler, times(1)).deleteIfExists(any());
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.PENDING_SUFFIX)));
  }

  @Test
  void testCheckExtensionWillNotOverwrite() throws Exception {
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    final String extensionPackageName = ExtensionUtil.getExtensionPackageName(getInstalledExtension());
    when(fileSystemHandler
        .newInputStream(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX))))
        .then(invocation -> getClass().getResourceAsStream("/test.tgz"));
    when(fileSystemHandler
        .exists(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/lib").resolve("test.so"))))
        .thenReturn(false);
    Assertions.assertFalse(extensionManager.getExtensionInstaller(context(cluster), extension)
        .doesInstallOverwriteAnySharedLibrary());
    verify(webClientFactory, times(1)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(
        eq(ExtensionUtil.getExtensionPackageUri(REPOSITORY, extension, getExtensionMetadata())));
    verify(fileSystemHandler, times(1)).newInputStream(any());
    verify(fileSystemHandler, times(1)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX)));
    verify(fileSystemHandler, times(0)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(0)).createDirectories(any());
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(0)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(0)).setPosixFilePermissions(any(), any());
    verify(fileSystemHandler, times(0)).deleteIfExists(any());
  }

  @Test
  void testCheckExtensionWillOverwrite() throws Exception {
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    final String extensionPackageName = ExtensionUtil.getExtensionPackageName(getInstalledExtension());
    when(fileSystemHandler
        .newInputStream(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX))))
        .then(invocation -> getClass().getResourceAsStream("/test.tgz"));
    when(fileSystemHandler
        .exists(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/lib/postgresql/12/lib").resolve("test.so"))))
        .thenReturn(true);
    Assertions.assertTrue(extensionManager.getExtensionInstaller(context(cluster), extension)
        .doesInstallOverwriteAnySharedLibrary());
    verify(webClientFactory, times(1)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(
        eq(ExtensionUtil.getExtensionPackageUri(REPOSITORY, extension, getExtensionMetadata())));
    verify(fileSystemHandler, times(0)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.SHA256_SUFFIX)));
    verify(fileSystemHandler, times(1)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX)));
    verify(fileSystemHandler, times(0)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(0)).createDirectories(any());
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(0)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(0)).setPosixFilePermissions(any(), any());
    verify(fileSystemHandler, times(0)).deleteIfExists(any());
  }

  @Test
  void testIsExtensionNotPending() throws Exception {
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    Assertions.assertFalse(
        extensionManager.getExtensionInstaller(context(cluster), extension).isExtensionPendingOverwrite());
    verify(webClientFactory, times(1)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(any());
    verify(fileSystemHandler, times(0)).newInputStream(any());
    verify(fileSystemHandler, times(0)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(0)).createDirectories(any());
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(0)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(0)).setPosixFilePermissions(
        any(), any());
    verify(fileSystemHandler, times(0)).deleteIfExists(any());
  }

  @Test
  void testIsExtensionPending() throws Exception {
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    final String extensionPackageName = ExtensionUtil.getExtensionPackageName(getInstalledExtension());
    when(fileSystemHandler
        .exists(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.PENDING_SUFFIX))))
        .thenReturn(true);
    Assertions.assertTrue(
        extensionManager.getExtensionInstaller(context(cluster), extension).isExtensionPendingOverwrite());
    verify(webClientFactory, times(1)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(any());
    verify(fileSystemHandler, times(0)).newInputStream(any());
    verify(fileSystemHandler, times(0)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(0)).createDirectories(any());
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(0)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(0)).setPosixFilePermissions(
        any(), any());
    verify(fileSystemHandler, times(0)).deleteIfExists(any());
  }

  @Test
  void testSetExtensionAsPending() throws Exception {
    StackGresCluster cluster = getCluster();
    StackGresClusterInstalledExtension extension = getInstalledExtension();
    when(webClientFactory.create(anyBoolean())).thenReturn(webClient);
    when(webClient.getJson(any(), any())).thenReturn(getExtensions());
    final String extensionPackageName = ExtensionUtil.getExtensionPackageName(getInstalledExtension());
    ExtensionInstaller extensionInstaller = extensionManager.getExtensionInstaller(context(cluster), extension);
    extensionInstaller.setExtensionAsPending();
    verify(webClientFactory, times(1)).create(anyBoolean());
    verify(webClient, times(1)).getJson(any(), any());
    verify(webClient, times(1)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(any());
    verify(fileSystemHandler, times(0)).newInputStream(any());
    verify(fileSystemHandler, times(1)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(1)).createOrReplaceFile(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.PENDING_SUFFIX)));
    verify(fileSystemHandler, times(0)).createDirectories(any());
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(0)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(0)).setPosixFilePermissions(
        any(), any());
    verify(fileSystemHandler, times(0)).deleteIfExists(any());
  }

  @Test
  void testUninstallExtension() throws Exception {
    StackGresCluster cluster = getCluster();
    StackGresClusterExtension extension = getClusterExtension();
    final String extensionPackageName = ExtensionUtil.getExtensionPackageName(getInstalledExtension());
    when(fileSystemHandler
        .newInputStream(eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX))))
        .then(invocation -> getClass().getResourceAsStream("/test.tgz"));
    StackGresClusterInstalledExtension installedExtension = getInstalledExtension();
    extensionManager.getExtensionUninstaller(context(cluster), installedExtension).uninstallExtension();
    verify(webClientFactory, times(0)).create(anyBoolean());
    verify(webClient, times(0)).getJson(
        eq(ExtensionUtil.getIndexUri(REPOSITORY)), eq(StackGresExtensions.class));
    verify(webClient, times(0)).getInputStream(
        eq(ExtensionUtil.getExtensionPackageUri(REPOSITORY, extension, getExtensionMetadata())));
    verify(fileSystemHandler, times(0)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.INSTALLED_SUFFIX)));
    verify(fileSystemHandler, times(1)).newInputStream(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX)));
    verify(fileSystemHandler, times(0)).createOrReplaceFile(any());
    verify(fileSystemHandler, times(0)).createDirectories(any());
    verify(fileSystemHandler, times(0)).createOrReplaceSymbolicLink(any(), any());
    verify(fileSystemHandler, times(0)).copyOrReplace(any(), any());
    verify(fileSystemHandler, times(0)).setPosixFilePermissions(any(), any());
    verify(fileSystemHandler, times(7)).deleteIfExists(any());
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension").resolve("test.control")));
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve("usr/share/postgresql/12/extension").resolve("test.sql")));
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.TGZ_SUFFIX)));
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.SHA256_SUFFIX)));
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.PENDING_SUFFIX)));
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_RELOCATED_LIB_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.LINKS_CREATED_SUFFIX)));
    verify(fileSystemHandler, times(1)).deleteIfExists(
        eq(Paths.get(ClusterStatefulSetPath.PG_EXTENSIONS_PATH.path(context(cluster)))
            .resolve(extensionPackageName + ExtensionManager.INSTALLED_SUFFIX)));
  }

}
