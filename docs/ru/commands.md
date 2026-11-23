# Команды и разрешения

Корневые команды по умолчанию: `/guild` (алиасы `/g`, `/guilds`) и `/guildadmin` (алиас `/ga`). Таб-комплитер предлагает доступные подкоманды и онлайн-игроков.

Для игровых команд игроку требуется `guildmaster.use` (по умолчанию `true`). Дополнительно действия внутри гильдии проверяются по внутреннему рангу из `permissions.yml`. Это **не** Bukkit-permissions: это список разрешённых действий для ранга.

## Команды игроков

Все команды ниже вводятся после `/guild`.

| Команда | Назначение | Внутреннее действие ранга |
|---|---|---|
| `/guild`, `help`, `list` | Главное меню, справка и каталог гильдий | Публично |
| `create [NAME] [CURRENCY]` | Создать гильдию; без параметров открывается сценарий создания | Гость |
| `info [GUILD]` | Информация о своей или указанной гильдии | `info` для своей; карточки каталога публичны |
| `members` | Список участников | `members` |
| `description TEXT` | Изменить описание | `description` |
| `rename NAME` | Переименовать гильдию | `rename` |
| `leave` | Выйти через меню подтверждения | `leave` |
| `delete`, `disband` | Архивировать гильдию через подтверждение | Только лидер |
| `transfer PLAYER` | Передать лидерство через подтверждение | Только лидер |
| `confirm TOKEN` | Подтвердить ожидающее личное действие | Повторная проверка прав |
| `invite PLAYER` | Пригласить видимого онлайн-игрока | `invite` |
| `invitations` | Посмотреть входящие приглашения | Приглашённый игрок |
| `accept GUILD`, `deny GUILD` | Принять или отклонить приглашение | Приглашённый игрок |
| `kick PLAYER` | Исключить участника с более низким рангом | `kick` |
| `apply GUILD` | Подать заявку в гильдию | Игрок без членства |
| `applications` | Посмотреть входящие заявки | `applications` |
| `application accept\|deny PLAYER` | Принять или отклонить заявку | `applications` |
| `role PLAYER RANK` | Назначить ранг ниже собственного; `member` снимает пользовательский ранг | `role` |
| `alliance [list\|info GUILD]` | Посмотреть союзы и запросы | `info` |
| `war [list\|info GUILD]` | Посмотреть войны и запросы | `info` |
| `alliance\|war send\|accept\|deny\|remove GUILD` | Управлять двусторонними отношениями | `alliance` или `war` |
| `sethome` | Установить дом гильдии | `sethome` |
| `home` | Телепортироваться домой | `home` |
| `delhome` | Удалить дом | `delhome` |
| `chat TEXT` | Отправить сообщение в приватный чат гильдии | `chat` |
| `history [PAGE]` | Просмотреть историю событий | `history-view-own` |
| `light on\|off` | Включить или выключить личное свечение | `light` |
| `settings [home\|recruiting]` | Просмотреть или изменить настройки | `settings` |
| `top` | Открыть рейтинг | `info` / доступность рейтинга зависит от механики |
| `statistics` | Статистика своей гильдии | `statistics` |
| `announce [TEXT]` | Установить объявление; без текста очистить его | `announce` |
| `upgrade` | Улучшить вместимость | `upgrade` |
| `level` | Посмотреть уровень гильдии | `info` |
| `territory claim\|release\|list` | Захватить, освободить или просмотреть чанки | `territory` для изменений |
| `set centre` | Установить legacy-центр гильдии | `territory` |

## Административные команды

Для `/guildadmin` нужны `guildmaster.admin` или соответствующее `guildmaster.admin.<action>`. Все эти Bukkit-разрешения по умолчанию выданы операторам.

| Команда | Разрешение | Назначение |
|---|---|---|
| `/guildadmin help` | `guildmaster.admin` или доступ к административным действиям | Справка |
| `/guildadmin reload` | `guildmaster.admin.reload` | Проверить и применить перезагружаемые YAML-настройки |
| `/guildadmin info [GUILD]` | `guildmaster.admin.info` | Состояние плагина или сведения о гильдии |
| `/guildadmin delete GUILD [TOKEN]` | `guildmaster.admin.delete` | Архивировать гильдию с подтверждением |
| `/guildadmin migrate check` | `guildmaster.admin.migrate` | Проверить источник и выполнить dry-run импорта |
| `/guildadmin migrate apply [TOKEN]` | `guildmaster.admin.migrate` | Подтвердить импорт |
| `/guildadmin debug` | `guildmaster.admin.debug` | Сервер, версия схемы и число платежей на проверке |
| `/guildadmin backup` | `guildmaster.admin.backup` | Создать резервную копию SQLite |
| `/guildadmin restore FILE [TOKEN]` | `guildmaster.admin.restore` | Подготовить восстановление на следующий запуск |
| `/guildadmin payment` | `guildmaster.admin.payment` | Показать платежи, требующие проверки |
| `/guildadmin payment resolve ID verified` | `guildmaster.admin.payment` | Снять резервирование после проверки провайдера и баланса |

`guildmaster.territory.bypass` позволяет обходить защиту чанков GuildMaster.

## Bukkit-разрешения

| Permission | По умолчанию | Описание |
|---|---|---|
| `guildmaster.use` | `true` | Доступ к пользовательским командам и меню |
| `guildmaster.admin` | `op` | Полный административный доступ |
| `guildmaster.admin.reload` | `op` | Перезагрузка настроек |
| `guildmaster.admin.info` | `op` | Административная информация |
| `guildmaster.admin.delete` | `op` | Удаление/архивация гильдии администратором |
| `guildmaster.admin.migrate` | `op` | Проверка и импорт старой базы |
| `guildmaster.admin.debug` | `op` | Диагностика |
| `guildmaster.admin.backup` | `op` | Резервная копия |
| `guildmaster.admin.restore` | `op` | Подготовка восстановления |
| `guildmaster.admin.payment` | `op` | Проверка и разрешение платежей |
| `guildmaster.territory.bypass` | `op` | Обход защиты территории |

## Внутренние действия рангов

Файл `permissions.yml` управляет не Bukkit-permissions, а правами участников внутри конкретной гильдии. Стандартные ранги:

- `leader`: единственный владелец, приоритет `1000`, все действия (`*`).
- `member`: базовые права `info`, `members`, `chat`, `home`, `light`, `statistics`, `history-view-own`, `leave`.

Пользовательские ранги задаются в `permissions.yml`: `display-name`, `priority` (1–999), `color`, `prefix` и список `actions`. Лидер защищён и всегда сохраняет управление. Ранг можно назначить командой `/guild role PLAYER RANK`.

Действия, используемые системой рангов: `info`, `members`, `description`, `rename`, `leave`, `disband`, `invite`, `kick`, `applications`, `role`, `alliance`, `war`, `sethome`, `home`, `delhome`, `chat`, `history-view-own`, `light`, `settings`, `statistics`, `announce`, `upgrade`, `territory`.

## Примеры

```text
/guild
/guild create Vaelis
/guild invite PlayerName
/guild role PlayerName builder
/guild sethome
/guild home
/guild territory claim
/guildadmin backup
```

Подробная настройка ролей: [configuration.md](../configuration.md#permissionsyml).
