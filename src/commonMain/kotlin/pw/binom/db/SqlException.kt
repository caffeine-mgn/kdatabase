package pw.binom.db

/**
 * Ошибка работы с БД.
 *
 * ## Почему одна иерархия на всё
 *
 * ORM и пул должны различать три случая, и от этого зависит поведение:
 *
 * * [ConnectionLost] — соединение мертво. Пул выбрасывает его из набора и,
 *   если запрос идемпотентен, вызывающий может повторить на новом.
 * * [ConstraintViolation] — данные не прошли ограничение. Повторять нельзя,
 *   это ошибка вызывающего.
 * * [SqlError] — синтаксис, неизвестная таблица, нехватка прав. Повторять
 *   нельзя, чинится в коде или схеме.
 *
 * Всё, что не разложилось, — [SqlError] с исходным текстом: лучше показать
 * сырую ошибку БД, чем спрятать её за общим «что-то пошло не так».
 */
open class SqlException(
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Соединение потеряно: обрыв TCP, EOF при чтении кадра, закрытие сервером.
 *
 * Пул обязан выбросить такое соединение, а не вернуть в набор. Повторный
 * запрос на нём прочитает остаток предыдущего ответа и вернёт чужие данные —
 * это хуже падения.
 */
class ConnectionLostException(
    message: String? = null,
    cause: Throwable? = null,
) : SqlException(message, cause)

/** Нарушено ограничение: уникальность, внешний ключ, NOT NULL, CHECK. */
class ConstraintViolationException(
    message: String? = null,
    cause: Throwable? = null,
) : SqlException(message, cause)

/** Ошибка в самом SQL или в схеме. Повтор не поможет. */
class SqlErrorException(
    message: String? = null,
    cause: Throwable? = null,
) : SqlException(message, cause)

/**
 * Соединение уже закрыто, но попытку использования сделали.
 *
 * Отдельный тип, потому что это всегда ошибка вызывающего (взял из пула,
 * закрыл, потом использовал), а не сбой БД. Пул по этому исключению знает,
 * что соединение возвращать не надо.
 */
class ConnectionClosedException(
    message: String? = "соединение закрыто",
) : SqlException(message)
