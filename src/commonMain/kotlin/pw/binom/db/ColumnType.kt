package pw.binom.db

/**
 * Тип колонки в терминах SQL, независимо от конкретной СУБД.
 *
 * Драйвер (`kpostgres`, `ksqlite`) отдаёт этот тип из [ResultSet.columnType],
 * а ORM (`korm`) через него решает, каким декодером читать значение.
 * Конкретный SQL-тип колонки (`TEXT`, `VARCHAR(255)`, `BIGSERIAL`) — дело
 * драйвера и диалекта ORM, здесь только категория значения.
 */
enum class ColumnType {
    BIT,
    TINYINT,
    SMALLINT,
    INTEGER,
    BIGINT,
    FLOAT,
    REAL,
    DOUBLE,
    NUMERIC,
    DECIMAL,
    CHAR,
    VARCHAR,
    LONGVARCHAR,
    DATE,
    TIME,
    TIMESTAMP,
    BINARY,
    VARBINARY,
    LONGVARBINARY,
    UUID,
    NULL,
    OTHER,
}
