# Contributing

Use JDK 21 and Maven 3.9 or newer. Run `mvn verify` before opening a pull request. Keep event and Bukkit API calls on the server thread and database I/O on the SQLite worker. Include a reproduction for behavior changes and a forward migration for schema changes. Test SQLite changes against temporary databases and leave server snapshots and runtime logs out of commits.

Use [the testing guide](docs/testing.md) for isolated server runs. Include version and build identifiers with any compatibility results.
