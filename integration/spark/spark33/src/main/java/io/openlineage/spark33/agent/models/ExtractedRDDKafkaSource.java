/*
/* Copyright 2018-2025 contributors to the OpenLineage project
/* SPDX-License-Identifier: Apache-2.0
*/

package io.openlineage.spark33.agent.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@JsonIgnoreProperties(ignoreUnknown = true)
@Value
public class ExtractedRDDKafkaSource {

    public ExtractedRDDKafkaSource(
            @JsonProperty("bootstrapServers") String bootstrapServers,
            @JsonProperty("topics") String topic) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
    }

    String bootstrapServers;
    String topic;
}
