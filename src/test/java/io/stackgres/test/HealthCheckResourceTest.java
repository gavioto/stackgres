/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.test;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class HealthCheckResourceTest {

  @Test
  public void testHealthLiveEndpoint() {
    given()
        .when().get("/health/live")
        .then()
        .statusCode(200);
  }

  @Test
  public void testHealthReadyEndpoint() {
    given()
        .when().get("/health/ready")
        .then()
        .statusCode(200);
  }

}
