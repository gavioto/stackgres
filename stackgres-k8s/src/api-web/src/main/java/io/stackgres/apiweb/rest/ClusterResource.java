/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.apiweb.rest;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.quarkus.security.Authenticated;
import io.stackgres.apiweb.distributedlogs.DistributedLogsFetcher;
import io.stackgres.apiweb.distributedlogs.DistributedLogsQueryParameters;
import io.stackgres.apiweb.distributedlogs.FullTextSearchQuery;
import io.stackgres.apiweb.distributedlogs.ImmutableDistributedLogsQueryParameters;
import io.stackgres.apiweb.dto.cluster.ClusterDistributedLogs;
import io.stackgres.apiweb.dto.cluster.ClusterDto;
import io.stackgres.apiweb.dto.cluster.ClusterInfoDto;
import io.stackgres.apiweb.dto.cluster.ClusterInitData;
import io.stackgres.apiweb.dto.cluster.ClusterLogEntryDto;
import io.stackgres.apiweb.dto.cluster.ClusterScriptEntry;
import io.stackgres.apiweb.dto.cluster.ClusterScriptFrom;
import io.stackgres.apiweb.dto.cluster.ClusterSpec;
import io.stackgres.apiweb.dto.cluster.ClusterStatsDto;
import io.stackgres.apiweb.resource.ResourceTransactionHandler;
import io.stackgres.common.CdiUtil;
import io.stackgres.common.PatroniUtil;
import io.stackgres.common.StackGresUtil;
import io.stackgres.common.crd.ConfigMapKeySelector;
import io.stackgres.common.crd.SecretKeySelector;
import io.stackgres.common.crd.sgcluster.StackGresCluster;
import io.stackgres.common.resource.CustomResourceFinder;
import io.stackgres.common.resource.CustomResourceScanner;
import io.stackgres.common.resource.ResourceFinder;
import io.stackgres.common.resource.ResourceUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jetbrains.annotations.Nullable;
import org.jooq.lambda.Seq;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;
import org.jooq.lambda.tuple.Tuple4;

@Path("/stackgres/sgcluster")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClusterResource
    extends AbstractRestService<ClusterDto, StackGresCluster> {

  private static final String DEFAULT_SCRIPT_KEY = "script";

  private final CustomResourceScanner<ClusterDto> clusterScanner;
  private final CustomResourceFinder<ClusterDto> clusterFinder;
  private final CustomResourceFinder<ClusterStatsDto> clusterResourceStatsFinder;
  private final DistributedLogsFetcher distributedLogsFetcher;
  private final ResourceTransactionHandler<Secret> secretTransactionHandler;
  private final ResourceTransactionHandler<ConfigMap> configMapTransactionHandler;
  private final ResourceFinder<ConfigMap> configMapFinder;
  private final ResourceFinder<Service> serviceFinder;

  @Inject
  public ClusterResource(
      CustomResourceScanner<ClusterDto> clusterScanner,
      CustomResourceFinder<ClusterDto> clusterFinder,
      CustomResourceFinder<ClusterStatsDto> clusterResourceStatsFinder,
      DistributedLogsFetcher distributedLogsFetcher,
      ResourceTransactionHandler<Secret> secretTransactionHandler,
      ResourceTransactionHandler<ConfigMap> configMapTransactionHandler,
      ResourceFinder<ConfigMap> configMapFinder,
      ResourceFinder<Service> serviceFinder) {
    this.clusterScanner = clusterScanner;
    this.clusterFinder = clusterFinder;
    this.clusterResourceStatsFinder = clusterResourceStatsFinder;
    this.distributedLogsFetcher = distributedLogsFetcher;
    this.secretTransactionHandler = secretTransactionHandler;
    this.configMapTransactionHandler = configMapTransactionHandler;
    this.configMapFinder = configMapFinder;
    this.serviceFinder = serviceFinder;
  }

  public ClusterResource() {
    CdiUtil.checkPublicNoArgsConstructorIsCalledToCreateProxy();
    this.clusterScanner = null;
    this.clusterFinder = null;
    this.clusterResourceStatsFinder = null;
    this.distributedLogsFetcher = null;
    this.secretTransactionHandler = null;
    this.configMapTransactionHandler = null;
    this.configMapFinder = null;
    this.serviceFinder = null;
  }

  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK",
              content = {@Content(
                  mediaType = "application/json",
                  array = @ArraySchema(schema = @Schema(implementation = ClusterDto.class)))})
      })
  @CommonApiResponses
  @Authenticated
  @Override
  public List<ClusterDto> list() {
    return Seq.seq(clusterScanner.getResources())
        .map(this::setConfigMaps)
        .map(this::setInfo)
        .toList();
  }

  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK",
              content = {@Content(
                  mediaType = "application/json",
                  schema = @Schema(implementation = ClusterDto.class))})
      })
  @CommonApiResponses
  @Authenticated
  @Override
  public ClusterDto get(String namespace, String name) {
    return clusterFinder.findByNameAndNamespace(name, namespace)
        .map(this::setConfigMaps)
        .map(this::setInfo)
        .orElseThrow(NotFoundException::new);
  }

  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK")
      })
  @CommonApiResponses
  @Authenticated
  @Override
  public void create(ClusterDto resource) {
    Deque<Secret> secretsToCreate = getSecretsToCreate(resource);
    Deque<ConfigMap> configMapsToCreate = getConfigMapsToCreate(resource);

    createSecrets(secretsToCreate,
        () -> createConfigMaps(configMapsToCreate,
            () -> super.create(resource)));
  }

  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK")
      })
  @CommonApiResponses
  @Authenticated
  @Override
  public void update(ClusterDto resource) {
    super.update(resource);
  }

  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK")
      })
  @CommonApiResponses
  @Authenticated
  @Override
  public void delete(ClusterDto resource) {
    super.delete(resource);
  }

  @Nullable
  private ClusterDto setInfo(ClusterDto resource) {
    if (resource.getMetadata() == null) {
      return resource;
    }
    final String namespace = resource.getMetadata().getNamespace();
    final String clusterName = resource.getMetadata().getName();
    final ClusterInfoDto info = new ClusterInfoDto();

    serviceFinder.findByNameAndNamespace(PatroniUtil.readWriteName(clusterName), namespace)
        .ifPresent(service -> info.setPrimaryDns(StackGresUtil.getServiceDnsName(service)));
    serviceFinder.findByNameAndNamespace(PatroniUtil.readOnlyName(clusterName), namespace)
        .ifPresent(service -> info.setReplicasDns(StackGresUtil.getServiceDnsName(service)));

    info.setSuperuserUsername("postgres");
    info.setSuperuserSecretName(clusterName);
    info.setSuperuserPasswordKey("superuser-password");

    resource.setInfo(info);
    return resource;
  }

  private ClusterDto setConfigMaps(ClusterDto resource) {
    final String namespace = resource.getMetadata().getNamespace();
    Seq.of(Optional.ofNullable(resource.getSpec())
        .map(ClusterSpec::getInitData)
        .map(ClusterInitData::getScripts))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .flatMap(Collection::stream)
        .zipWithIndex()
        .filter(t -> t.v1.getScriptFrom() != null
            && t.v1.getScriptFrom().getConfigMapKeyRef() != null)
        .map(script -> extractConfigMapInfo(resource, script))
        .filter(t -> t.v2.v3 != null)
        .grouped(t -> t.v2.v3.getName())
        .flatMap(t -> {
          Optional<Map<String, String>> configMaps = configMapFinder
              .findByNameAndNamespace(t.v1, namespace)
              .map(ConfigMap::getData);
          return configMaps
              .map(s -> t.v2.map(tt -> Tuple.tuple(
                  s.get(tt.v2.v3.getKey()), tt.v2.v2)))
              .orElse(Seq.empty());
        })
        .forEach(t -> t.v2.accept(t.v1));
    return resource;
  }

  private void createSecrets(Deque<Secret> secrets, Runnable transaction) {
    Secret secret = secrets.poll();
    if (secret != null) {
      secretTransactionHandler.create(secret, () -> createSecrets(secrets, transaction));
    } else {
      transaction.run();
    }
  }

  private void createConfigMaps(Deque<ConfigMap> configMaps, Runnable transaction) {
    ConfigMap configMap = configMaps.poll();
    if (configMap != null) {
      configMapTransactionHandler.create(configMap,
          () -> createConfigMaps(configMaps, transaction));
    } else {
      transaction.run();
    }
  }

  private Deque<ConfigMap> getConfigMapsToCreate(ClusterDto resource) {
    return Optional.ofNullable(resource.getSpec())
        .map(ClusterSpec::getInitData)
        .map(ClusterInitData::getScripts)
        .map(clusterScriptEntries -> Seq.zipWithIndex(clusterScriptEntries)
            .filter(entry -> entry.v1.getScriptFrom() != null)
            .filter(entry -> entry.v1.getScriptFrom().getConfigMapScript() != null)
            .map(tuple -> {
              ClusterScriptFrom clusterScriptFrom = tuple.v1.getScriptFrom();
              ConfigMapKeySelector configMapKeyRef = clusterScriptFrom.getConfigMapKeyRef();
              final String configMapScript = clusterScriptFrom.getConfigMapScript();
              if (configMapKeyRef != null) {
                return new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(configMapKeyRef.getName())
                    .withNamespace(resource.getMetadata().getNamespace())
                    .endMetadata()
                    .withData(ImmutableMap.of(configMapKeyRef.getKey(),
                        configMapScript))
                    .build();
              } else {
                final String configMapName = scriptResourceName(resource, tuple);
                configMapKeyRef = new ConfigMapKeySelector();
                configMapKeyRef.setName(configMapName);
                configMapKeyRef.setKey(DEFAULT_SCRIPT_KEY);
                clusterScriptFrom.setConfigMapKeyRef(configMapKeyRef);
                return new ConfigMapBuilder()
                    .withNewMetadata()
                    .withName(configMapName)
                    .withNamespace(resource.getMetadata().getNamespace())
                    .endMetadata()
                    .withData(ImmutableMap.of(DEFAULT_SCRIPT_KEY,
                        configMapScript))
                    .build();
              }
            }).collect(Collectors.toCollection(ArrayDeque::new)))
        .orElse(new ArrayDeque<>());
  }

  private Deque<Secret> getSecretsToCreate(ClusterDto resource) {
    return Optional
        .ofNullable(resource.getSpec())
        .map(ClusterSpec::getInitData)
        .map(ClusterInitData::getScripts)
        .map(clusterScriptEntries -> Seq.zipWithIndex(clusterScriptEntries)
            .filter(entry -> entry.v1.getScriptFrom() != null)
            .filter(entry -> entry.v1.getScriptFrom().getSecretScript() != null)
            .map(tuple -> {
              ClusterScriptFrom clusterScriptFrom = tuple.v1.getScriptFrom();
              SecretKeySelector secretKeyRef = clusterScriptFrom.getSecretKeyRef();

              final String secretScript = ResourceUtil
                  .encodeSecret(clusterScriptFrom.getSecretScript());

              if (secretKeyRef != null) {
                return new SecretBuilder()
                    .withNewMetadata()
                    .withName(clusterScriptFrom.getSecretKeyRef().getName())
                    .withNamespace(resource.getMetadata().getNamespace())
                    .endMetadata()
                    .withData(ImmutableMap.of(clusterScriptFrom.getSecretKeyRef().getKey(),
                        secretScript))
                    .build();
              } else {
                final String secretName = scriptResourceName(resource, tuple);
                secretKeyRef = new SecretKeySelector();
                secretKeyRef.setName(secretName);
                secretKeyRef.setKey(DEFAULT_SCRIPT_KEY);
                clusterScriptFrom.setSecretKeyRef(secretKeyRef);
                return new SecretBuilder()
                    .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(resource.getMetadata().getNamespace())
                    .endMetadata()
                    .withData(ImmutableMap.of(DEFAULT_SCRIPT_KEY,
                        secretScript))
                    .build();
              }
            }).collect(Collectors.toCollection(ArrayDeque::new)))
        .orElse(new ArrayDeque<>());
  }

  private Tuple2<String, Tuple4<String, Consumer<String>, ConfigMapKeySelector,
      Consumer<ConfigMapKeySelector>>> extractConfigMapInfo(
      ClusterDto resource, Tuple2<ClusterScriptEntry, Long> script) {
    return Tuple.<String, Tuple4<String, Consumer<String>, ConfigMapKeySelector,
        Consumer<ConfigMapKeySelector>>>tuple(
        scriptResourceName(resource, script),
        Tuple.<String, Consumer<String>, ConfigMapKeySelector,
            Consumer<ConfigMapKeySelector>>tuple(
            script.v1.getScriptFrom().getConfigMapScript(),
            script.v1.getScriptFrom()::setConfigMapScript,
            script.v1.getScriptFrom().getConfigMapKeyRef(),
            script.v1.getScriptFrom()::setConfigMapKeyRef));
  }

  private String scriptResourceName(ClusterDto resource, Tuple2<ClusterScriptEntry, Long> tuple) {
    return tuple.v1.getName() != null
        ? tuple.v1.getName()
        : resource.getMetadata().getName() + "-init-script-" + tuple.v2;
  }

  /**
   * Return a {@code ClusterStatus}.
   */
  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK",
              content = {@Content(
                  mediaType = "application/json",
                  schema = @Schema(implementation = ClusterStatsDto.class))})
      })
  @CommonApiResponses
  @GET
  @Path("/stats/{namespace}/{name}")
  @Authenticated
  public ClusterStatsDto stats(@PathParam("namespace") String namespace,
                               @PathParam("name") String name) {
    return clusterResourceStatsFinder.findByNameAndNamespace(name, namespace)
        .orElseThrow(NotFoundException::new);
  }

  /**
   * Query distributed logs and return a list of {@code ClusterLogEntry}.
   */
  @Operation(
      responses = {
          @ApiResponse(responseCode = "200", description = "OK",
              content = {@Content(
                  mediaType = "application/json",
                  array = @ArraySchema(
                      schema = @Schema(implementation = ClusterLogEntryDto.class)))})
      })
  @CommonApiResponses
  @GET
  @Path("/logs/{namespace}/{name}")
  @Authenticated
  public List<ClusterLogEntryDto> logs(
      @PathParam("namespace") String namespace,
      @PathParam("name") String name,
      @QueryParam("records") Integer records,
      @QueryParam("from") String from,
      @QueryParam("to") String to,
      @QueryParam("sort") String sort,
      @QueryParam("text") String text,
      @QueryParam("logType") List<String> logType,
      @QueryParam("podName") List<String> podName,
      @QueryParam("role") List<String> role,
      @QueryParam("errorLevel") List<String> errorLevel,
      @QueryParam("userName") List<String> userName,
      @QueryParam("databaseName") List<String> databaseName,
      @QueryParam("fromInclusive") Boolean fromInclusive) {
    final ClusterDto cluster = clusterFinder.findByNameAndNamespace(name, namespace)
        .orElseThrow(NotFoundException::new);

    final int calculatedRecords = records != null ? records : 50;

    if (calculatedRecords <= 0) {
      throw new BadRequestException("records should be a positive number");
    }

    final Optional<Tuple2<Instant, Integer>> fromTuple;
    final Optional<Tuple2<Instant, Integer>> toTuple;

    if (Optional.ofNullable(cluster.getSpec())
        .map(ClusterSpec::getDistributedLogs)
        .map(ClusterDistributedLogs::getDistributedLogs)
        .isEmpty()) {
      throw new BadRequestException(
          "Distributed logs are not configured for specified cluster");
    }

    final var filters = ImmutableMap.<String, ImmutableList<String>>builder();
    addFilter("logType", logType, filters);
    addFilter("podName", podName, filters);
    addFilter("role", role, filters);
    addFilter("errorLevel", errorLevel, filters);
    addFilter("userName", userName, filters);
    addFilter("databaseName", databaseName, filters);

    try {
      fromTuple = Optional.ofNullable(from)
          .map(s -> s.split(","))
          .map(ss -> Tuple.tuple(ss[0],
              ss.length > 1 ? ss[1] : String.valueOf(Integer.valueOf(0))))
          .map(t -> t.map1(Instant::parse))
          .map(t -> t.map2(Integer::valueOf));
    } catch (Exception ex) {
      throw new BadRequestException("from should be a timestamp"
          + " or a timestamp and an index separated by character ','", ex);
    }

    try {
      toTuple = Optional.ofNullable(to)
          .map(s -> s.split(","))
          .map(ss -> Tuple.tuple(ss[0],
              ss.length > 1 ? ss[1] : String.valueOf(Integer.MAX_VALUE)))
          .map(t -> t.map1(Instant::parse))
          .map(t -> t.map2(Integer::valueOf));
    } catch (Exception ex) {
      throw new BadRequestException("to should be a timestamp"
          + " or a timestamp and an index separated by character ','", ex);
    }

    if (sort != null && !sort.equals("asc") && !sort.equals("desc")) {
      throw new BadRequestException("sort only accept asc or desc values");
    }

    DistributedLogsQueryParameters logs = ImmutableDistributedLogsQueryParameters.builder()
        .cluster(cluster)
        .records(calculatedRecords)
        .fromTimeAndIndex(fromTuple)
        .toTimeAndIndex(toTuple)
        .filters(filters.build())
        .isSortAsc(Objects.equals("asc", sort))
        .fullTextSearchQuery(Optional.ofNullable(text)
            .map(FullTextSearchQuery::new))
        .isFromInclusive(fromInclusive != null && fromInclusive)
        .build();

    return distributedLogsFetcher.logs(logs);
  }

  private void addFilter(String key, List<String> values,
      final Builder<String, ImmutableList<String>> filters) {
    if (values != null && !values.isEmpty()) {
      filters.put(key, values.stream()
          .filter(value -> !value.isEmpty())
          .collect(ImmutableList.toImmutableList()));
    }
  }

}
