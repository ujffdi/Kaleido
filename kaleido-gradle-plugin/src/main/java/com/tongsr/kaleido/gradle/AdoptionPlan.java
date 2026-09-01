package com.tongsr.kaleido.gradle;

import java.util.Map;
import java.util.TreeMap;

record AdoptionPlan(Map<String, String> values) {
    AdoptionPlan {
        values = Map.copyOf(new TreeMap<>(values));
    }

    String canonicalText() {
        var sorted = new TreeMap<>(values);
        var text = new StringBuilder();
        sorted.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.toString();
    }
}
