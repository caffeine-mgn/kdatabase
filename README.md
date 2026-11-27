# kdatabase

Контракты работы с БД и пул соединений для Kotlin Multiplatform.

## Что здесь есть

| Слой | Назначение |
|---|---|
| `Connection`, `PreparedStatement`, `ResultSet` | контракт драйвера: что обязан уметь любой коннектор |
| `SqlValue` | закрытый набор значений параметров |
| `SqlException` и наследники | три случая, от которых зависит поведение пула |
| `ConnectionPool`, `ConnectionPoolImpl` | пул с потолком, очередью и health-check |
| `DatabaseInfo`, `DatabaseEngine` | что за СУБД под соединением |

## Чего здесь нет и почему

**Нет драйверов.** `kpostgres` и `ksqlite` живут отдельно и зависят от этого
репозитория, а не наоборот. Драйвер обязан уметь работать без пула — пул это
надстройка, а не условие работы.

**Нет ORM.** `korm` зависит от этого репозитория.

**Нет диалектов.** Типы колонок и синтаксис — дело ORM и драйвера. Здесь
только то, что одинаково для всех: соединение, запрос, результат, пул.

## Схема зависимостей

```
kdatabase                  ← ничего
  ↑        ↑        ↑
kpostgres  ksqlite   korm
  ↑                   ↑
kpostgres-pool    kpostgres-orm
```

Порядок релиза линейный: `kdatabase` → `korm` → драйверы.

## Пул: что важно знать

**Потолок жёсткий.** Открыть сверх `maxConnections` нельзя: вызывающий ждёт.
Пул без верхней границы — не пул, а генератор соединений.

**Битое соединение выбрасывается.** `ConnectionLostException` и отмена
корутины помечают соединение непригодным: недочитанный ответ протокола был бы
прочитан следующим клиентом как его собственный.

**Соединение с открытой транзакцией не переиспользуется.** Проверяется через
`isReadyForQuery`: иначе следующий клиент унаследует чужое состояние.

**Время инжектируется.** `PoolConfig.clock` — чтобы тесты управляли уборкой
по простою. `runTest` подменяет `delay`, но не `Clock.System.now()`.

**Без экспериментальных API.** `Channel.isEmpty` и
`CompletableDeferred.getCompleted` требуют opt-in — заменены на `tryReceive`
в цикле и явное поле результата. Предупреждений при сборке нет.

## Подключение

Артефакт публикуется в Maven Central, релиз — по тегу `N.N.N`.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // Контракты БД и пул соединений.
    implementation("pw.binom.db:kdatabase:0.1.0")
}
```

Kotlin Multiplatform подтянет нужный klib сам — координата одна на все таргеты:

| Таргет | Артефакт |
|---|---|
| JVM | `pw.binom.db:kdatabase-jvm` |
| linuxX64 / linuxArm64 | `pw.binom.db:kdatabase-linuxx64` / `-linuxarm64` |
| macosX64 / macosArm64 | `pw.binom.db:kdatabase-macosx64` / `-macosarm64` |
| iOS / tvOS / watchOS | `pw.binom.db:kdatabase-iosarm64` и т.д. |
| mingwX64 | `pw.binom.db:kdatabase-mingwx64` |
| Android Native | `pw.binom.db:kdatabase-androidnativearm64` и т.д. |
| JS (browser / nodejs) | `pw.binom.db:kdatabase-js` |

Отдельных `-sources`/`-javadoc` объявлять не нужно: они приходят вместе с
основным артефактом и нужны только IDE.

**Драйвер в комплект не входит.** `kdatabase` — это контракт и пул; чтобы
работать с конкретной СУБД, добавьте драйвер (`kpostgres`, `ksqlite`) или свой.

## Использование

```kotlin
val pool = ConnectionPoolImpl(
    config = PoolConfig(maxConnections = 4, pingQuery = "SELECT 1"),
    factory = { PostgresConnection.connect(config) },
)

pool.withConnection { conn ->
    conn.transaction { tx ->
        tx.prepare("INSERT INTO t (a) VALUES ($1)").use {
            it.update(SqlValue.Text("значение"))
        }
    }
}
```

## Релиз

Публикация — GitHub Actions на `macos-latest`, триггер `git tag 0.1.0 && git
push --tags`. Тег становится версией артефакта.

**Этот репозиторий публикуется ПЕРВЫМ**: `korm` и адаптеры LMDB зависят от
`pw.binom.db:kdatabase`, поэтому без него цепочка не соберётся.

Секреты репозитория: `GPG_PRIVATE_KEY`, `GPG_PASSWORD`, `GPG_KEY_ID`,
`CENTRAL_USERNAME`, `CENTRAL_PASSWORD`.

Maven Central неизменяем — версию нельзя перезаписать. Перед тегом проверяйте
локально:

```bash
./gradlew publishToMavenLocal -Pversion=X.Y.Z -PskipSigning=true
```

## Тесты

```
./gradlew :jvmTest :linuxX64Test :jsNodeTest
```

12 тестов пула на трёх платформах (JVM, linuxX64, JS/Node). Живая БД не
нужна: пул работает с контрактом `Connection`, а не с конкретной СУБД.

JS-таргет (`browser()` + `nodejs()`) заведён заранее — под будущий драйвер
IndexedDB. Пул и контракты от платформы не зависят, поэтому собираются
как есть.
