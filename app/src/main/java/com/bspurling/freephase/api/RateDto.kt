package com.bspurling.freephase.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RatePageDto(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<RateSlotDto>,
)

@Serializable
data class RateSlotDto(
    @SerialName("value_exc_vat") val valueExcVat: Double,
    @SerialName("value_inc_vat") val valueIncVat: Double,
    @SerialName("valid_from") val validFrom: String,   // ISO-8601 UTC
    @SerialName("valid_to") val validTo: String? = null,
    @SerialName("payment_method") val paymentMethod: String,
)
