package pw.binom.db

/**
 * Соединение с базой.
 *
 * ## Почему `suspend` и нет синхронного двойника
 *
 * Сетевой драйвер ждёт ответа БД — это ожидание. Синхронный вариант в
 * Kotlin/Native невозможен (блокировать нельзя), а в JVM он всё равно был бы
 * обёрткой над `runBlocking`. Один контракт проще двух: нет нужды держать в
 * голове, какой из наборов где.
 *
 * Если вызывающему нужен блокирующий вызов, он делает `runBlocking` у себя —
 * это его решение и его риск.
 *
 * ## Транзакции
 *
 * [beginTransaction]/[commit]/[rollback] — ручные. ORM-удобства живут в
 * расширении [transaction], чтобы драйверу не повторять обработку ошибок.
 */
interface Connection : AutoCloseable {
    val isClosed: Boolean

    val dbInfo: DatabaseInfo

    /** Готово ли соединение принять запрос (не в середине чужой операции). */
    val isReadyForQuery: Boolean

    val transactionMode: TransactionMode

    /**
     * Открыта ли транзакция прямо сейчас.
     *
     * Нужен там, где транзакция — не удобство, а условие корректности.
     * Блокирующее чтение (`FOR UPDATE`) вне транзакции бессмысленно: в
     * Postgres блокировка снимется на первом же `COMMIT`, которого не будет,
     * а точнее — снимется сразу после оператора, потому что неявная
     * транзакция кончилась.
     *
     * Отдельно от [transactionMode], потому что уровень изоляции и факт
     * открытой транзакции — разные вещи: `READ_COMMITTED` бывает и внутри
     * транзакции, и вне её.
     */
    val isInTransaction: Boolean get() = false

    suspend fun setTransactionMode(mode: TransactionMode)

    suspend fun beginTransaction()

    suspend fun commit()

    suspend fun rollback()

    suspend fun prepare(sql: String): PreparedStatement

    /** Быстрый путь без подготовленного запроса. */
    suspend fun executeUpdate(sql: String): Long {
        prepare(sql).use { return it.executeUpdate() }
    }

    suspend fun query(sql: String): ResultSet {
        prepare(sql).use { return it.executeQuery() }
    }

    /**
     * Запрос с потоковой выдачей.
     *
     * `fetchSize` — сколько строк запрашивать за раз. Возвращённый набор
     * имеет `isStreaming == true`, и строки берутся через [fetchRows].
     *
     * ## Требования к драйверу
     *
     * Postgres: соединение **обязано** быть в транзакции, иначе портал
     * умирает на `Sync`, и сервер отдаёт всё разом. Драйвер начинает
     * транзакцию сам, если её нет, и завершает её при [ResultSet.close] —
     * иначе портал останется висеть и удержит снимок.
     *
     * SQLite: `sqlite3_step` и так по одной строке, порционная выдача там
     * сводится к накоплению в буфер.
     *
     * ## Почему не по умолчанию
     *
     * Транзакция на всё время чтения держит снимок: `VACUUM` и `ALTER TABLE`
     * ждут. Для мелких выборок это лишняя плата, поэтому потоковость —
     * осознанный выбор вызывающего, а не поведение по умолчанию.
     */
    suspend fun queryStream(sql: String, fetchSize: Int = 512): ResultSet {
        require(fetchSize > 0) { "fetchSize должен быть > 0, получено $fetchSize" }
        // Драйверы, не умеющие потоково, обязаны хотя бы не притворяться:
        // молчаливая выдача всего в память — ровно та болезнь, от которой
        // уходим.
        throw UnsupportedOperationException(
            "${this::class.simpleName} не поддерживает потоковое чтение",
        )
    }
}

/**
 * Транзакция с гарантированным откатом при исключении.
 *
 * Откат глушит свои ошибки намеренно: если `commit` упал из-за обрыва
 * соединения, `rollback` на том же соединении тоже упадёт, и его исключение
 * затёрло бы настоящую причину.
 */
suspend fun <T> Connection.transaction(block: suspend (Connection) -> T): T {
    beginTransaction()
    try {
        val result = block(this)
        commit()
        return result
    } catch (e: Throwable) {
        runCatching { rollback() }
        throw e
    }
}

/**
 * Закрытие с гарантией освобождения даже при исключении.
 *
 * Своё расширение, а не `kotlin.io.use`: интерфейс `AutoCloseable`, но метод
 * закрытия объявлен здесь, потому что драйверу нужно успеть освободить
 * ресурсы асинхронно.
 */
suspend inline fun <T : Connection, R> T.useConnection(block: (T) -> R): R = try {
    block(this)
} finally {
    close()
}
