/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.dto.pooling;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.stackgres.common.StackGresUtil;

@JsonDeserialize
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@RegisterForReflection
public class PoolingConfigSpec {

  private PoolingConfigPgBouncer pgBouncer;

  public PoolingConfigPgBouncer getPgBouncer() {
    return pgBouncer;
  }

  public void setPgBouncer(PoolingConfigPgBouncer pgBouncer) {
    this.pgBouncer = pgBouncer;
  }

  @Override
  public String toString() {
    return StackGresUtil.toPrettyYaml(this);
  }

}
