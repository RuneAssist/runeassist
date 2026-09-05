package com.runeassist.flip.model;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Maps Ares compose JSON onto the local {@link Suggestion} used by GE UI / overlays.
 */
public final class ComposeSuggestionMapper
{
    private ComposeSuggestionMapper()
    {
    }

    /**
     * @return mapped suggestion, or null if the response is missing / has an unknown type
     */
    public static Suggestion toSuggestion(ComposeSuggestionResponse response)
    {
        if (response == null || !response.isOk() || response.getSuggestion() == null)
        {
            return null;
        }
        return toSuggestion(response.getSuggestion(), response.getSource());
    }

    public static Suggestion toSuggestion(ComposeSuggestionResponse.SuggestionDto dto, String source)
    {
        if (dto == null)
        {
            return null;
        }
        SuggestionType type = parseType(dto.getType());
        if (type == null)
        {
            return null;
        }
        Suggestion s = new Suggestion();
        s.setType(type);
        s.setBoxId(dto.getBoxId());
        s.setItemId(dto.getItemId());
        s.setId(dto.getItemId());
        s.setPrice(dto.getPrice());
        s.setQuantity(dto.getQuantity());
        s.setName(dto.getName() != null ? dto.getName() : "");
        s.setMessage(dto.getMessage() != null ? dto.getMessage() : "");
        s.setWhy(dto.getWhy() != null ? dto.getWhy() : "");
        if (dto.getExpectedProfit() != null)
        {
            s.setExpectedProfit(dto.getExpectedProfit());
        }
        if (dto.getExpectedDuration() != null)
        {
            s.setExpectedDuration(dto.getExpectedDuration());
        }
        s.setGeLimit(dto.getGeLimit());
        s.setRemainingLimit(dto.getRemainingLimit());
        s.setLimitKnown(dto.isLimitKnown());
        if (dto.getFlags() != null && !dto.getFlags().isEmpty())
        {
            s.setFlags(new ArrayList<>(dto.getFlags()));
        }
        if (source != null && !source.isEmpty())
        {
            s.setPickSource(source);
        }
        else
        {
            s.setPickSource("ares-compose");
        }
        return s;
    }

    static SuggestionType parseType(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (SuggestionType t : SuggestionType.values())
        {
            if (t.apiValue().equals(key) || t.name().equalsIgnoreCase(raw.trim()))
            {
                return t;
            }
        }
        return null;
    }
}
