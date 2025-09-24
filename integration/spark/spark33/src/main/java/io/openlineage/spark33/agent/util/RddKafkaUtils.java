/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark33.agent.util;

import io.openlineage.spark.agent.util.ScalaConversionUtils;
import io.openlineage.spark33.agent.models.ExtractedRDDKafkaSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.spark.rdd.MapPartitionsRDD;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.execution.datasources.v2.DataSourceRDD;
import org.apache.spark.sql.execution.datasources.v2.DataSourceRDDPartition;
import org.apache.spark.sql.kafka010.KafkaBatchInputPartition;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Utility class to extract kafka topics and bootstrap servers from RDD nodes.
 */
@Slf4j
public class RddKafkaUtils {

    public static Stream<ExtractedRDDKafkaSource> findRDDKafkaSources(RDD rdd) {
        return Stream.<RddKafkaExtractor>of(
                        new DataSourceRDDExtractor(),
                        new MapPartitionsRDDExtractor())
                .filter(e -> e.isDefinedAt(rdd))
                .findFirst()
                .orElse(new UnknownRDDExtractor())
                .extract(rdd)
                .filter(p -> p != null);
    }

    static class UnknownRDDExtractor implements RddKafkaExtractor<RDD> {
        @Override
        public boolean isDefinedAt(Object rdd) {
            return true;
        }

        @Override
        public Stream<ExtractedRDDKafkaSource> extract(RDD rdd) {
            log.warn("Unknown RDD class {}", rdd);
            return Stream.empty();
        }
    }

    static class MapPartitionsRDDExtractor implements RddKafkaExtractor<MapPartitionsRDD> {

        @Override
        public boolean isDefinedAt(Object rdd) {
            return rdd instanceof MapPartitionsRDD;
        }

        @Override
        public Stream<ExtractedRDDKafkaSource> extract(MapPartitionsRDD rdd) {
            if (log.isDebugEnabled()) {
                log.debug("Parent RDD: {}", rdd.prev());
            }
            return findRDDKafkaSources(rdd.prev());
        }
    }

    static class DataSourceRDDExtractor implements RddKafkaExtractor<DataSourceRDD> {

        @Override
        public boolean isDefinedAt(Object rdd) {
            return rdd instanceof DataSourceRDD;
        }

        @Override
        public Stream<ExtractedRDDKafkaSource> extract(DataSourceRDD rdd) {
            return Arrays.stream(rdd.getPartitions())
                    .filter(part -> part instanceof DataSourceRDDPartition)
                    .map(part -> (DataSourceRDDPartition) part)
                    .flatMap(part -> ScalaConversionUtils.fromSeq(part.inputPartitions()).stream())
                    .filter(part -> part instanceof KafkaBatchInputPartition)
                    .map(part -> (KafkaBatchInputPartition) part)
                    .map(part -> new ExtractedRDDKafkaSource(
                            part.executorKafkaParams().get(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG).toString(),
                            part.offsetRange().topic()
                    ))
                    .distinct();
        }
    }

    interface RddKafkaExtractor<T extends RDD> {
        boolean isDefinedAt(Object rdd);

        Stream<ExtractedRDDKafkaSource> extract(T rdd);
    }
}
