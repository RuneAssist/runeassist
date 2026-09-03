package com.runeassist.flip.model;

import lombok.*;

@Data
@AllArgsConstructor
@EqualsAndHashCode
public class ItemIdName {
    public final Integer itemId;
    public final String name;

    public String toString() {
        return name;
    }
}
