package com.github.guoyyit.spatialindexjava.api.dto;

import java.util.List;

public record BatchPutRequest(List<SpatialObjectDto> objects) {
}
