package com.tongsr.kaleido.gradle

@JvmRecord
internal data class AdoptionPlan(val values: Map<String, String>) {
    fun canonicalText(): String = buildString {
        values.toSortedMap().forEach { (key, value) ->
            append(key).append('=').append(value).append('\n')
        }
    }
}
