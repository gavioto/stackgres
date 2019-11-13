/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.common;

public enum ConfigProperty {
  OPERATOR_NAME("stackgres.operatorName"),
  OPERATOR_NAMESPACE("stackgres.operatorNamespace"),
  OPERATOR_VERSION("stackgres.operatorVersion"),
  CRD_GROUP("stackgres.group"),
  CRD_VERSION("stackgres.crd.version"),
  CONTAINER_BUILD("stackgres.containerBuild"),
  PROMETHEUS_AUTOBIND("stackgres.prometheus.allowAutobind");

  private final String systemProperty;

  ConfigProperty(String systemProperty) {
    this.systemProperty = systemProperty;
  }

  public String property() {
    return name();
  }

  public String systemProperty() {
    return systemProperty;
  }
}