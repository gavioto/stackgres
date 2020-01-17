/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.utils;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import io.stackgres.operatorframework.ValidationFailed;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ValidationUtils {

  public static String getRandomString(int length){
    byte[] array = new byte[length];
    new Random().nextBytes(array);
    return new String(array, StandardCharsets.UTF_8);
  }

  public static void assertValidationFailed(Executable executable, String message, Integer code){
    ValidationFailed validation = assertThrows(ValidationFailed.class, executable);
    assertEquals(message, validation.getResult().getMessage());
    assertEquals(code, validation.getResult().getCode());
  }

  public static void assertValidationFailed(Executable executable, String message){
    ValidationFailed validation = assertThrows(ValidationFailed.class, executable);
    assertEquals(message, validation.getResult().getMessage());
    assertEquals(500, validation.getResult().getCode());
  }
}