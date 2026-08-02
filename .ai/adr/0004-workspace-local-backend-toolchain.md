# ADR 0004: Workspace-Local Backend Toolchain

## Status

Accepted

## Context

The local macOS user does not have administrator/sudo access, and Homebrew is not installed. The backend requires Java 21 and Gradle 8.10, while the system Java is Java 11 and Gradle is absent.

## Decision

Install Java 21 and Gradle 8.10 under the workspace root `../.tools` and expose them through `SHRESTA-BE/scripts/be-java` and `SHRESTA-BE/scripts/be-gradle`.

## Consequences

- Backend compile/test/package commands can run without administrator privileges.
- Gradle cache and temp directories are pinned to workspace-local writable directories.
- Commands that open Gradle daemon sockets may require unsandboxed execution in Codex.
- Docker, PostgreSQL server, and Redis server are still not installed because they require either Homebrew/admin rights or a separate Docker Desktop/Colima setup.

## Standard Commands

```bash
./scripts/be-java -version
./scripts/be-gradle -v
./scripts/be-gradle test --no-daemon
./scripts/be-gradle bootJar --no-daemon
```
