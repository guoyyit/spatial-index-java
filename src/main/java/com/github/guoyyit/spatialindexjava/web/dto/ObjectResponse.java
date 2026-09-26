package com.github.guoyyit.spatialindexjava.web.dto;

/**
 * One object as returned by queries.
 */
public record ObjectResponse(String id, String shape, int dim,
                             double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ,
                             String payload, Double distance) {
}
