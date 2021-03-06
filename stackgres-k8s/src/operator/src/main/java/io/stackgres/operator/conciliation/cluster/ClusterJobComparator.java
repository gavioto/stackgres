/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.cluster;

import java.util.Arrays;
import java.util.regex.Pattern;

import javax.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.zjsonpatch.JsonDiff;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.operator.conciliation.ReconciliationScope;
import io.stackgres.operator.conciliation.comparator.StackGresAbstractComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ReconciliationScope(value = StackGresCluster.class, kind = "Job")
@ApplicationScoped
public class ClusterJobComparator extends StackGresAbstractComparator {

  private static final Logger LOGGER = LoggerFactory.getLogger(ClusterJobComparator.class);

  private static final IgnorePatch[] IGNORE_PATCH_PATTERNS = {
      new SimpleIgnorePatch("/spec/template/metadata/labels/controller-uid",
          "add"),
      new SimpleIgnorePatch("/spec/template/metadata/labels/job-name",
          "add"),
      new SimpleIgnorePatch("/spec/template/metadata/labels/job-name",
          "add"),
      new SimpleIgnorePatch("/spec/template/metadata/annotations",
          "add"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/containers/\\d+/resources"),
          "add",
          "{}"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/containers/\\d+/imagePullPolicy"),
          "add",
          "IfNotPresent"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/containers/\\d+/terminationMessagePath"),
          "add",
          "/dev/termination-log"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/containers/\\d+/terminationMessagePolicy"),
          "add",
          "File"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/initContainers/\\d+/resources"),
          "add",
          "{}"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/initContainers/\\d+/terminationMessagePath"),
          "add",
          "/dev/termination-log"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/initContainers/\\d+/terminationMessagePolicy"),
          "add",
          "File"),
      new SimpleIgnorePatch("/spec/template/spec/dnsPolicy",
          "add",
          "ClusterFirst"),
      new SimpleIgnorePatch("/spec/template/spec/serviceAccount",
          "add"),
      new SimpleIgnorePatch("/spec/template/spec/schedulerName",
          "add",
          "default-scheduler"),
      new SimpleIgnorePatch("/spec/template/spec/terminationGracePeriodSeconds",
          "add",
          "30"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/containers/\\d+/env/\\d+/value"),
          "remove"),
      new PatchPattern(Pattern
          .compile("/spec/template/spec/containers/\\d+/env/\\d+/valueFrom/fieldRef/apiVersion"),
          "add",
          "v1"),
      new SimpleIgnorePatch("/spec/selector",
          "add"),
      new SimpleIgnorePatch("/status",
          "add"),
      new PatchPattern(Pattern
          .compile("/metadata/ownerReferences/\\d+/apiVersion"),
          "replace"
      ),
  };

  @Override
  protected IgnorePatch[] getPatchPattersToIgnore() {
    return IGNORE_PATCH_PATTERNS;
  }

  @Override
  public boolean isResourceContentEqual(HasMetadata required, HasMetadata deployed) {

    final JsonNode source = PATCH_MAPPER.valueToTree(required);
    final JsonNode target = PATCH_MAPPER.valueToTree(deployed);
    JsonNode diff = JsonDiff.asJson(source, target);

    int ignore = countPatchesToIgnore(diff);

    final int actualDifferences = diff.size() - ignore;
    if (LOGGER.isDebugEnabled() && actualDifferences != 0) {
      for (JsonNode jsonPatch : diff) {
        JsonPatch patch = new JsonPatch(jsonPatch);
        if (Arrays.stream(getPatchPattersToIgnore())
            .noneMatch(patchPattern -> patchPattern.matches(patch))) {
          LOGGER.debug("Job diff {}", jsonPatch.toPrettyString());
        }
      }
    }

    return actualDifferences == 0;
  }

}
