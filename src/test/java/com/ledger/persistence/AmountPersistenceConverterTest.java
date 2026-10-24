package com.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountPersistenceConverterTest {

    private final BigDecimalToDoubleConverter writer = new BigDecimalToDoubleConverter();
    private final DoubleToBigDecimalConverter reader = new DoubleToBigDecimalConverter();

    @Test
    void writesBsonDoubleAndReadsItAsBigDecimal() {
        Double stored = writer.convert(new BigDecimal("123.45"));

        assertThat(stored).isInstanceOf(Double.class).isEqualTo(123.45d);
        assertThat(reader.convert(stored)).isEqualTo(new BigDecimal("123.45"));
    }

    @Test
    void rejectsAmountsOutsideFiniteDoubleRange() {
        assertThatThrownBy(() -> writer.convert(new BigDecimal("1E10000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
