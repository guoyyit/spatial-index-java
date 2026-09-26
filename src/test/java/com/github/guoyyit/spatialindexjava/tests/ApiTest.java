package com.github.guoyyit.spatialindexjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void fullCrudAndQueryCycle() throws Exception {
        mvc.perform(put("/api/objects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"a\",\"min\":[1,1],\"max\":[1,1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(put("/api/objects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"b\",\"min\":[5,5],\"max\":[6,6]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/objects/a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("a"))
                .andExpect(jsonPath("$.min[0]").value(1.0));

        mvc.perform(post("/api/query/range").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min\":[0,0],\"max\":[10,10]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mvc.perform(post("/api/query/knn").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"point\":[0,0],\"k\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].object.id").value("a"));

        mvc.perform(delete("/api/objects/a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.existed").value(true));
        mvc.perform(get("/api/objects/a")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/objects/a"))
                .andExpect(jsonPath("$.existed").value(false));

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveObjects").value(1));
    }

    @Test
    void batchPutAndValidation() throws Exception {
        mvc.perform(post("/api/objects/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objects\":["
                                + "{\"id\":\"x\",\"min\":[0,0],\"max\":[0,0]},"
                                + "{\"id\":\"y\",\"min\":[1,1,1],\"max\":[2,2,2]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        // bad coordinate arity -> 400
        mvc.perform(put("/api/objects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"bad\",\"min\":[1],\"max\":[1]}"))
                .andExpect(status().isBadRequest());
        // min > max -> 400
        mvc.perform(put("/api/objects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"bad2\",\"min\":[5,5],\"max\":[1,1]}"))
                .andExpect(status().isBadRequest());
        // empty id -> 400
        mvc.perform(put("/api/objects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"\",\"min\":[0,0],\"max\":[0,0]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminEndpoints() throws Exception {
        mvc.perform(post("/api/admin/flush"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/admin/compact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
