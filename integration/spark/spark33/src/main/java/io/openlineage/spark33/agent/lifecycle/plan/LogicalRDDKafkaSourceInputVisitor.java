/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark33.agent.lifecycle.plan;

import io.openlineage.client.OpenLineage;
import io.openlineage.spark.agent.lifecycle.Rdds;
import io.openlineage.spark.agent.lifecycle.plan.KafkaBootstrapServerResolver;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark.api.QueryPlanVisitor;
import io.openlineage.spark33.agent.models.ExtractedRDDKafkaSource;
import io.openlineage.spark33.agent.util.RddKafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.LogicalRDD;
import org.apache.spark.sql.execution.datasources.v2.DataSourceRDD;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link LogicalPlan} visitor that attempts to extract {@link ExtractedRDDKafkaSource}s from a {@link DataSourceRDD}
 * wrapped in a {@link LogicalRDD}.
 */
@Slf4j
public class LogicalRDDKafkaSourceInputVisitor
        extends QueryPlanVisitor<LogicalRDD, OpenLineage.InputDataset> {

    public LogicalRDDKafkaSourceInputVisitor(OpenLineageContext context) {
        super(context);
    }

    @Override
    public boolean isDefinedAt(LogicalPlan x) {
        return (x instanceof LogicalRDD);
    }

    @Override
    public List<OpenLineage.InputDataset> apply(LogicalPlan x) {
        Set<RDD<?>> flattenedRdds = Rdds.flattenRDDs(((LogicalRDD) x).rdd(), new HashSet<>());

        return Rdds.findDataSourceRdds(flattenedRdds).stream()
                .flatMap(RddKafkaUtils::findRDDKafkaSources)
                .distinct()
                .map(p -> {
                    String namespace = KafkaBootstrapServerResolver.resolve(Optional.ofNullable(p.getBootstrapServers()));

                    return inputDataset().getDataset(p.getTopic(), namespace, x.schema());
                }).collect(Collectors.toList());
    }

}
