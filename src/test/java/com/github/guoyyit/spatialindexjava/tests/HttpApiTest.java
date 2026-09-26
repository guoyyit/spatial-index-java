package com.github.guoyyit.spatialindexjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spatial.index.data-dir=target/test-data/http-api",
        "spatial.index.memtable-max-records=50",
        "spatial.index.merge-threshold=4",
        "spatial.index.fsync=false"
})
@AutoConfigureMockMvc
class HttpApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void fullCrudRangeKnnAndDuplicateIds() throws Exception {
        // Insert 2D point.
        mvc.perform(post("/api/spatial/objects")
                        .contentType("application/json")
                        .content("""
                                {"id":"a","shape":"POINT","dim":2,"minX":1,"minY":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("committed"));

        // Duplicate id: upsert to a new position/payload.
        mvc.perform(post("/api/spatial/objects")
                        .contentType("application/json")
                        .content("""
                                {"id":"a","shape":"RECTANGLE","dim":2,
                                 "minX":0,"minY":0,"maxX":3,"maxY":3,"payload":"box"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/spatial/objects")
                        .contentType("application/json")
                        .content("""
                                {"id":"b","shape":"POINT","dim":3,"minX":10,"minY":10,"minZ":10}
                                """))
                .andExpect(status().isOk());

        // Fetch sees the upserted shape/payload.
        mvc.perform(get("/api/spatial/objects/a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shape").value("RECTANGLE"))
                .andExpect(jsonPath("$.payload").value("box"));

        // Inclusive range: the box touches the window boundary at (3,3).
        MvcResult range = mvc.perform(post("/api/spatial/query/range")
                        .contentType("application/json")
                        .content("""
                                {"dim":2,"minX":3,"minY":3,"maxX":5,"maxY":5}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode arr = JSON.readTree(range.getResponse().getContentAsString());
        assertEquals(1, arr.size());
        assertEquals("a", arr.get(0).get("id").asText());

        // Empty result is an empty JSON array.
        mvc.perform(post("/api/spatial/query/range")
                        .contentType("application/json")
                        .content("""
                                {"dim":2,"minX":100,"minY":100,"maxX":101,"maxY":101}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        // kNN 3D.
        MvcResult knn = mvc.perform(post("/api/spatial/query/knn")
                        .contentType("application/json")
                        .content("""
                                {"dim":3,"x":0,"y":0,"z":0,"k":2}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode karr = JSON.readTree(knn.getResponse().getContentAsString());
        assertEquals(2, karr.size());
        assertEquals("a", karr.get(0).get("id").asText());
        assertTrue(karr.get(0).get("distance").asDouble() < karr.get(1).get("distance").asDouble());

        // Delete and repeat.
        mvc.perform(delete("/api/spatial/objects/a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("deleted"));
        mvc.perform(delete("/api/spatial/objects/a"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/spatial/objects/a"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/spatial/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void invalidBodiesReturn400() throws Exception {
        mvc.perform(post("/api/spatial/objects")
                        .contentType("application/json")
                        .content("""
                                {"shape":"POINT","dim":2,"minX":1,"minY":2}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/spatial/objects")
                        .contentType("application/json")
                        .content("""
                                {"id":"x","shape":"WEIRD","dim":2,"minX":1,"minY":2}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/spatial/query/knn")
                        .contentType("application/json")
                        .content("""
                                {"dim":2,"x":0,"y":0,"k":0}
                                """))
                .andExpect(status().isBadRequest());
    }

}

