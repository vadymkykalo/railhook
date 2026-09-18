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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
@TestPropertySource(properties = { "public-bin.enabled=true", "public-bin.max-active=40" })
public class PublicWebhookTesterIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @MockitoBean
    private CaptchaVerifier captchaVerifier;

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
        when(captchaVerifier.verify(any(), anyString())).thenReturn(true);
    }

    /** Relative to the row's own created_at, so the database and the JVM need not share a zone. */
    private void expire(String slug) {
        jdbcTemplate.update("UPDATE public_bins SET expires_at = created_at - INTERVAL '1 second' WHERE slug = ?", slug);
    }

    /** From an address of its own, so the per-address cap is exercised only where it is meant to be. */
    private JsonNode create() throws Exception {
        return create("198.51.100." + NEXT_ADDRESS.getAndIncrement());
    }

    private JsonNode create(String address) throws Exception {
        MvcResult result = mockMvc.perform(createFrom(address))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder createFrom(String address) {
        return post("/api/v1/public/bins")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"captchaToken\":\"token\"}")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                });
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
        mockMvc.perform(createFrom("203.0.113.1")).andExpect(status().isTooManyRequests());
    }

    @Test
    public void oneAddressHoldsAtMostThreeLiveUrls() throws Exception {
        String address = "203.0.113.50";
        create(address);
        create(address);
        String third = create(address).get("slug").asText();
        mockMvc.perform(createFrom(address))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("too_many_active_urls"));

        // An expired one no longer counts.
        expire(third);
        create(address);
    }

    @Test
    public void aFailedChallengeMakesNoUrl() throws Exception {
        when(captchaVerifier.verify(any(), anyString())).thenReturn(false);
        mockMvc.perform(createFrom("203.0.113.60")).andExpect(status().isBadRequest());
    }

    @Test
    public void aUrlKeepsAtMostOneMegabyteOfBodies() throws Exception {
        String slug = create().get("slug").asText();
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(post("/hook/p/" + slug).content("b".repeat(60_000))).andExpect(status().isOk());
        }
        Long stored = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(octet_length(r.body)), 0) FROM public_bin_requests r "
                        + "JOIN public_bins b ON b.id = r.bin_id WHERE b.slug = ?", Long.class, slug);
        assertThat(stored).isLessThanOrEqualTo(1_048_576L).isGreaterThan(900_000L);
        assertThat(read(slug).get("requestCount").asLong()).isEqualTo(20);
    }

    @Test
    public void thePlatformHoldsABoundedNumberOfLiveUrls() throws Exception {
        long live = jdbcTemplate.queryForObject("SELECT count(*) FROM public_bins WHERE expires_at > now()", Long.class);
        int filler = (int) Math.max(0, 40 - live);
        try {
            for (int i = 0; i < filler; i++) {
                jdbcTemplate.update("INSERT INTO public_bins (id, slug, expires_at, creator_ip) "
                        + "VALUES (gen_random_uuid(), ?, now() + INTERVAL '1 hour', '192.0.2.1')", "filler" + i + "x".repeat(10));
            }
            mockMvc.perform(createFrom("203.0.113.70"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("tester_busy"));
        } finally {
            jdbcTemplate.update("DELETE FROM public_bins WHERE slug LIKE 'filler%'");
        }
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
