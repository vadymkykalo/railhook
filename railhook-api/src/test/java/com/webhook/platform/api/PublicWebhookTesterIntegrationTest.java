package com.webhook.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.repository.PublicBinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook tester on the public site: a URL anyone can make without an account, which keeps
 * what is sent to it for a day so a developer can see what a provider really sends.
 *
 * <p>No account means no organization, so none of this is tenant data: the slug is the only
 * identity, as it is for a test endpoint. What it must not become is an open store: it is
 * rate-limited per address, keeps a bounded number of requests of a bounded size, expires, and
 * never shows a credential it was sent.
 */
public class PublicWebhookTesterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PublicBinRepository publicBinRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void allowCaptures() {
        when(redisRateLimiterService.tryAcquireForSlug(anyString(), anyInt())).thenReturn(true);
    }

    /** Relative to the row's own created_at, so the database and the JVM need not share a zone. */
    private void expire(String slug) {
        jdbcTemplate.update("UPDATE public_bins SET expires_at = created_at - INTERVAL '1 second' WHERE slug = ?", slug);
    }

    private JsonNode create() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/public/bins"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode read(String slug) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/public/bins/" + slug))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    public void anyoneCanMakeAUrlThatLastsADay() throws Exception {
        JsonNode bin = create();
        String slug = bin.get("slug").asText();

        assertThat(slug).hasSize(24).matches("[a-z0-9]+");
        assertThat(bin.get("url").asText()).endsWith("/hook/p/" + slug);
        Instant expiresAt = Instant.parse(bin.get("expiresAt").asText());
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofHours(23), Duration.ofHours(25));
    }

    @Test
    public void recordsWhateverIsSentAndShowsItNewestFirst() throws Exception {
        String slug = create().get("slug").asText();

        mockMvc.perform(post("/hook/p/" + slug + "?attempt=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer sk_live_do_not_show")
                        .header("Stripe-Signature", "t=1,v1=abc")
                        .content("{\"type\":\"invoice.paid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(put("/hook/p/" + slug).content("second"))
                .andExpect(status().isOk());

        JsonNode requests = read(slug).get("requests");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).get("method").asText()).isEqualTo("PUT");
        JsonNode first = requests.get(1);
        assertThat(first.get("method").asText()).isEqualTo("POST");
        assertThat(first.get("query").asText()).isEqualTo("attempt=1");
        assertThat(first.get("body").asText()).isEqualTo("{\"type\":\"invoice.paid\"}");
        assertThat(first.get("contentType").asText()).startsWith("application/json");
        // Anyone with the URL can read it, so credentials and signatures are masked, as in every
        // other capture; that the header arrived is what a developer is checking for.
        assertThat(first.get("headers").has("Stripe-Signature")).isTrue();
        assertThat(first.get("headers").toString()).doesNotContain("sk_live_do_not_show");
        assertThat(read(slug).get("requestCount").asLong()).isEqualTo(2);
    }

    @Test
    public void anUnknownOrExpiredUrlIsNotFound() throws Exception {
        mockMvc.perform(post("/hook/p/doesnotexist0000000000000").content("x")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/bins/doesnotexist0000000000000")).andExpect(status().isNotFound());

        String slug = create().get("slug").asText();
        expire(slug);
        mockMvc.perform(post("/hook/p/" + slug).content("x")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/bins/" + slug)).andExpect(status().isNotFound());
    }

    @Test
    public void keepsTheLatestHundredRequests() throws Exception {
        String slug = create().get("slug").asText();
        for (int i = 1; i <= 105; i++) {
            mockMvc.perform(post("/hook/p/" + slug).content("request " + i)).andExpect(status().isOk());
        }
        JsonNode bin = read(slug);
        assertThat(bin.get("requestCount").asLong()).isEqualTo(105);
        assertThat(bin.get("requests")).hasSize(100);
        assertThat(bin.get("requests").get(0).get("body").asText()).isEqualTo("request 105");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM public_bin_requests r JOIN public_bins b ON b.id = r.bin_id WHERE b.slug = ?",
                Long.class, slug)).isEqualTo(100L);
    }

    @Test
    public void keepsTheFirst64KilobytesOfABody() throws Exception {
        String slug = create().get("slug").asText();
        mockMvc.perform(post("/hook/p/" + slug).content("a".repeat(70_000))).andExpect(status().isOk());

        JsonNode request = read(slug).get("requests").get(0);
        assertThat(request.get("body").asText()).hasSize(65_536);
        assertThat(request.get("bodyTruncated").asBoolean()).isTrue();
        assertThat(request.get("sizeBytes").asLong()).isEqualTo(70_000);
    }

    @Test
    public void makingUrlsIsLimitedPerAddress() throws Exception {
        when(authRateLimiterService.allowPublicBin(anyString())).thenReturn(false);
        mockMvc.perform(post("/api/v1/public/bins")).andExpect(status().isTooManyRequests());
    }

    @Test
    public void aFloodIntoOneUrlIsRefused() throws Exception {
        String slug = create().get("slug").asText();
        when(redisRateLimiterService.tryAcquireForSlug(anyString(), anyInt())).thenReturn(false);
        mockMvc.perform(post("/hook/p/" + slug).content("x")).andExpect(status().isTooManyRequests());
    }

    @Test
    public void expiredUrlsAreDeletedWithWhatTheyRecorded() throws Exception {
        String slug = create().get("slug").asText();
        mockMvc.perform(post("/hook/p/" + slug).content("x")).andExpect(status().isOk());
        expire(slug);

        // The repository directly, in a transaction: the scheduled method holds a ShedLock taken
        // when the context started, so calling it again within the minute is skipped.
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> publicBinRepository.deleteExpired(Instant.now()));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM public_bins WHERE slug = ?", Long.class, slug))
                .isZero();
    }
}
