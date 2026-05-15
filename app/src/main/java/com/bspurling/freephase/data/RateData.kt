package com.bspurling.freephase.data

import com.bspurling.freephase.api.RateSlotDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}

@Serializable
data class RateData(
    val tariffSlots: List<Slot>,
    val svtPence: Double?,           // null if SVT lookup hasn't happened yet
    @Serializable(with = InstantSerializer::class) val fetchedAt: Instant,
) {
    /** True iff at least one slot ends >= [referenceLocalEndOfTomorrow]. */
    fun coversThroughTomorrow(referenceLocalEndOfTomorrow: Instant): Boolean =
        tariffSlots.any { it.validTo >= referenceLocalEndOfTomorrow }

    /** Slots from [now] floored to the previous half-hour, forward to the last available. */
    fun slotsFrom(now: Instant): List<Slot> {
        val floor = now.minusSeconds(now.epochSecond % 1800L)
        return tariffSlots.filter { it.validTo > floor }
    }

    val isEmpty: Boolean get() = tariffSlots.isEmpty()
}

@Serializable
data class Slot(
    @Serializable(with = InstantSerializer::class) val validFrom: Instant,
    @Serializable(with = InstantSerializer::class) val validTo: Instant,
    val pence: Double,           // value_inc_vat
) {
    val isFree: Boolean get() = pence == 0.0
}

fun List<RateSlotDto>.toSlots(): List<Slot> = map {
    Slot(
        validFrom = Instant.parse(it.validFrom),
        validTo = Instant.parse(it.validTo ?: it.validFrom),
        pence = it.valueIncVat,
    )
}.sortedBy { it.validFrom }
