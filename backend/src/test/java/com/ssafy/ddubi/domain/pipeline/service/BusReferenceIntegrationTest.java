package com.ssafy.ddubi.domain.pipeline.service;

import com.ssafy.ddubi.support.IntegrationTestSupport;
import com.ssafy.ddubi.global.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BusReferenceIntegrationTest extends IntegrationTestSupport {
    @Autowired BusReferenceService service;
    @Autowired MockMvc mvc;
    @Test void immutableRetryAndExactLookup() {
        var request=new BusReferenceService.Upload(List.of(BusReferenceServiceTest.row(60,2,"HOURLY_SOURCE_MEAN")));
        assertEquals(false,service.upload("test-v1",0,request).get("reused"));
        assertEquals(true,service.upload("test-v1",0,request).get("reused"));
        assertThrows(BusinessException.class,()->service.upload("test-v1",0,new BusReferenceService.Upload(List.of(BusReferenceServiceTest.row(70,2,"HOURLY_SOURCE_MEAN")))));
        var result=service.lookup("test-v1",new BusReferenceService.Query("master-v1","SEOUL_BUS","r","a","b",1,2,2,8));
        assertEquals(false,result.get("productionReady"));
        assertEquals(1,((List<?>)result.get("rows")).size());
        assertEquals("REFERENCE_MISSING",service.lookup("test-v1",new BusReferenceService.Query("wrong-master","SEOUL_BUS","r","a","b",1,2,2,8)).get("status"));
    }
    @Test void internalReferenceRejectsMissingKey() throws Exception {
        mvc.perform(post("/internal/bus-references/test-v1/lookup").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
