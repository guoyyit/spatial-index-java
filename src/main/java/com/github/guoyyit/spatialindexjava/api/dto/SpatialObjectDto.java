package com.github.guoyyit.spatialindexjava.api.dto;

import java.util.List;

/** REST view of a spatial object. min/max have 2 or 3 elements. */
public record SpatialObjectDto(String id, List<Double> min, List<Double> max, String payload) {
}
