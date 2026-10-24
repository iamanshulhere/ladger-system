package com.ledger.persistence;

import java.math.BigDecimal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

@WritingConverter
public class BigDecimalToDoubleConverter implements Converter<BigDecimal, Double> {

    @Override
    public Double convert(BigDecimal source) {
        double value = source.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Amount cannot be represented as a finite BSON double");
        }
        return value;
    }
}
