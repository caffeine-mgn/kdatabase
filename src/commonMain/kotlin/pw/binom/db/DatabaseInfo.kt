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
}
