package pw.binom.db

/**
 * Что за СУБД под соединением.
 *
 * Нужен ORM и диалекту: типы колонок, способ подстановки параметров и
 * мелочи вроде `RETURNING` различаются. Драйвер обязан вернуть точное
 * значение из [DatabaseInfo.engine].
 */
enum class DatabaseEngine {
    POSTGRESQL,
    SQLITE,
}
