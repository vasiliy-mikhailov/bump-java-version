# Dependency families for attempt_2

Anchored to OpenRewrite catalog modules that target Java 21 migration breaking changes.

| family | OR module + recipe | signature in source/pom |
|--------|--------------------|------------------------|
| spring-boot-2 | rewrite-spring / UpgradeSpringBoot_3_3 | spring-boot-starter at 2.x in pom |
| jakarta-ee-javax | rewrite-migrate-java / JakartaEE10 | javax.persistence / javax.servlet / javax.ws / javax.validation imports in src |
| junit4-mockito | rewrite-testing-frameworks / JUnit4to5Migration + MockitoBestPractices | junit:junit:4.x + mockito-core <5 in pom |
| hibernate-5 | rewrite-hibernate / MigrateToHibernate62 | hibernate-core or hibernate-entitymanager at 5.x in pom (not via Boot) |

Target: 8 distinct-owner samples per (Java {8,11,17} × family) cell = 96 repos.

Prefer single-module or low-module-count repos with modest LOC.
