/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.customresource.sgprofile;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.google.common.base.MoreObjects;
import io.fabric8.kubernetes.client.CustomResource;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StackGresProfile extends CustomResource {

  private static final long serialVersionUID = -5276087851826599719L;

  @NotNull(message = "The specification is required")
  @Valid
  private StackGresProfileSpec spec;

  public StackGresProfile() {
    super(StackGresProfileDefinition.KIND);
  }

  public StackGresProfileSpec getSpec() {
    return spec;
  }

  public void setSpec(StackGresProfileSpec spec) {
    this.spec = spec;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .omitNullValues()
        .add("apiVersion", getApiVersion())
        .add("kind", getKind())
        .add("metadata", getMetadata())
        .add("spec", spec)
        .toString();
  }

}