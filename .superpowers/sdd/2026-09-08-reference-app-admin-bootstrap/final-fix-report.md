# Final Fix Report: Empty HR Roles Coverage

## Scope

- Base commit: `0a8bf51`
- Production changes: none
- Test change: parameterized the ordinary-user bootstrap scenario for `Set.of()` and `Set.of("EMPLOYEE")`
- Assertions per input: ordinary user has only `APP_USER`, bootstrap remains unassigned, and the later eligible user receives `APP_ADMIN`

## Baseline verification

Command (from `reference-app/backend`):

```powershell
.\gradlew.bat test --tests '*AppAdminBootstrapIntegrationTest'
```

Output:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources UP-TO-DATE
> Task :testClasses UP-TO-DATE
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
2026-09-08T16:46:54.627+09:00  INFO 28848 --- [reference-bootstrap-test] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-08T16:46:54.629+09:00  INFO 28848 --- [reference-bootstrap-test] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown initiated...
2026-09-08T16:46:54.633+09:00  INFO 28848 --- [reference-bootstrap-test] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown completed.
> Task :test

BUILD SUCCESSFUL in 9s
5 actionable tasks: 1 executed, 4 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

## Fresh focused verification after change

Command (from `reference-app/backend`):

```powershell
.\gradlew.bat test --tests '*AppAdminBootstrapIntegrationTest' --rerun-tasks
```

Output:

```text
> Task :compileJava
> Task :processResources
> Task :classes
> Task :compileTestJava
> Task :processTestResources
> Task :testClasses
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
2026-09-08T16:47:29.117+09:00  INFO 40724 --- [reference-bootstrap-test] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-08T16:47:29.120+09:00  INFO 40724 --- [reference-bootstrap-test] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown initiated...
2026-09-08T16:47:29.124+09:00  INFO 40724 --- [reference-bootstrap-test] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown completed.
> Task :test

BUILD SUCCESSFUL in 11s
5 actionable tasks: 5 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

Result count from `build/test-results/test/TEST-com.sweet.referenceapp.user.AppAdminBootstrapIntegrationTest.xml`:

```text
tests=9, skipped=0, failures=0, errors=0
```

## Self-review

Command (from worktree root):

```powershell
git diff --check
git diff --stat
git status --short
```

Output before staging:

```text
 .../user/AppAdminBootstrapIntegrationTest.java | 19 +++++++++++++++----
 1 file changed, 15 insertions(+), 4 deletions(-)
 M reference-app/backend/src/test/java/com/sweet/referenceapp/user/AppAdminBootstrapIntegrationTest.java
```

`git diff --check` reported no whitespace errors (only the repository's Windows LF-to-CRLF working-copy warning).
