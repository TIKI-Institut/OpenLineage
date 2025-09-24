/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark.agent.lifecycle;

import com.google.common.collect.ImmutableList;
import io.openlineage.client.OpenLineage.Dataset;
import io.openlineage.client.OpenLineage.InputDataset;
import io.openlineage.client.OpenLineage.OutputDataset;
import io.openlineage.spark.agent.lifecycle.plan.StreamingDataSourceV2RelationVisitor;
import io.openlineage.spark.agent.lifecycle.plan.WriteToDataSourceV2Visitor;
import io.openlineage.spark.api.DatasetFactory;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark3.agent.lifecycle.plan.CreateTableLikeCommandVisitor;
import io.openlineage.spark3.agent.lifecycle.plan.DropTableVisitor;
import io.openlineage.spark3.agent.lifecycle.plan.RefreshTableCommandVisitor;
import io.openlineage.spark33.agent.lifecycle.plan.LogicalRDDKafkaSourceInputVisitor;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import scala.PartialFunction;

import java.util.List;

class Spark33VisitorFactoryImpl extends BaseVisitorFactory {

  @Override
  public List<PartialFunction<LogicalPlan, List<OutputDataset>>> getOutputVisitors(
      OpenLineageContext context) {
    return ImmutableList.<PartialFunction<LogicalPlan, List<OutputDataset>>>builder()
        .addAll(super.getOutputVisitors(context))
        .add(new CreateTableLikeCommandVisitor(context))
        .add(new DropTableVisitor(context))
        .add(new WriteToDataSourceV2Visitor(context))
        .build();
  }

  @Override
  public List<PartialFunction<LogicalPlan, List<InputDataset>>> getInputVisitors(
      OpenLineageContext context) {
    return ImmutableList.<PartialFunction<LogicalPlan, List<InputDataset>>>builder()
        .addAll(super.getInputVisitors(context))
        .add(new StreamingDataSourceV2RelationVisitor(context))
        .add(new RefreshTableCommandVisitor(context))
        .add(new LogicalRDDKafkaSourceInputVisitor(context))
        .build();
  }

  @Override
  public <D extends Dataset> List<PartialFunction<LogicalPlan, List<D>>> getCommonVisitors(
      OpenLineageContext context, DatasetFactory<D> factory) {
    return super.getBaseCommonVisitors(context, factory);
  }
}
