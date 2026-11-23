# PlaceholderAPI

Установите PlaceholderAPI и оставьте `placeholderapi: true` в `integrations.yml`. GuildMaster регистрирует expansion при запуске.

| Placeholder | Результат |
|---|---|
| `%guild_name%` | Название гильдии игрока |
| `%guild_leader%` | Последнее известное имя лидера |
| `%guild_members%` | Количество участников |
| `%guild_online%` | Количество участников онлайн |
| `%guild_description%` | Описание гильдии |
| `%guild_role%` | Префикс и имя ранга |
| `%guild_alliances%` | Количество подтверждённых союзов |
| `%guild_home%` | Мир, в котором расположен дом |
| `%guild_level%` | Уровень гильдии |
| `%guild_kd%` | Средний K/D подходящих участников |
| `%guild_activity%` | Среднее время активности за период |
| `%guild_score%` | Отформатированный рейтинг |
| `%guild_position%` | Позиция гильдии в рейтинге |

По умолчанию доступны `%top1_guilds%` — `%top10_guilds%`. Для каждого места также доступны суффиксы `guilds_score`, `guilds_members`, `guilds_kd` и `guilds_activity`. Количество мест настраивается через `ranking.yml: places`.

В `placeholders.aliases` можно сопоставить пользовательский идентификатор с каноническим полем. Например, `{ title: name }` позволяет `%guild_title%` возвращать то же значение, что `%guild_name%`. Значения `unavailable` и `disabled` задают текст для пустых или отключённых данных.

Если идентификатор свободен, expansion также поддерживает алиасы VaelisKRL: `%vaeliskrl_guild%`, `%vaeliskrl_guild_leader%`, `%vaeliskrl_guild_members%`, `%vaeliskrl_guild_online%` и `%vaeliskrl_guild_alliances%`.
