package com.github.guoyyit.spatialindexjava.web.dto;

/**
 * Request/response representation of a spatial object.
 * A point supplies only min coordinates (x,y[,z]); a rectangle supplies min and max.
 */
public final class ObjectDto {
    public String id;
    public String shape; // POINT or RECTANGLE
    public Integer dim;  // 2 or 3
    public Double minX, minY, minZ;
    public Double maxX, maxY, maxZ;
    public String payload;
}
