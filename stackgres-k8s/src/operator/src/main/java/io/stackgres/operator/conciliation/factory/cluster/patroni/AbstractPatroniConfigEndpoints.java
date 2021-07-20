/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.patroni;

import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableMap;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.PatroniUtil;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgpgconfig.StackGresPostgresConfig;
import io.stackgres.common.patroni.PatroniConfig;
import io.stackgres.common.resource.ResourceUtil;
import io.stackgres.operator.conciliation.ResourceGenerator;
import io.stackgres.operator.conciliation.cluster.StackGresClusterContext;
import org.jetbrains.annotations.NotNull;

public abstract class AbstractPatroniConfigEndpoints
    implements ResourceGenerator<StackGresClusterContext> {

  public static final String PATRONI_CONFIG_KEY = "config";

  private final JsonMapper objectMapper;

  private final LabelFactory<StackGresCluster> labelFactory;

  public AbstractPatroniConfigEndpoints(JsonMapper objectMapper,
                                LabelFactory<StackGresCluster> labelFactory) {
    this.objectMapper = objectMapper;
    this.labelFactory = labelFactory;
  }

  @Override
  public Stream<HasMetadata> generateResource(StackGresClusterContext context) {

    PatroniConfig patroniConf = new PatroniConfig();
    patroniConf.setTtl(30);
    patroniConf.setLoopWait(10);
    patroniConf.setRetryTimeout(10);
    patroniConf.setPostgresql(new PatroniConfig.PostgreSql());
    patroniConf.getPostgresql().setUsePgRewind(true);
    patroniConf.getPostgresql().setParameters(getPostgresConfigValues(context));

    final String patroniConfigJson = objectMapper.valueToTree(patroniConf).toString();

    final Map<String, String> labels = labelFactory.patroniClusterLabels(context.getSource());

    StackGresCluster cluster = context.getSource();
    return Stream.of(new EndpointsBuilder()
        .withNewMetadata()
        .withNamespace(cluster.getMetadata().getNamespace())
        .withName(configName(context))
        .withLabels(labels)
        .withAnnotations(ImmutableMap.of(PATRONI_CONFIG_KEY, patroniConfigJson))
        .endMetadata()
        .build());
  }

  @NotNull
  public Map<String, String> getPostgresConfigValues(StackGresClusterContext context) {
    StackGresPostgresConfig pgConfig = context.getPostgresConfig();

    Map<String, String> params = getParemters(context, pgConfig);

    return params;
  }

  protected abstract Map<String, String> getParemters(StackGresClusterContext context,
      StackGresPostgresConfig pgConfig);

  private String configName(StackGresClusterContext context) {
    final String scope = labelFactory.clusterScope(context.getSource());
    return ResourceUtil.resourceName(scope + PatroniUtil.CONFIG_SERVICE);
  }

  protected boolean isBackupConfigurationPresent(StackGresClusterContext context) {
    return context.getBackupConfig()
        .isPresent();
  }

}
