/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.mutation;

import io.stackgres.operator.common.BackupReview;
import io.stackgres.operator.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BackupMutationResourceTest extends MutationResourceTest<BackupReview> {

  @BeforeEach
  void setUp() {
    resource = new BackupMutationResource(pipeline);

    review = JsonUtil
        .readFromJson("backup_allow_request/create.json", BackupReview.class);
  }

}