THE REGIME: one number decides what every io.quarkus artifact in this module
resolves to. The platform BOM arrives at <scope>import</scope> and the extensions
under it carry no version of their own, so the row worth planning against is
rarely the row an extension sits on.

THE FLOOR LIST NAMES NO QUARKUS ARTIFACT, and most of what it does name is not
declared here: on the Quarkus modules measured in this corpus the applicable
floors came to lombok and the compiler plugin. Saying that is a real answer, and
it is not the whole one, because the platform version behaves like a floor and is
not on the list. The jars that read this module's bytecode during its own build
are the platform's deployment artifacts, so a platform older than the target
fails in augmentation rather than in javac: JoaoGabrielCarvalhoL/quarkus-social
reached 21 on quarkus.platform.version 2.12.0.Final to 3.16.4. It is also the
only lever over what the scanner counts, which on the module measured here was
netty, jackson-databind and quarkus-vertx-http, every one of them managed by the
BOM and none of them movable on its own.

WHERE THAT NUMBER LIVES. A generated Maven project holds three properties,
quarkus.platform.group-id, quarkus.platform.artifact-id and
quarkus.platform.version, and writes the BOM import as all three ${...} at once,
so the row declared_versions prints names Quarkus only through those properties.
Other projects import io.quarkus:quarkus-bom literally against a property of
their own naming, version.io.quarkus in one repository here. On Gradle it can be
quarkusPlatformVersion in gradle.properties, a pluginManagement block in
settings.gradle.kts, a root ext entry, or a literal inside enforcedPlatform, and
one module here kept it in three files with a stale commented copy in a fourth.

The report has no row for quarkus-maven-plugin, whose block carries <executions>,
and none for a property whose name does not end in version. Read the pom and give
the line numbers: on the module measured here one property was read at three of
them, its own declaration, the BOM import and the plugin.
