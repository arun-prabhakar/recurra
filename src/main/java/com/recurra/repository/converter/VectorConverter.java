package com.recurra.repository.converter;

import com.pgvector.PGvector;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;

/**
 * JPA converter for pgvector float array to/from PGvector type.
 */
@Slf4j
@Converter(autoApply = true)
public class VectorConverter implements AttributeConverter<float[], PGvector> {

    @Override
    public PGvector convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) {
            return null;
        }
        return new PGvector(attribute);
    }

    @Override
    public float[] convertToEntityAttribute(PGvector dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return dbData.toArray();
        } catch (SQLException e) {
            log.error("Error converting PGvector to float array", e);
            return null;
        }
    }
}
