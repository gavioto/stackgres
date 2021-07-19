/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.mutation.distributedlogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import com.github.fge.jsonpatch.AddOperation;
import com.github.fge.jsonpatch.JsonPatchOperation;
import com.google.common.collect.ImmutableList;
import io.stackgres.common.OperatorProperty;
import io.stackgres.common.StackGresComponent;
import io.stackgres.common.crd.sgcluster.StackGresClusterExtension;
import io.stackgres.common.crd.sgcluster.StackGresClusterInstalledExtension;
import io.stackgres.common.crd.sgdistributedlogs.StackGresDistributedLogsStatus;
import io.stackgres.common.extension.StackGresExtensionMetadata;
import io.stackgres.operator.common.StackGresDistributedLogsReview;
import io.stackgres.operator.mutation.ClusterExtensionMetadataManager;
import io.stackgres.testutil.JsonUtil;
import org.jooq.lambda.Seq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExtensionsMutatorTest {

  private static final String POSTGRES_MAJOR_VERSION =
      StackGresComponent.POSTGRESQL.getOrderedMajorVersions().findFirst().get();

  private static final String BUILD_VERSION =
      StackGresComponent.POSTGRESQL.getOrderedBuildVersions().findFirst().get();

  private StackGresDistributedLogsReview review;

  @Mock
  private ClusterExtensionMetadataManager extensionMetadataManager;

  private ExtensionsMutator mutator;

  private List<StackGresClusterInstalledExtension> defaultExtensions;

  @BeforeEach
  void setUp() throws Exception {
    review = JsonUtil
        .readFromJson("distributedlogs_allow_request/create.json", StackGresDistributedLogsReview.class);

    mutator = new ExtensionsMutator(extensionMetadataManager, JsonUtil.JSON_MAPPER);

    defaultExtensions = Seq.of(
        "plpgsql",
        "pg_stat_statements",
        "dblink",
        "plpython3u",
        "timescaledb")
        .map(this::getDefaultExtension)
        .collect(ImmutableList.toImmutableList());
    when(extensionMetadataManager.getExtensionCandidateAnyVersion(
        any(), any()))
        .then(this::getDefaultExtensionMetadata);
    when(extensionMetadataManager.getExtensionCandidateSameMajorBuild(
        any(),
        argThat(extension -> defaultExtensions.stream()
            .anyMatch(defaultExtension -> defaultExtension.getName()
                .equals(extension.getName())))))
        .then(this::getDefaultExtensionMetadata);
  }

  private StackGresExtensionMetadata getDefaultExtensionMetadata(InvocationOnMock invocation) {
    return new StackGresExtensionMetadata(defaultExtensions.stream()
        .filter(defaultExtension -> defaultExtension.getName()
            .equals(((StackGresClusterExtension) invocation.getArgument(1)).getName()))
        .findAny().get());
  }

  @Test
  void clusterWithoutExtensions_shouldNotDoAnything() {
    review.getRequest().getObject().setStatus(new StackGresDistributedLogsStatus());
    review.getRequest().getObject().getStatus().setPostgresExtensions(new ArrayList<>());
    review.getRequest().getObject().getStatus().getPostgresExtensions().addAll(defaultExtensions);

    final List<JsonPatchOperation> operations = mutator.mutate(review);

    assertTrue(operations.isEmpty());
  }

  @Test
  void clusterWithoutExtensionsAndState_shouldCreateTheStateWithDefaultExtensions() {
    final List<JsonPatchOperation> operations = mutator.mutate(review);

    assertEquals(1, operations.size());
    assertEquals(1, operations.stream().filter(o -> o instanceof AddOperation).count());
  }

  private StackGresClusterInstalledExtension getDefaultExtension(String name) {
    final StackGresClusterInstalledExtension installedExtension = new StackGresClusterInstalledExtension();
    installedExtension.setName(name);
    installedExtension.setPublisher("com.ongres");
    installedExtension.setRepository(OperatorProperty.EXTENSIONS_REPOSITORY_URLS.getString());
    installedExtension.setVersion("1.0.0");
    installedExtension.setPostgresVersion(POSTGRES_MAJOR_VERSION);
    installedExtension.setBuild(BUILD_VERSION);
    return installedExtension;
  }

}