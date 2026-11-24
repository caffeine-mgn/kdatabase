package pw.binom.db

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Значение, которое можно передать в параметр запроса.
 *
 * ## Почему собственный тип, а не `Any?`
 *
 * Список `List<Any?>` допускает что угодно — включая объект, который драйвер
 * не умеет сериализовать. Ошибка вылезает в момент биндинга, то есть
 * в середине запроса, и выглядит как «драйвер сломался».
 *
 * Здесь набор закрыт: если значение не входит в [SqlValue], это ошибка
 * компиляции у вызывающего, а не исключение в рантайме.
 *
 * ## Чего здесь нет
 *
 * `BigDecimal`/`BigInteger`: в Kotlin Multiplatform нет стандартного типа.
 * Если понадобятся — добавить `kotlinx-bignum` и ветки в драйверы, но не
 * тянуть их ради одного поля. До тех пор — [String] с явным приведением
 * на стороне диалекта.
 *
 * `LocalDate`/`LocalDateTime`: используем `kotlin.time.Instant` только для
 * момента времени; дата без времени и время без даты пока не нужны ORM.
 * Появятся — добавить ветки, это дешевле, чем тащить kotlinx-datetime
 * в контракт, от которого зависят все.
 */
sealed interface SqlValue {
    data object Null : SqlValue

    data class Text(val value: String) : SqlValue

    data class Bool(val value: Boolean) : SqlValue

    /** 8 бит. Отдельно от [Int], потому что SQLite и Postgres хранят по-разному. */
    data class Int8(val value: Byte) : SqlValue

    data class Int16(val value: Short) : SqlValue

    data class Int32(val value: Int) : SqlValue

    data class Int64(val value: Long) : SqlValue

    data class Float32(val value: Float) : SqlValue

    data class Float64(val value: Double) : SqlValue

    data class Bytes(val value: ByteArray) : SqlValue {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()
    }

    data class Time(val value: Instant) : SqlValue

    data class UuidValue(val value: Uuid) : SqlValue

    companion object {
        /**
         * Приведение из «обычного» Kotlin-значения.
         *
         * Нужно ORM: он читает поля через `kotlinx.serialization` и получает
         * `Any`. Возвращает `null` для неподдерживаемого типа — вызывающий
         * сам решает, бросить или пропустить (ORM бросает).
         */
        fun of(value: Any?): SqlValue? = when (value) {
            null -> Null
            is String -> Text(value)
            is Boolean -> Bool(value)
            is Byte -> Int8(value)
            is Short -> Int16(value)
            is Int -> Int32(value)
            is Long -> Int64(value)
            is Float -> Float32(value)
            is Double -> Float64(value)
            is ByteArray -> Bytes(value)
            is Instant -> Time(value)
            is Uuid -> UuidValue(value)
            else -> null
        }
    }
}
