# GuildMaster
### Advanced Guild Management System for Minecraft Servers

**Version:** 1.0.0  
**Developer / Publisher:** VaelisWorld  
**Platform:** Bukkit · Spigot · Paper · Purpur  
**Language:** English / Русский

---

# 🇬🇧 ENGLISH DOCUMENTATION

## 1. Overview

**GuildMaster** is a standalone guild management plugin designed to provide Minecraft servers with a complete guild system.

It brings together guild creation, member management, custom ranks, diplomacy, territory control, progression, and persistent data storage in one plugin.

GuildMaster is designed to give players meaningful reasons to cooperate, compete, build communities, and develop their guilds.

## 2. Features

### Guild Management
- Create and manage guilds.
- Invite players and accept applications.
- Remove members.
- Manage internal guild ranks.
- Transfer guild leadership.

### Diplomacy
- Establish bilateral alliances with other guilds.
- Declare and manage wars.
- Maintain guild relationship history.

### Gameplay
- Set and use a guild home.
- Private guild chat.
- Guild announcements.
- Event history.
- Player glow functionality.

### Territory System
- Claim chunks for your guild.
- Protect claimed territories.
- Protect blocks and player interactions within claimed areas.
- Allow authorized administrators to bypass territory protection.

### Progression
- Guild levels.
- Member capacity upgrades.
- PvP rankings.
- Activity rankings.
- Guild statistics.

### Data and Reliability
- SQLite database storage.
- Automatic database backups.
- Staged database restoration.
- VaelisKRL data import support.

## 3. Installation

1. Make sure your server uses a compatible Bukkit-based platform.
2. Use Java 21 for the tested Minecraft 1.21.x builds.
3. Download `GuildMaster.jar`.
4. Place the JAR file into your server's `plugins` directory.
5. Start or restart your server.
6. Open `plugins/GuildMaster/` and configure the generated YAML files.
7. Use `/guildadmin reload` to apply supported configuration changes.

**Important:** Always create a backup before migrating or restoring database data.

## 4. Commands

| Command | Description |
|---|---|
| `/guild` | Open the main guild interface |
| `/guild help` | Display player command help |
| `/guild create NAME` | Create a guild |
| `/guild invite PLAYER` | Invite a player |
| `/guild sethome` | Set the guild home |
| `/guild home` | Teleport to the guild home |
| `/guildadmin help` | Display administrative help |
| `/guildadmin backup` | Create an SQLite backup |

Additional functionality and command details may depend on the plugin configuration. Use `/guild help` and `/guildadmin help` for the in-game command reference.

## 5. Bukkit Permissions

| Permission | Default | Description |
|---|---|---|
| `guildmaster.use` | true | Access to player commands and menus |
| `guildmaster.admin` | op | Full administrative access |
| `guildmaster.admin.reload` | op | Reload supported settings |
| `guildmaster.admin.info` | op | View plugin information |
| `guildmaster.admin.delete` | op | Administrative guild deletion |
| `guildmaster.admin.migrate` | op | Data migration |
| `guildmaster.admin.debug` | op | Diagnostic tools |
| `guildmaster.admin.backup` | op | Create database backups |
| `guildmaster.admin.restore` | op | Prepare database restoration |
| `guildmaster.admin.payment` | op | Payment-related checks |
| `guildmaster.territory.bypass` | op | Bypass territory protection |

## 6. Guild Ranks

GuildMaster has its own internal permission system.

This system is separate from Bukkit permissions and is configured in:

`plugins/GuildMaster/permissions.yml`

Default roles:

- **Leader** — has access to all available guild actions (`*`).
- **Member** — has access to information, member lists, guild chat, home, glow, statistics, personal history viewing, and leaving the guild.

Custom roles can define:

- `display-name`
- `priority` (1–999)
- `color`
- `prefix`
- `actions`

This allows server owners to create a rank hierarchy that fits their community.

## 7. Database and Backups

GuildMaster uses SQLite by default.

**Database location:**

`plugins/GuildMaster/data/guildmaster.db`

**Backup directory:**

`plugins/GuildMaster/data/backups/`

The administrative restore command prepares the restoration process for the next server startup.

Always keep an independent backup before performing a migration or restoration.

## 8. Optional Integrations

The documented optional integrations are:

- PlaceholderAPI
- Vault
- LuckPerms
- Essentials

These integrations allow GuildMaster to work alongside commonly used server systems.

## 9. Compatibility

GuildMaster is designed for Bukkit-based Minecraft servers.

The included test documentation records checks on Paper, Purpur, and Spigot for Minecraft 1.21.x and selected 26.x versions.

Not every optional integration or large-scale production scenario has been tested. Server owners should test the plugin in a staging environment before deploying it to a production network.

## 10. Credits

**GuildMaster** is developed and published under the **VaelisWorld** project.

Built for communities that want more than just a basic guild command.

---

# 🇷🇺 РУССКАЯ ДОКУМЕНТАЦИЯ

## 1. Описание

**GuildMaster** — самостоятельный плагин управления гильдиями для Minecraft-серверов.

Он объединяет создание гильдий, управление участниками, собственные ранги, дипломатические отношения, контроль территорий, развитие гильдий и надёжное хранение данных.

GuildMaster создан, чтобы игроки могли объединяться в сообщества, развивать свои гильдии, сотрудничать и соревноваться друг с другом.

## 2. Возможности

### Управление гильдиями
- Создание и управление гильдиями.
- Приглашения игроков и заявки на вступление.
- Исключение участников.
- Внутренние ранги и права.
- Передача лидерства.

### Дипломатия
- Заключение двусторонних союзов.
- Объявление и ведение войн.
- История отношений между гильдиями.

### Игровые возможности
- Установка и использование дома гильдии.
- Приватный чат.
- Объявления гильдии.
- История событий.
- Возможность свечения игроков.

### Система территорий
- Захват чанков для гильдии.
- Защита захваченных территорий.
- Защита блоков и взаимодействий игроков.
- Возможность обхода защиты для уполномоченных администраторов.

### Развитие
- Уровни гильдий.
- Улучшение вместимости участников.
- PvP-рейтинг.
- Рейтинг активности.
- Статистика гильдий.

### Данные и надёжность
- Хранение данных в SQLite.
- Автоматические резервные копии.
- Подготовка восстановления базы данных.
- Поддержка импорта данных VaelisKRL.

## 3. Установка

1. Убедитесь, что сервер использует совместимое Bukkit-ядро.
2. Для проверенных сборок Minecraft 1.21.x используйте Java 21.
3. Скачайте файл `GuildMaster.jar`.
4. Поместите его в папку `plugins/`.
5. Запустите или перезапустите сервер.
6. Откройте `plugins/GuildMaster/` и настройте созданные YAML-файлы.
7. Для применения поддерживаемых изменений используйте `/guildadmin reload`.

**Важно:** Перед миграцией или восстановлением базы данных обязательно создайте резервную копию.

## 4. Команды

| Команда | Описание |
|---|---|
| `/guild` | Открыть главное меню гильдий |
| `/guild help` | Справка по командам игроков |
| `/guild create NAME` | Создать гильдию |
| `/guild invite PLAYER` | Пригласить игрока |
| `/guild sethome` | Установить дом гильдии |
| `/guild home` | Телепортироваться домой |
| `/guildadmin help` | Административная справка |
| `/guildadmin backup` | Создать резервную копию SQLite |

Дополнительные возможности и детали команд могут зависеть от конфигурации. Для просмотра справки используйте `/guild help` и `/guildadmin help`.

## 5. Bukkit-права

| Permission | По умолчанию | Назначение |
|---|---|---|
| `guildmaster.use` | true | Доступ к пользовательским командам и меню |
| `guildmaster.admin` | op | Полный административный доступ |
| `guildmaster.admin.reload` | op | Перезагрузка поддерживаемых настроек |
| `guildmaster.admin.info` | op | Информация о плагине |
| `guildmaster.admin.delete` | op | Административное удаление гильдий |
| `guildmaster.admin.migrate` | op | Миграция данных |
| `guildmaster.admin.debug` | op | Диагностика |
| `guildmaster.admin.backup` | op | Создание резервных копий |
| `guildmaster.admin.restore` | op | Подготовка восстановления базы |
| `guildmaster.admin.payment` | op | Проверки, связанные с платежами |
| `guildmaster.territory.bypass` | op | Обход защиты территорий |

## 6. Внутренние ранги гильдий

GuildMaster имеет собственную систему прав внутри гильдии.

Она не связана напрямую с Bukkit permissions и настраивается в файле:

`plugins/GuildMaster/permissions.yml`

Стандартные роли:

- **Leader** — имеет доступ ко всем доступным действиям (`*`).
- **Member** — может просматривать информацию и участников, пользоваться чатом и домом гильдии, свечением, статистикой, просматривать собственную историю и покидать гильдию.

Для пользовательских рангов доступны параметры:

- `display-name` — отображаемое название.
- `priority` — приоритет (1–999).
- `color` — цвет.
- `prefix` — префикс.
- `actions` — список разрешённых действий.

Это позволяет владельцам серверов создавать собственную иерархию рангов.

## 7. База данных и резервные копии

По умолчанию GuildMaster использует SQLite.

**Путь к базе данных:**

`plugins/GuildMaster/data/guildmaster.db`

**Папка резервных копий:**

`plugins/GuildMaster/data/backups/`

Команда административного восстановления подготавливает процесс восстановления к следующему запуску сервера.

Перед любой миграцией или восстановлением рекомендуется сохранить отдельную резервную копию.

## 8. Необязательные интеграции

В документации указаны следующие интеграции:

- PlaceholderAPI
- Vault
- LuckPerms
- Essentials

Они позволяют GuildMaster взаимодействовать с распространёнными системами Minecraft-серверов.

## 9. Совместимость

GuildMaster предназначен для Bukkit-совместимых серверов.

В комплектной документации тестирования указаны проверки Paper, Purpur и Spigot для Minecraft 1.21.x и отдельных версий 26.x.

Не все необязательные интеграции и сценарии с высокой нагрузкой проверены. Перед установкой на основной сервер рекомендуется протестировать плагин на отдельной тестовой копии.

## 10. Разработчик

**GuildMaster** разработан и распространяется в рамках проекта **VaelisWorld**.

Для серверов, которым нужна не просто команда создания гильдии, а полноценная система развития и взаимодействия игроков.

---

**GuildMaster 1.0.0 — VaelisWorld**  
*Build your guild. Shape your world.*  
*Создавай гильдию. Меняй свой мир.*
