/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.dto.backup;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects;
import io.quarkus.runtime.annotations.RegisterForReflection;

@JsonDeserialize
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@RegisterForReflection
public class BackupInformation {

  private String startWalFile;

  private String hostname;
  private String pgData;
  private String postgresVersion;

  private BackupLsn lsn;
  private String systemIdentifier;

  private BackupSize size;
  private Map<String, String> controlData;

  public void setControlData(Map<String, String> controlData) {
    this.controlData = controlData;
  }

  public Map<String, String> getControlData() {
    return controlData;
  }

  public void setSystemIdentifier(String systemIdentifier) {
    this.systemIdentifier = systemIdentifier;
  }

  public String getSystemIdentifier() {
    return systemIdentifier;
  }

  public void setPostgresVersion(String postgresVersion) {
    this.postgresVersion = postgresVersion;
  }

  public String getPostgresVersion() {
    return postgresVersion;
  }

  public void setPgData(String pgData) {
    this.pgData = pgData;
  }

  public String getPgData() {
    return pgData;
  }

  public void setHostname(String hostname) {
    this.hostname = hostname;
  }

  public String getHostname() {
    return hostname;
  }

  public void setStartWalFile(String startWalFile) {
    this.startWalFile = startWalFile;
  }

  public String getStartWalFile() {
    return startWalFile;
  }

  public BackupSize getSize() {
    return size;
  }

  public void setSize(BackupSize size) {
    this.size = size;
  }

  public BackupLsn getLsn() {
    return lsn;
  }

  public void setLsn(BackupLsn lsn) {
    this.lsn = lsn;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("startWalFile", startWalFile)
        .add("hostname", hostname)
        .add("pgData", pgData)
        .add("postgresVersion", postgresVersion)
        .add("lsn", lsn)
        .add("systemIdentifier", systemIdentifier)
        .add("size", size)
        .add("controlData", controlData)
        .toString();
  }
}