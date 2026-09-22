package com.webhook.platform.api.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tunnel's public slug is part of a URL, and it used to be base64 with its two non-alphanumeric
 * characters stripped and the rest cut to twelve. When five of the sixteen encoded characters came
 * out as {@code -} or {@code _}, fewer than twelve were left and the cut threw: the CLI saw a 500
 * on {@code railhook listen} with nothing it could do about it, and it took out a CI run.
 */
class TunnelSlugTest {

    @Test
    void everySlugIsTwelveAlphanumericCharactersAfterTheTunPrefix() {
        Random random = new Random(20260922L);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            String slug = TunnelService.slug(random);
            assertThat(slug).matches("tun-[a-z0-9]{12}");
            seen.add(slug);
        }

        assertThat(seen).as("a slug is a name in a URL, so it may not repeat").hasSize(10_000);
    }

    /** The length is drawn character by character, so no source of randomness can shorten it. */
    @Test
    void aSourceThatKeepsReturningTheFirstCharacterStillGivesTwelve() {
        String slug = TunnelService.slug(new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }
        });

        assertThat(slug).matches("tun-[a-z0-9]{12}");
    }
}
