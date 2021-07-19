/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.rest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.quarkus.security.Authenticated;
import io.stackgres.apiweb.dto.event.EventDto;
import io.stackgres.apiweb.dto.event.ObjectReference;
import io.stackgres.common.crd.sgdbops.StackGresDbOps;
import io.stackgres.common.resource.ResourceScanner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jooq.lambda.Seq;

@Path("/stackgres/sgdbops/events")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DbOpsEventsResource {

  private final ResourceScanner<EventDto> scanner;
  private final ResourceScanner<Job> jobScanner;
  private final ResourceScanner<Pod> podScanner;

  @Inject
  public DbOpsEventsResource(ResourceScanner<EventDto> scanner,
      ResourceScanner<Job> jobScanner,
      ResourceScanner<Pod> podScanner) {
    this.scanner = scanner;
    this.jobScanner = jobScanner;
    this.podScanner = podScanner;
  }

  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK",
              content = { @Content(
                  mediaType = "application/json",
                  array = @ArraySchema(schema = @Schema(implementation = EventDto.class))) })
      })
  @CommonApiResponses
  @Path("/{namespace}/{name}")
  @GET
  @Authenticated
  public List<EventDto> list(@PathParam("namespace") String namespace,
      @PathParam("name") String name) {
    Map<String, List<ObjectMeta>> relatedResources = new HashMap<>();
    relatedResources.put("Job",
        Seq.seq(jobScanner.findResourcesInNamespace(namespace))
        .filter(job -> job.getMetadata().getOwnerReferences().stream()
            .anyMatch(resourceReference -> resourceReference.getKind().equals(StackGresDbOps.KIND)
                && resourceReference.getName().equals(name)))
        .map(Job::getMetadata)
        .toList());
    relatedResources.put("Pod",
        Seq.seq(podScanner.findResourcesInNamespace(namespace))
        .filter(pod -> pod.getMetadata().getOwnerReferences().stream()
            .anyMatch(resourceReference -> resourceReference.getKind().equals("Job")
                && relatedResources.get("Job").stream().anyMatch(
                   jobMetadata -> jobMetadata.getName().equals(resourceReference.getName()))))
        .map(Pod::getMetadata)
        .toList());
    return Seq.seq(scanner.findResourcesInNamespace(namespace))
        .filter(event -> isDbOpsEvent(event, namespace, name, relatedResources))
        .sorted(this::orderByLastTimestamp)
        .toList();
  }

  private boolean isDbOpsEvent(EventDto event, String namespace, String name,
      Map<String, List<ObjectMeta>> relatedResources) {
    ObjectReference involvedObject = event.getInvolvedObject();
    return involvedObject.getNamespace().equals(namespace)
        && ((involvedObject.getKind().equals(StackGresDbOps.KIND)
            && involvedObject.getName().equals(name))
            || (Optional.ofNullable(relatedResources.get(involvedObject.getKind()))
                .stream().flatMap(List::stream)
                .anyMatch(relatedResource -> relatedResource.getNamespace()
                    .equals(involvedObject.getNamespace())
                    && relatedResource.getName().equals(involvedObject.getName())
                    && relatedResource.getUid().equals(involvedObject.getUid()))));
  }

  private int orderByLastTimestamp(EventDto e1, EventDto e2) {
    Optional<Instant> lt1 = Optional.ofNullable(e1.getLastTimestamp()).map(Instant::parse);
    Optional<Instant> lt2 = Optional.ofNullable(e2.getLastTimestamp()).map(Instant::parse);
    if (lt1.isPresent() && lt2.isPresent()) {
      return lt1.get().compareTo(lt2.get());
    }
    if (lt1.isPresent()) {
      return 1;
    }
    if (lt2.isPresent()) {
      return -1;
    }
    return 0;
  }

}
