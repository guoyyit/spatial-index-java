package com.github.guoyyit.spatialindexjava.api.dto;

public record ScoredObjectDto(SpatialObjectDto object, double distance) {
}
