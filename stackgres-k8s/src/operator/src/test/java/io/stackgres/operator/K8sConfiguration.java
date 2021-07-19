/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator;

import com.ongres.junit.docker.DockerContainer;
import com.ongres.junit.docker.Environment;
import com.ongres.junit.docker.Mount;
import com.ongres.junit.docker.Port;
import com.ongres.junit.docker.WaitFor;

@DockerContainer(
    image = "stackgres/it:14.0",
    arguments = { "/bin/sh", "-c",
        "set -e;"
            + "echo 'K8s cluster started';"
            + "while true; do sleep 1; done" },
    waitFor = @WaitFor(value = "K8s cluster started", timeout = 1_200_000),
    environments = { @Environment(key = "DOCKER_HOST", value = "${DOCKER_HOST}") },
    mounts = {
        @Mount(reference = K8sConfiguration.class,
            path = "/var/run/docker.sock", value = "/var/run/docker.sock", system = true),
    },
    ports = {
        @Port(internal = 8001)
    })
public interface K8sConfiguration {
}
