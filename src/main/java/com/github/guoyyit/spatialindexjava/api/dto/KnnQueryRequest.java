package com.github.guoyyit.spatialindexjava.api.dto;

import java.util.List;

public record KnnQueryRequest(List<Double> point, int k) {
}
