package edu.cwru.openbench.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CreditsTest {

    @Test
    void keepsTwoDecimalsRoundingHalfUp() {
        assertThat(new Credits(new BigDecimal("1120")).amount()).isEqualTo(new BigDecimal("1120.00"));
        assertThat(new Credits(new BigDecimal("10.005")).amount()).isEqualTo(new BigDecimal("10.01"));
        assertThat(new Credits(new BigDecimal("10.004")).amount()).isEqualTo(new BigDecimal("10.00"));
        assertThat(new Credits(new BigDecimal("-10.005")).amount()).isEqualTo(new BigDecimal("-10.01"));
    }

    @Test
    void roundsRefundsDown() {
        // 25% of 45.00 is exact; 75% of 45.01 is 33.7575, which a Refund rounds down.
        assertThat(Credits.roundedDown(new BigDecimal("11.25"))).isEqualTo(Credits.of("11.25"));
        assertThat(Credits.roundedDown(new BigDecimal("33.7575"))).isEqualTo(Credits.of("33.75"));
    }

    @Test
    void equalsIgnoresTheScaleItWasGivenIn() {
        assertThat(Credits.of("30")).isEqualTo(Credits.of("30.00")).isEqualTo(Credits.of(30));
        assertThat(Credits.of("30").hashCode()).isEqualTo(Credits.of("30.000").hashCode());
    }

    @Test
    void addsSubtractsAndCompares() {
        Credits balance = Credits.of("600.00");
        Credits spent = Credits.of("480.00").plus(Credits.of("120.00"));
        assertThat(balance.minus(spent)).isEqualTo(Credits.ZERO);
        assertThat(balance.minus(spent.plus(Credits.of("0.01"))).isNegative()).isTrue();
        assertThat(Credits.of("30")).isLessThan(Credits.of("30.01"));
    }

    @Test
    void isAJsonNumberWithTwoDecimals() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertThat(json.writeValueAsString(Credits.of(30))).isEqualTo("30.00");
        assertThat(json.readValue("12.5", Credits.class)).isEqualTo(Credits.of("12.50"));
    }
}
