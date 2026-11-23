package com.lastulas.ica;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class AdicionalesCobradosConverter implements AttributeConverter<List<AdicionalCobrado>, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<AdicionalCobrado> adicionales) {
        if (adicionales == null) return null;
        try {
            return MAPPER.writeValueAsString(adicionales);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("No se pudo guardar el desglose de adicionales cobrados.", e);
        }
    }

    @Override
    public List<AdicionalCobrado> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.of(MAPPER.readValue(json, AdicionalCobrado[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("No se pudo leer el desglose de adicionales cobrados.", e);
        }
    }
}
