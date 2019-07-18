/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.test;

import io.quarkus.test.junit.SubstrateTest;

@SubstrateTest
public class NativeHealthCheckResourceIT extends HealthCheckResourceTest {

  // Execute the same tests but in native mode.
}
