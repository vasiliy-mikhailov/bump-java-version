THE REGIME: a platform BOM manages this module, so the smallest edit that clears
a wall here is usually a version property and not anything under src.

WHEN THE FIRST REAL ERROR IS A BUILD STEP. quarkus-maven-plugin runs inside this
module's own build, and a failure from it reads "[error]: Build step" followed by
a processor name and the method that threw. That processor lives in a deployment
jar the pom never declares: io.quarkus.hibernate.orm.deployment comes from
quarkus-hibernate-orm-deployment, and its version is whatever the platform says.
inspect_jar opens it under that name.

"Unsupported class file major version" from inside one of those steps, 65 for a
21 target and 69 for a 25 target, is the platform's own ASM refusing the bytecode
javac has just written. It belongs to the family the shared advice names, but the
floors for that family move byte-buddy, archunit and jacoco, and none of them is
the copy doing the reading; adding an asm or byte-buddy dependency beside it
changes nothing the deployment jar loads. The edit that cleared this in this
corpus was quarkus.platform.version, 2.12.0.Final to 3.16.4.

An io.quarkiverse artifact is the opposite case. The platform does not manage it,
its version sits in the pom as a number of its own, and when the platform crosses
to Quarkus 3 an extension left on a javax build throws NoClassDefFoundError on
javax/enterprise/context/ApplicationScoped from its own processor. 0.1.1 to 0.2.2
is what that repair looked like here. The crossing also renames javax to jakarta
in this module's sources, which is a wide edit with edit_file, and it is the
crossing rather than a wall of its own.
