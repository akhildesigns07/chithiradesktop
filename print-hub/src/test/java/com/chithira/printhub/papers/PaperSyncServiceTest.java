package com.chithira.printhub.papers;

import com.chithira.printhub.fiery.FieryApiClient;
import com.chithira.printhub.fiery.FieryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Paper Catalog sync: add, update, deactivate. */
@SpringBootTest
@ActiveProfiles("test")
class PaperSyncServiceTest {

    @MockitoBean private FieryApiClient fiery;
    @Autowired private PaperSyncService sync;
    @Autowired private PaperRepository papers;
    @Autowired private FieryProperties properties;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void connectFiery() {
        properties.setEnabled(true);
        properties.setApiKey("key");
        properties.setUsername("user");
        properties.setPassword("password");
    }

    @AfterEach
    void disconnectFiery() { properties.setEnabled(false); }

    @Test
    void addsUpdatesAndDeactivatesPapers() throws Exception {
        when(fiery.getPaperCatalog()).thenReturn(json.readTree("""
                {"data":{"kind":"papercatalog","items":[
                  {"id":9001,"name":"SRA3 350 Gloss","Dimension":"907.1 1275.6","Weight":"350","MediaTypeDetails":"Coated","TrayNo":"3"},
                  {"id":9002,"name":"A4 80","Dimension":"595.3 841.9","Weight":"80","MediaType":"Plain"}]}}"""));
        PaperSyncService.SyncResult first = sync.sync();

        assertThat(first.added()).isEqualTo(2);
        Paper gloss = papers.findByFieryPcmid("9001").orElseThrow();
        assertThat(gloss.getSizeLabel()).isEqualTo("12.6 × 17.72 in (320 × 450 mm)");

        when(fiery.getPaperCatalog()).thenReturn(json.readTree("""
                {"data":{"items":[{"id":9001,"name":"SRA3 350 Gloss NEW","Dimension":"907.1 1275.6","Weight":"350"}]}}"""));
        PaperSyncService.SyncResult second = sync.sync();

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.deactivated()).isGreaterThanOrEqualTo(1);
        assertThat(papers.findByFieryPcmid("9002").orElseThrow().isActive()).isFalse();
        assertThat(papers.findByFieryPcmid("9001").orElseThrow().getName()).isEqualTo("SRA3 350 Gloss NEW");
    }
}
