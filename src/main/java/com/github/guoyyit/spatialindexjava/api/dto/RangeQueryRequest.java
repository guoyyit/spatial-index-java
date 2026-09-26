package com.github.guoyyit.spatialindexjava.api.dto;

import java.util.List;

public record RangeQueryRequest(List<Double> min, List<Double> max, String mode, Integer limit) {
}
