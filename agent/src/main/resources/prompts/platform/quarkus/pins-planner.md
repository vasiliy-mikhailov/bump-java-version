THE REGIME: one number decides what every io.quarkus artifact in this module
resolves to. The platform BOM arrives at <scope>import</scope> and the extensions
under it carry no version of their own, so the row worth planning against is
rarely the row an extension sits on.

YOUR LIST IS LOMBOK, and above 21 it is lombok and kotlin. That is the whole of
it, so this is not a survey of the module. Quarkus does not manage lombok:
quarkus-bom carries no org.projectlombok entry at all, read at 3.16.1 and again
at 3.36.1, so a Quarkus module that uses Lombok writes a literal version of its
own and that row is squarely this module's to set. Kotlin is the opposite shape.
The BOM does manage the stdlib, 2.0.21 at platform 3.16.1 and 2.3.21 at 3.36.1,
so above 21 the kotlin floor is a question about the platform version and about
the kotlin-maven-plugin, which no BOM sets because it is a plugin.

ONE LOMBOK ROW CAN STAND FOR TWO DECLARATIONS. declared_versions keys a row by
coordinate and by kind within a file, so two <dependency> blocks for
org.projectlombok:lombok in the same pom collapse into the first one read.
AlanSilvaLima/curso-rest-quarkus carried exactly that: 1.18.38 in the compile
block and 1.18.30 in the provided one, behind a single row. Plan from the pom
rather than from the row, and give the line number of every block.

THE PLATFORM VERSION BEHAVES LIKE A FLOOR AND IS NOT ON THE LIST, which is why
NOTHING-OUTSTANDING against the list alone is a true answer and half an answer.
The jars that read this module's bytecode during its own build are the
platform's deployment artifacts, so a platform older than the target fails in
the plugin rather than in javac. The plugin's code generation runs inside the
module gate's own test-compile; its augmentation waits for package and the
repository gate, and a plugin version that does not exist stops both.
JoaoGabrielCarvalhoL/quarkus-social reached 21 on
quarkus.platform.version 2.12.0.Final to 3.16.4, and apedano/account-service
moved 3.1.2.Final to 3.2.4.Final in this phase.

WHERE THAT NUMBER LIVES. A generated Maven project holds three properties,
quarkus.platform.group-id, quarkus.platform.artifact-id and
quarkus.platform.version, and writes the BOM import as all three ${...} at once,
so the row declared_versions prints names Quarkus only through those properties.
Other projects import io.quarkus:quarkus-bom literally against a property of
their own naming, version.io.quarkus in one repository here. On Gradle it can be
quarkusPlatformVersion in gradle.properties, a pluginManagement block in
settings.gradle.kts, a root ext entry, or a literal inside enforcedPlatform, and
one module here kept it in three files with a stale commented copy in a fourth.

The report has no row for quarkus-maven-plugin where its block carries
<executions>, and none for a property whose name does not end in version. Read
the pom and give the line numbers: on the module measured here one property was
read at three of them, its own declaration, the BOM import and the plugin.
