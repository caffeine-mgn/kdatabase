package pw.binom.db

/**
 * Сведения о соединении, нужные ORM для генерации SQL.
 *
 * ## Зачем кавычки в контракте
 *
 * Postgres приводит незакавыченный идентификатор к нижнему регистру, SQLite
 * сохраняет как написано. Поэтому ORM обязана знать, чем экранировать имя
 * таблицы или колонки, и брать это у драйвера, а не догадываться.
 *
 * `tableNameQuotesStart`/`End` — именно пара, а не один символ: в MySQL это
 * обратная кавычка с обеих сторон, в Postgres и SQLite — двойная. Пара
 * покрывает оба случая без ветвлений в ORM.
 */
interface DatabaseInfo {
    val engine: DatabaseEngine
    val tableNameQuotesStart: String
    val tableNameQuotesEnd: String

    /**
     * Как эта СУБД блокирует строку от параллельной записи.
     *
     * ORM берёт стратегию отсюда и строит SQL по ней. Так один и тот же
     * `findById(lock = true)` работает и в Postgres (`FOR UPDATE`), и в
     * SQLite (`BEGIN IMMEDIATE` без суффикса).
     *
     * По умолчанию [LockStrategy.NONE]: драйвер, не заявивший обратного,
     * блокировку не обеспечивает, и ORM должна на этом падать, а не делать
     * вид, что заблокировала.
     */
    val lockStrategy: LockStrategy get() = LockStrategy.NONE

    /**
     * Диалект: фрагменты SQL, зависящие от СУБД.
     *
     * Собирается из [lockStrategy] по умолчанию, чтобы драйверу не писать
     * его вручную.
     */
    val dialect: SqlDialect
        get() = object : SqlDialect {
            override fun forUpdateClause(): String = when (lockStrategy) {
                LockStrategy.ROW_LOCK -> "FOR UPDATE"
                LockStrategy.TRANSACTION_LOCK, LockStrategy.NONE -> ""
            }

            override val lockStrategy: LockStrategy get() = this@DatabaseInfo.lockStrategy
        }
}
