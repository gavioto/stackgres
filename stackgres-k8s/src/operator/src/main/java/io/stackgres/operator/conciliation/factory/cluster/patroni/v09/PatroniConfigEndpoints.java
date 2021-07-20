/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory.cluster.patroni.v09;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.stackgres.common.CdiUtil;
import io.stackgres.common.ClusterStatefulSetEnvVars;
import io.stackgres.common.ClusterStatefulSetPath;
import io.stackgres.common.EnvoyUtil;
import io.stackgres.common.LabelFactory;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.crd.sgcluster.StackGresClusterDistributedLogs;
import io.stackgres.common.crd.sgcluster.StackGresClusterSpec;
import io.stackgres.common.crd.sgpgconfig.StackGresPostgresConfig;
import io.stackgres.operator.conciliation.OperatorVersionBinder;
import io.stackgres.operator.conciliation.cluster.StackGresClusterContext;
import io.stackgres.operator.conciliation.cluster.StackGresVersion;
import io.stackgres.operator.conciliation.factory.cluster.patroni.AbstractPatroniConfigEndpoints;
import io.stackgres.operator.conciliation.factory.cluster.patroni.parameters.Blocklist;
import io.stackgres.operator.conciliation.factory.cluster.patroni.parameters.PostgresDefaultValues;

@Singleton
@OperatorVersionBinder(startAt = StackGresVersion.V09, stopAt = StackGresVersion.V09_LAST)
public class PatroniConfigEndpoints extends AbstractPatroniConfigEndpoints {

  @Inject
  public PatroniConfigEndpoints(JsonMapper objectMapper,
      LabelFactory<StackGresCluster> labelFactory) {
    super(objectMapper, labelFactory);
  }

  public PatroniConfigEndpoints() {
    super(null, null);
    CdiUtil.checkPublicNoArgsConstructorIsCalledToCreateProxy();
  }

  @Override
  protected Map<String, String> getParemters(StackGresClusterContext context,
      StackGresPostgresConfig pgConfig) {
    Map<String, String> params = new HashMap<>(PostgresDefaultValues.getDefaultValues());
    Map<String, String> userParams = pgConfig.getSpec().getPostgresqlConf();
    for (String bl : Blocklist.getBlocklistParameters()) {
      userParams.remove(bl);
    }
    for (Map.Entry<String, String> userParam : userParams.entrySet()) {
      params.put(userParam.getKey(), userParam.getValue());
    }

    params.put("port", String.valueOf(EnvoyUtil.PG_PORT));

    if (isBackupConfigurationPresent(context)) {
      params.put("archive_command",
          "exec-with-env '" + ClusterStatefulSetEnvVars.BACKUP_ENV.value(context
              .getSource()) + "'"
              + " -- wal-g wal-push %p");
    } else {
      params.put("archive_command", "/bin/true");
    }

    if (Optional.ofNullable(context.getSource())
        .map(StackGresCluster::getSpec)
        .map(StackGresClusterSpec::getDistributedLogs)
        .map(StackGresClusterDistributedLogs::getDistributedLogs).isPresent()) {
      params.put("logging_collector", "on");
      params.put("log_destination", "csvlog");
      params.put("log_directory", ClusterStatefulSetPath.PG_LOG_PATH.path());
      params.put("log_filename", "postgres-%M.log");
      params.put("log_rotation_age", "30");
      params.put("log_rotation_size", "0");
      params.put("log_truncate_on_rotation", "on");
    }

    params.put("wal_level", "logical");
    params.put("wal_log_hints", "on");
    params.put("archive_mode", "on");

    return params;
  }

}
