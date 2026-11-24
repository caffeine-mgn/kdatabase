package pw.binom.db

/**
 * Уровень изоляции транзакции.
 *
 * Не все СУБД поддерживают все уровни. SQLite игнорирует запрошенный уровень
 * и работает в том, что даёт режим журнала (`WAL` даёт читателям снимок);
 * драйвер обязан не падать, а применить ближайший возможный и залогировать
 * расхождение.
 */
enum class TransactionMode {
    SERIALIZABLE,
    REPEATABLE_READ,
    READ_COMMITTED,
    READ_UNCOMMITTED,
}
