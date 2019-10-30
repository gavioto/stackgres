/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator;

import com.ongres.junit.docker.DockerContainer;
import com.ongres.junit.docker.Environment;
import com.ongres.junit.docker.Mount;
import com.ongres.junit.docker.WaitFor;

@DockerContainer(
    image = "stackgres/it:latest",
    arguments = { "/bin/sh", "-c",
        "set -e;"
            + "sh /scripts/restart-kind.sh 3;"
            + "seq -s ' ' 10000000 10000910;"
            + "while true; do sleep 1; done" },
    waitFor = @WaitFor(value = "Kind started k8s cluster", timeout = 1_200_000),
    environments = { @Environment(key = "DOCKER_HOST", value = "${DOCKER_HOST}") },
    mounts = {
        @Mount(path = "/scripts", value = "/restart-kind.sh"),
        @Mount(path = "/certs", value = "/certs/server.crt"),
        @Mount(path = "/var/run/docker.sock", value = "/var/run/docker.sock", system = true),
    })
public interface KindConfiguration {
}