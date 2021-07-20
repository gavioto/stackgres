/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.dto.pooling;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;

@JsonDeserialize
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@RegisterForReflection
public class PoolingConfigPgBouncerStatus {

  @JsonProperty("pgbouncer.ini")
  @NotNull(message = "pgbouncer.ini is required")
  @Valid
  private List<PgBouncerIniParameter> parameters;

  @JsonProperty("defaultParameters")
  @NotNull(message = "defaultParameters is required")
  private List<String> defaultParameters;

  public List<PgBouncerIniParameter> getParameters() {
    return parameters;
  }

  public void setParameters(List<PgBouncerIniParameter> parameters) {
    this.parameters = parameters;
  }

  public List<String> getDefaultParameters() {
    return defaultParameters;
  }

  public void setDefaultParameters(List<String> defaultParameters) {
    this.defaultParameters = defaultParameters;
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
