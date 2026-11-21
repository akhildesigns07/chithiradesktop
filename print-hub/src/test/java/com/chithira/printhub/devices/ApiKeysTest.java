package com.chithira.printhub.devices;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** API key format, prefix lookup and hash comparison. */
class ApiKeysTest {

    @Test
    void generatedKeyHasPrefixAndMatchesItsHash() {
        ApiKeys.NewKey key = ApiKeys.generate();

        assertThat(key.plain()).matches("chp_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}");
        assertThat(ApiKeys.prefixOf(key.plain())).isEqualTo(key.prefix());
        assertThat(ApiKeys.matches(key.plain(), key.hash())).isTrue();
        assertThat(key.hash()).doesNotContain(key.plain());
    }

    @Test
    void tamperedOrMalformedKeyIsRejected() {
        ApiKeys.NewKey key = ApiKeys.generate();
        String tampered = key.plain().substring(0, key.plain().length() - 1) + "x";

        assertThat(ApiKeys.matches(tampered, key.hash())).isEqualTo(tampered.equals(key.plain()));
        assertThat(ApiKeys.prefixOf("not-a-key")).isNull();
        assertThat(ApiKeys.prefixOf("chp_short_secret")).isNull();
        assertThat(ApiKeys.prefixOf(null)).isNull();
    }
}
