package com.ledger.persistence;

import java.math.BigDecimal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

@ReadingConverter
public class DoubleToBigDecimalConverter implements Converter<Double, BigDecimal> {

    @Override
    public BigDecimal convert(Double source) {
        if (!Double.isFinite(source)) {
            throw new IllegalArgumentException("Stored amount is not a finite BSON double");
        }
        return BigDecimal.valueOf(source);
    }
}
