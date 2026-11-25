package pw.binom.db

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Курсор по результату запроса.
 *
 * ## Два вида чтения
 *
 * * [next] — синхронный шаг по **уже полученным** строкам. Годится для
 *   мелких выборок (`findById`, справочники), где экономить нечего.
 * * [rows] / [rowSequence] — потоковое чтение: строки приходят порциями, в
 *   памяти живёт одна порция. Для больших выборок обязательны, иначе
 *   `SELECT` без `LIMIT` съест память целиком (см. `StreamingResultSet`).
 *
 * ## Почему `next` синхронный
 *
 * У синхронного курсора нет сетевого ожидания: строки уже здесь. Потоковый
 * курсор ждать умеет, поэтому его методы — `suspend`/[Flow]. Смешивать их в
 * одном наборе методов значит обещать синхронность там, где её нет.
 *
 * ## Доступ по индексу и по имени
 *
 * Оба варианта обязательны. ORM читает по индексу (быстрее, порядок известен
 * из сгенерированного SQL), а человек в отладке — по имени. Реализация по
 * имени по умолчанию идёт через [columnIndex], чтобы драйверу не писать
 * двадцать методов.
 */
interface ResultSet : AutoCloseable {
    /** Имена колонок в порядке выдачи. Регистр — как вернула СУБД. */
    val columns: List<String>

    fun columnType(index: Int): ColumnType
    fun columnType(column: String): ColumnType = columnType(columnIndex(column))

    /** Номер колонки по имени. Бросает, если такой нет. */
    fun columnIndex(column: String): Int {
        val i = columns.indexOfFirst { it.equals(column, ignoreCase = true) }
        if (i < 0) throw SqlException("колонка «$column» отсутствует, есть: ${columns.joinToString()}")
        return i
    }

    /**
     * Следующая строка. `false` — строки кончились.
     *
     * Для потокового набора этот метод бросает [UnsupportedOperationException]:
     * там строки достаются через [rows]. Признак — [isStreaming].
     */
    fun next(): Boolean

    /**
     * Потоковое чтение.
     *
     * `true` — набор отдаёт строки порциями, [next] не работает.
     * `false` — набор уже в памяти.
     */
    val isStreaming: Boolean get() = false

    /**
     * Следующая порция строк.
     *
     * Для потокового набора — запрос порции у драйвера. Для обычного —
     * вычитывание из памяти, чтобы вызывающий писал один код на оба случая.
     *
     * Член интерфейса, а не extension-функция: расширения в Kotlin не
     * виртуальны, и драйвер не смог бы подставить свою реализацию — вызов
     * уходил бы в дефолтную, которая бросает исключение.
     *
     * Пустой список — строк больше нет.
     */
    suspend fun fetchRows(size: Int = 512): List<Row> {
        require(size > 0) { "size должен быть > 0" }
        val out = ArrayList<Row>(if (isStreaming) size else 16)
        if (isStreaming) {
            throw UnsupportedOperationException("${this::class.simpleName} не реализовал fetchRows")
        }
        var n = 0
        while (n < size && next()) {
            out += currentRow()
            n++
        }
        return out
    }

    fun isNull(index: Int): Boolean

    fun getString(index: Int): String?
    fun getBoolean(index: Int): Boolean?
    fun getByte(index: Int): Byte?
    fun getShort(index: Int): Short?
    fun getInt(index: Int): Int?
    fun getLong(index: Int): Long?
    fun getFloat(index: Int): Float? = getDouble(index)?.toFloat()
    fun getDouble(index: Int): Double?
    fun getBytes(index: Int): ByteArray?
    fun getInstant(index: Int): Instant?
    fun getUuid(index: Int): Uuid?

    fun isNull(column: String): Boolean = isNull(columnIndex(column))

    fun getString(column: String): String? = getString(columnIndex(column))
    fun getBoolean(column: String): Boolean? = getBoolean(columnIndex(column))
    fun getByte(column: String): Byte? = getByte(columnIndex(column))
    fun getShort(column: String): Short? = getShort(columnIndex(column))
    fun getInt(column: String): Int? = getInt(columnIndex(column))
    fun getLong(column: String): Long? = getLong(columnIndex(column))
    fun getFloat(column: String): Float? = getFloat(columnIndex(column))
    fun getDouble(column: String): Double? = getDouble(columnIndex(column))
    fun getBytes(column: String): ByteArray? = getBytes(columnIndex(column))
    fun getInstant(column: String): Instant? = getInstant(columnIndex(column))
    fun getUuid(column: String): Uuid? = getUuid(columnIndex(column))

    override fun close()
}

/**
 * Одна строка результата, уже прочитанная в память.
 *
 * Нужна для потокового чтения: строку нельзя держать «текущей» в курсоре,
 * пока потребитель её обрабатывает, потому что курсор в это время качает
 * следующую порцию. Поэтому строка отделяется от курсора и живёт сама.
 *
 * ## Почему значения — `Any?`
 *
 * Хранить сырые байты и разбирать их при каждом `getString` значило бы
 * разбирать одно и то же поле дважды. Здесь разбор ленивый: значение
 * приводится при первом обращении и запоминается. Но реализация вправе
 * разобрать сразу — контракт этого не запрещает.
 */
interface Row : AutoCloseable {
    /** Имена колонок — те же, что у породившего набора. */
    val columns: List<String>

    fun columnIndex(column: String): Int {
        val i = columns.indexOfFirst { it.equals(column, ignoreCase = true) }
        if (i < 0) throw SqlException("колонка «$column» отсутствует, есть: ${columns.joinToString()}")
        return i
    }

    fun isNull(index: Int): Boolean
    fun getString(index: Int): String?
    fun getBoolean(index: Int): Boolean?
    fun getInt(index: Int): Int?
    fun getLong(index: Int): Long?
    fun getDouble(index: Int): Double?
    fun getBytes(index: Int): ByteArray?
    fun getInstant(index: Int): Instant?
    fun getUuid(index: Int): Uuid?

    fun isNull(column: String): Boolean = isNull(columnIndex(column))
    fun getString(column: String): String? = getString(columnIndex(column))
    fun getBoolean(column: String): Boolean? = getBoolean(columnIndex(column))
    fun getInt(column: String): Int? = getInt(columnIndex(column))
    fun getLong(column: String): Long? = getLong(columnIndex(column))
    fun getDouble(column: String): Double? = getDouble(columnIndex(column))
    fun getBytes(column: String): ByteArray? = getBytes(columnIndex(column))
    fun getInstant(column: String): Instant? = getInstant(columnIndex(column))
    fun getUuid(column: String): Uuid? = getUuid(columnIndex(column))

    override fun close() = Unit
}

/**
 * Строки как `Flow`.
 *
 * Удобно для цепочек: `rs.rowSequence().filter { ... }.map { ... }.toList()`.
 * Порция запрашивается лениво, при каждом новом подписчике поток идёт с
 * начала — повторный обход невозможен, как и у любого сетевого курсора.
 */
fun ResultSet.rowSequence(size: Int = 512): Flow<Row> = kotlinx.coroutines.flow.flow {
    while (true) {
        val batch = fetchRows(size)
        if (batch.isEmpty()) break
        for (r in batch) emit(r)
        if (batch.size < size) break
    }
}

/**
 * Читает набор целиком, но **порциями**, складывая результат в память.
 *
 * Отличается от `while (next())` только для потокового набора: там это
 * единственный способ получить все строки. Для обычного набора эквивалентно
 * обходу через [ResultSet.next].
 */
suspend fun ResultSet.toList(size: Int = 512): List<Row> {
    if (!isStreaming) {
        val out = mutableListOf<Row>()
        while (next()) out += currentRow()
        return out
    }
    val out = mutableListOf<Row>()
    while (true) {
        val batch = fetchRows(size)
        out += batch
        if (batch.size < size) break
    }
    return out
}

/** Текущая строка обычного набора — как [Row]. */
fun ResultSet.currentRow(): Row = object : Row {
    override val columns: List<String> get() = this@currentRow.columns
    override fun isNull(index: Int) = this@currentRow.isNull(index)
    override fun getString(index: Int) = this@currentRow.getString(index)
    override fun getBoolean(index: Int) = this@currentRow.getBoolean(index)
    override fun getInt(index: Int) = this@currentRow.getInt(index)
    override fun getLong(index: Int) = this@currentRow.getLong(index)
    override fun getDouble(index: Int) = this@currentRow.getDouble(index)
    override fun getBytes(index: Int) = this@currentRow.getBytes(index)
    override fun getInstant(index: Int) = this@currentRow.getInstant(index)
    override fun getUuid(index: Int) = this@currentRow.getUuid(index)
}
