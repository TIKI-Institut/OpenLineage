/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.lifecycle;

import static io.openlineage.client.OpenLineageClientUtils.mergeFacets;
import static io.openlineage.spark.agent.util.ScalaConversionUtils.toScalaFn;

import io.openlineage.client.OpenLineage;
import io.openlineage.client.OpenLineage.DatasetFacet;
import io.openlineage.client.OpenLineage.DatasetFacets;
import io.openlineage.client.OpenLineage.InputDataset;
import io.openlineage.client.OpenLineage.InputDatasetFacet;
import io.openlineage.client.OpenLineage.InputDatasetInputFacets;
import io.openlineage.client.OpenLineage.JobFacet;
import io.openlineage.client.OpenLineage.OutputDataset;
import io.openlineage.client.OpenLineage.OutputDatasetFacet;
import io.openlineage.client.OpenLineage.OutputDatasetOutputFacets;
import io.openlineage.client.OpenLineage.RunEvent;
import io.openlineage.client.OpenLineage.RunEvent.EventType;
import io.openlineage.client.OpenLineage.RunFacet;
import io.openlineage.client.OpenLineage.RunFacets;
import io.openlineage.client.OpenLineage.RunFacetsBuilder;
import io.openlineage.spark.agent.lifecycle.plan.column.ColumnLevelLineageUtils;
import io.openlineage.spark.agent.lifecycle.plan.column.ColumnLevelLineageVisitor;
import io.openlineage.spark.agent.util.FacetUtils;
import io.openlineage.spark.agent.util.PlanUtils;
import io.openlineage.spark.agent.util.RemovePathPatternUtils;
import io.openlineage.spark.agent.util.ScalaConversionUtils;
import io.openlineage.spark.api.CustomFacetBuilder;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark.api.OpenLineageEventHandlerFactory;
import io.openlineage.spark.api.QueryPlanVisitor;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.rdd.RDD;
import org.apache.spark.scheduler.ActiveJob;
import org.apache.spark.scheduler.SparkListenerEvent;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.SparkListenerStageCompleted;
import org.apache.spark.scheduler.Stage;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import scala.Function1;
import scala.PartialFunction;

/**
 * Event handler that accepts various {@link org.apache.spark.scheduler.SparkListener} events and
 * helps build up an {@link RunEvent} by passing event components to partial functions that know how
 * to convert those event components into {@link RunEvent} properties.
 *
 * <p>The event types that can be consumed to generate @link OpenLineage.RunEvent} properties have
 * no common supertype, so the generic argument for the function input is simply {@link Object}. The
 * types of arguments that may be found include
 *
 * <ul>
 *   <li>{@link org.apache.spark.scheduler.StageInfo}
 *   <li>{@link Stage}
 *   <li>{@link RDD}
 *   <li>{@link ActiveJob}
 *   <li>{@link org.apache.spark.sql.execution.QueryExecution}
 * </ul>
 *
 * <p>These components are extracted from various {@link org.apache.spark.scheduler.SparkListener}
 * events, such as {@link SparkListenerStageCompleted}, {@link SparkListenerJobStart}, and {@link
 * org.apache.spark.scheduler.SparkListenerTaskEnd}.
 *
 * <p>{@link RDD} chains will be _flattened_ so each `RDD` dependency is passed to the builders one
 * at a time. This means a builder can directly specify the type of {@link RDD} it handles, such as
 * a {@link org.apache.spark.rdd.HadoopRDD} or a {@link
 * org.apache.spark.sql.execution.datasources.FileScanRDD}, without having to check the dependencies
 * of every {@link org.apache.spark.rdd.MapPartitionsRDD} or {@link
 * org.apache.spark.sql.execution.SQLExecutionRDD}.
 *
 * <p>Any {@link RunFacet}s and {@link JobFacet}s returned by the {@link CustomFacetBuilder}s are
 * appended to the {@link OpenLineage.Run} and {@link OpenLineage.Job}, respectively.
 *
 * <p><b>If</b> any {@link OpenLineage.InputDatasetBuilder}s or {@link
 * OpenLineage.OutputDatasetBuilder}s are returned from the partial functions, the {@link
 * #inputDatasetBuilders} or {@link #outputDatasetBuilders} will be invoked using the same input
 * arguments in order to construct any {@link InputDatasetFacet}s or {@link OutputDatasetFacet}s to
 * the returned dataset. {@link InputDatasetFacet}s and {@link OutputDatasetFacet}s will be attached
 * to <i>any</i> {@link OpenLineage.InputDatasetBuilder} or {@link OpenLineage.OutputDatasetBuilder}
 * found for the event. This is because facets may be constructed from generic information that is
 * not specifically tied to a Dataset. For example, {@link
 * OpenLineage.OutputStatisticsOutputDatasetFacet}s are created from {@link
 * org.apache.spark.executor.TaskMetrics} attached to the last {@link
 * org.apache.spark.scheduler.StageInfo} for a given job execution. However, the {@link
 * OutputDataset} is constructed by reading the {@link LogicalPlan}. There's no way to tie the
 * output metrics in the {@link org.apache.spark.scheduler.StageInfo} to the {@link OutputDataset}
 * in the {@link LogicalPlan} except by inference. Similarly, input metrics can be found in the
 * {@link org.apache.spark.scheduler.StageInfo} for the stage that reads a dataset and the {@link
 * InputDataset} can usually be constructed by walking the {@link RDD} dependency tree for that
 * {@link Stage} and finding a {@link org.apache.spark.sql.execution.datasources.FileScanRDD} or
 * other concrete implementation. But while there is typically only one {@link InputDataset} read in
 * a given stage, there's no guarantee of that and the {@link org.apache.spark.executor.TaskMetrics}
 * in the {@link org.apache.spark.scheduler.StageInfo} won't disambiguate.
 *
 * <p>If a facet needs to be attached to a specific dataset, the user must take care to construct
 * both the Dataset and the Facet in the same builder.
 */
@Slf4j
@AllArgsConstructor
class OpenLineageRunEventBuilder {

  @NonNull private final OpenLineageContext openLineageContext;

  @NonNull
  private final Collection<PartialFunction<Object, List<InputDataset>>> inputDatasetBuilders;

  @NonNull
  private final Collection<PartialFunction<LogicalPlan, List<InputDataset>>>
      inputDatasetQueryPlanVisitors;

  @NonNull
  private final Collection<PartialFunction<Object, List<OutputDataset>>> outputDatasetBuilders;

  @NonNull
  private final Collection<PartialFunction<LogicalPlan, List<OutputDataset>>>
      outputDatasetQueryPlanVisitors;

  @NonNull
  private final Collection<CustomFacetBuilder<?, ? extends DatasetFacet>> datasetFacetBuilders;

  @NonNull
  private final Collection<CustomFacetBuilder<?, ? extends InputDatasetFacet>>
      inputDatasetFacetBuilders;

  @NonNull
  private final Collection<CustomFacetBuilder<?, ? extends OutputDatasetFacet>>
      outputDatasetFacetBuilders;

  @NonNull @Getter
  private final Collection<CustomFacetBuilder<?, ? extends RunFacet>> runFacetBuilders;

  @NonNull @Getter
  private final Collection<CustomFacetBuilder<?, ? extends JobFacet>> jobFacetBuilders;

  @NonNull private final Collection<ColumnLevelLineageVisitor> columnLineageVisitors;

  private final UnknownEntryFacetListener unknownEntryFacetListener =
      UnknownEntryFacetListener.getInstance();
  private final Map<Integer, ActiveJob> jobMap = new HashMap<>();
  private final Map<Integer, Stage> stageMap = new HashMap<>();
  private final OpenLineageRunEventTimeoutExecutor timeouter;

  OpenLineageRunEventBuilder(OpenLineageContext context, OpenLineageEventHandlerFactory factory) {
    this(
        context,
        factory.createInputDatasetBuilder(context),
        factory.createInputDatasetQueryPlanVisitors(context),
        factory.createOutputDatasetBuilder(context),
        factory.createOutputDatasetQueryPlanVisitors(context),
        factory.createDatasetFacetBuilders(context),
        factory.createInputDatasetFacetBuilders(context),
        factory.createOutputDatasetFacetBuilders(context),
        factory.createRunFacetBuilders(context),
        factory.createJobFacetBuilders(context),
        factory.createColumnLevelLineageVisitors(context),
        new OpenLineageRunEventTimeoutExecutor(context));
  }

  /**
   * Add an {@link ActiveJob} and all of its {@link Stage}s to the maps so we can look them up by id
   * later.
   *
   * @param job
   */
  void registerJob(ActiveJob job) {
    jobMap.put(job.jobId(), job);
    stageMap.put(job.finalStage().id(), job.finalStage());
    job.finalStage()
        .parents()
        .forall(
            toScalaFn(
                stage -> {
                  stageMap.put(stage.id(), stage);
                  return true;
                }));
  }

  RunEvent buildRun(OpenLineageRunEventContext context) {
    OpenLineage openLineage = openLineageContext.getOpenLineage();
    List<Object> nodes = context.loadNodes(stageMap, jobMap);
    UUID runId = context.getOverwriteRunId().orElse(openLineageContext.getRunUuid());

    OpenLineage.JobFacets jobFacets =
        timeouter.timeoutJobFacets(
            () -> buildJobFacets(nodes, jobFacetBuilders, context.getJobFacetsBuilder()));
    List<InputDataset> inputDatasets =
        timeouter.timeoutInputDatasets(() -> buildInputDatasets(nodes));
    openLineageContext.getLineageRunStatus().capturedInputs(inputDatasets.size());
    List<OutputDataset> outputDatasets =
        timeouter.timeoutOutputDatasets(() -> buildOutputDatasets(nodes));
    openLineageContext.getLineageRunStatus().capturedOutputs(outputDatasets.size());
    RunFacets runFacets =
        timeouter.timeoutRunFacets(() -> buildRunFacets(context, nodes), openLineage);

    OpenLineage.RunBuilder runBuilder = openLineage.newRunBuilder().runId(runId).facets(runFacets);
    context
        .getRunEventBuilder()
        .eventType(context.getEventType())
        .run(runBuilder.build())
        .job(context.getJobBuilder().facets(jobFacets).build())
        .inputs(RemovePathPatternUtils.removeInputsPathPattern(openLineageContext, inputDatasets))
        .outputs(
            RemovePathPatternUtils.removeOutputsPathPattern(openLineageContext, outputDatasets));

    return context.getRunEventBuilder().build();
  }

  RunFacetsBuilder constructRunFacetsBuilder(
      OpenLineageRunEventContext context, OpenLineage openLineage) {
    RunFacetsBuilder facetBuilder = context.getRunFacetsBuilder();
    if (facetBuilder == null) {
      return openLineage.newRunFacetsBuilder();
    }

    return facetBuilder;
  }

  private List<InputDataset> buildInputDatasets(List<Object> nodes) {
    openLineageContext
        .getQueryExecution()
        .ifPresent(
            qe -> {
              if (log.isDebugEnabled()) {
                log.debug("Traversing optimized plan {}", qe.optimizedPlan().toJSON());
                log.debug("Physical plan executed {}", qe.executedPlan().toJSON());
              }
            });
    if (log.isDebugEnabled()) {
      log.debug(
          "Visiting query plan {} with input dataset builders {}",
          openLineageContext.getQueryExecution(),
          inputDatasetBuilders);
    }

    Function1<LogicalPlan, Collection<InputDataset>> inputVisitor =
        visitLogicalPlan(PlanUtils.merge(inputDatasetQueryPlanVisitors));

    List<InputDataset> datasets =
        Stream.concat(
                buildDatasets(nodes, inputDatasetBuilders),
                openLineageContext
                    .getQueryExecution()
                    .map(
                        qe ->
                            ScalaConversionUtils.fromSeq(qe.optimizedPlan().map(inputVisitor))
                                .stream()
                                .flatMap(Collection::stream)
                                .map(((Class<InputDataset>) InputDataset.class)::cast))
                    .orElse(Stream.empty()))
            .collect(Collectors.toList());
    OpenLineage openLineage = openLineageContext.getOpenLineage();
    openLineageContext.getVisitedNodes().clearVisitedNodes();
    if (!datasets.isEmpty()) {
      Map<String, InputDatasetFacet> inputFacetsMap = new HashMap<>();
      nodes.forEach(
          event -> inputDatasetFacetBuilders.forEach(fn -> fn.accept(event, inputFacetsMap::put)));
      Map<String, DatasetFacets> datasetFacetsMap = new HashMap<>();
      nodes.forEach(
          event -> inputDatasetFacetBuilders.forEach(fn -> fn.accept(event, inputFacetsMap::put)));
      return datasets.stream()
          .map(
              ds ->
                  openLineage
                      .newInputDatasetBuilder()
                      .name(ds.getName())
                      .namespace(ds.getNamespace())
                      .inputFacets(
                          mergeFacets(
                              inputFacetsMap, ds.getInputFacets(), InputDatasetInputFacets.class))
                      .facets(mergeFacets(datasetFacetsMap, ds.getFacets(), DatasetFacets.class))
                      .build())
          .collect(Collectors.toList());
    }
    return datasets;
  }

  /**
   * Returns a {@link Function1} that passes the input {@link LogicalPlan} node to the {@link
   * #unknownEntryFacetListener} if the inputVisitor is defined for the input node.
   *
   * @param inputVisitor
   * @param <D>
   * @return
   */
  private <D> Function1<LogicalPlan, Collection<D>> visitLogicalPlan(
      PartialFunction<LogicalPlan, Collection<D>> inputVisitor) {
    return openLineageContext
        .getMeterRegistry()
        .timer("openlineage.spark.dataset.input.execution.time")
        .record(
            () ->
                ScalaConversionUtils.toScalaFn(
                    node ->
                        inputVisitor
                            .andThen(
                                toScalaFn(
                                    ds -> {
                                      if (!FacetUtils.isFacetDisabled(
                                          openLineageContext, "spark_unknown")) {
                                        unknownEntryFacetListener.accept(node);
                                      }
                                      return ds;
                                    }))
                            .applyOrElse(node, toScalaFn(n -> Collections.emptyList()))));
  }

  private List<OutputDataset> buildOutputDatasets(List<Object> nodes) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Visiting query plan {} with output dataset builders {}",
          openLineageContext.getQueryExecution(),
          outputDatasetBuilders);
    }
    Function1<LogicalPlan, Collection<OutputDataset>> visitor =
        visitLogicalPlan(
            PlanUtils.merge(
                outputDatasetQueryPlanVisitors.stream()
                    .filter(v -> v instanceof QueryPlanVisitor)
                    .filter(v -> !nodes.isEmpty() && nodes.get(0) instanceof SparkListenerEvent)
                    .filter(
                        v ->
                            (((QueryPlanVisitor) v).isDefinedAt((SparkListenerEvent) nodes.get(0))))
                    .collect(Collectors.toList())));
    List<OutputDataset> datasets =
        Stream.concat(
                buildDatasets(nodes, outputDatasetBuilders),
                openLineageContext
                    .getQueryExecution()
                    .map(qe -> visitor.apply(qe.optimizedPlan()))
                    .map(Collection::stream)
                    .orElse(Stream.empty()))
            .collect(Collectors.toList());

    OpenLineage openLineage = openLineageContext.getOpenLineage();

    if (!datasets.isEmpty()) {
      Map<String, OutputDatasetFacet> outputFacetsMap = new HashMap<>();
      nodes.forEach(
          event ->
              outputDatasetFacetBuilders.forEach(fn -> fn.accept(event, outputFacetsMap::put)));
      Map<String, DatasetFacet> datasetFacetsMap = new HashMap<>();
      nodes.forEach(
          event -> datasetFacetBuilders.forEach(fn -> fn.accept(event, datasetFacetsMap::put)));
      return datasets.stream()
          .map(
              ds -> {
                Map<String, DatasetFacet> dsFacetsMap = new HashMap(datasetFacetsMap);
                ColumnLevelLineageUtils.buildColumnLineageDatasetFacet(
                        Optional.of(nodes.get(0))
                            .filter(e -> e instanceof SparkListenerEvent)
                            .map(e -> (SparkListenerEvent) e)
                            .orElse(null),
                        openLineageContext,
                        ds.getFacets().getSchema())
                    .ifPresent(facet -> dsFacetsMap.put("columnLineage", facet));
                return openLineage
                    .newOutputDatasetBuilder()
                    .name(ds.getName())
                    .namespace(ds.getNamespace())
                    .outputFacets(
                        mergeFacets(
                            outputFacetsMap, ds.getOutputFacets(), OutputDatasetOutputFacets.class))
                    .facets(mergeFacets(dsFacetsMap, ds.getFacets(), DatasetFacets.class))
                    .build();
              })
          .collect(Collectors.toList());
    }
    return datasets;
  }

  private <T extends OpenLineage.Dataset> Stream<T> buildDatasets(
      List<Object> nodes, Collection<PartialFunction<Object, List<T>>> builders) {
    return nodes.stream()
        .flatMap(
            event ->
                builders.stream()
                    .filter(pfn -> PlanUtils.safeIsDefinedAt(pfn, event))
                    .map(pfn -> PlanUtils.safeApply(pfn, event))
                    .flatMap(Collection::stream));
  }

  /**
   * Attach facets to a facet container, such as an {@link InputDatasetInputFacets} or an {@link
   * OutputDatasetOutputFacets}. Facets returned by a {@link CustomFacetBuilder} may be attached to
   * a field in the container, such as {@link InputDatasetInputFacets#dataQualityMetrics} or may be
   * attached as a key/value pair in the {@link InputDatasetInputFacets#additionalProperties} map.
   * The serialized JSON does not distinguish between these, but the java class does. The Java class
   * also has some fields, such as the {@link InputDatasetInputFacets#producer} URI, which need to
   * be included in the serialized JSON.
   *
   * <p>This methods will generate a new facet container with properties potentially overridden by
   * the values set by the custom facet generators.
   *
   * @param events
   * @param builders
   * @return
   */
  private OpenLineage.JobFacets buildJobFacets(
      List<Object> events,
      Collection<CustomFacetBuilder<?, ? extends JobFacet>> builders,
      OpenLineage.JobFacetsBuilder jobFacetsBuilder) {
    events.forEach(
        event ->
            builders.forEach(
                fn ->
                    openLineageContext
                        .getMeterRegistry()
                        .timer(
                            "openlineage.spark.facets.job.execution.time",
                            "facet.builder",
                            Optional.ofNullable(fn)
                                .map(Object::getClass)
                                .map(Class::getCanonicalName)
                                .orElse(""))
                        .record(() -> fn.accept(event, jobFacetsBuilder::put))));
    return jobFacetsBuilder.build();
  }

  private RunFacets buildRunFacets(OpenLineageRunEventContext context, List<Object> nodes) {
    RunFacetsBuilder runFacetsBuilder =
        constructRunFacetsBuilder(context, openLineageContext.getOpenLineage());
    runFacetsBuilder.parent(context.getApplicationParentRunFacet());
    if (EventType.COMPLETE.equals(context.getEventType())
        && context.getApplicationParentRunFacet() != null) {
      FacetUtils.attachSmartDebugFacet(openLineageContext, runFacetsBuilder);
    }

    openLineageContext
        .getQueryExecution()
        .filter(qe -> !FacetUtils.isFacetDisabled(openLineageContext, "spark_unknown"))
        .flatMap(
            qe ->
                openLineageContext
                    .getMeterRegistry()
                    .timer("openlineage.spark.unknownFacet.time")
                    .record(() -> unknownEntryFacetListener.build(qe.optimizedPlan())))
        .ifPresent(facet -> runFacetsBuilder.put("spark_unknown", facet));
    unknownEntryFacetListener.clear();

    nodes.forEach(
        event ->
            runFacetBuilders.forEach(
                fn ->
                    openLineageContext
                        .getMeterRegistry()
                        .timer(
                            "openlineage.spark.facets.run.execution.time",
                            "facet.builder",
                            Optional.ofNullable(fn)
                                .map(Object::getClass)
                                .map(Class::getCanonicalName)
                                .orElse(""))
                        .record(
                            () -> {
                              fn.accept(event, runFacetsBuilder::put);
                            })));
    return runFacetsBuilder.build();
  }

  public RunFacets buildRunFacets(SparkListenerEvent event, RunFacetsBuilder builder) {
    runFacetBuilders.forEach(customFacetBuilder -> customFacetBuilder.accept(event, builder::put));
    return builder.build();
  }
}
