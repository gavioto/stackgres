/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.rest;

import static io.restassured.RestAssured.when;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.stackgres.common.StackGresComponent;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@QuarkusTest
@TestHTTPEndpoint(VersionsResource.class)
@EnabledIfEnvironmentVariable(named = "QUARKUS_PROFILE", matches = "test")
class VersionsResourceTest {

  @Test
  void get_listOf_postgresql_versions() {
    String[] pgvers = StackGresComponent.POSTGRESQL.getOrderedVersions().toArray(String[]::new);
    when()
        .get("/postgresql")
        .then()
        .statusCode(200)
        .body("postgresql", Matchers.hasItems(pgvers));
  }

}
