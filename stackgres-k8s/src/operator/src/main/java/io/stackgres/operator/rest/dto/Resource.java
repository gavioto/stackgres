/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true, value = { "kind", "apiVersion" })
public abstract class Resource {

  private Metadata metadata = new Metadata();

  public Metadata getMetadata() {
    return metadata;
  }

  public void setMetadata(Metadata metadata) {
    this.metadata = metadata;
  }

}
