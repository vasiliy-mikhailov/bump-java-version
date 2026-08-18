THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and the one row this list carries is a
member of the set it manages. Boot states lombok's number as a lombok.version property inside its
own dependencyManagement, so a module that declares lombok without a version has no number of its
own for you to raise. What you are planning is an override of one parameter of the manager, and it
is the only phase in this walk where that is the right plan.

WHY IT IS RIGHT HERE. Lombok is an annotation processor running inside javac, so a lombok the new
compiler cannot load stops the module gate before anything downstream of it runs, and nothing in
the bump phase raises it. Boot, which would carry it, moves at after-pins, behind that compile:
where the Boot floor is the 3.5 line it names a parent that declares a Java version this module has
not reached yet, so there is no Boot raise available to you to satisfy this row with. A plan that
names one has planned the next phase's work at the point in the walk where it cannot be done.

WHICH OVERRIDE EXISTS DEPENDS ON HOW BOOT REACHES THIS MODULE, and the report says which of the
three it is:
- a parent row, whether this module names a Boot parent itself (41 modules here) or reaches one at
  the end of a chain of in-repo poms (185, and none of them says Boot anywhere in its own file): the
  override is a lombok.version property in this module's own properties block, which leaves the
  declaration version-less and the rest of the set parameterised as it was.
- an import-scope row for spring-boot-dependencies (11 modules): a property will not override it,
  because the property is read in the imported pom's own context. The override is a
  dependencyManagement entry ahead of the import, and the report lists rows in reading order, so
  ahead of it is something you can check.
- the Boot Gradle plugin (40 files): the version arrives through the platform rather than off a
  declaration this module owns, so the override is a constraint on what the platform brings.
Name which of the three you mean. The doer makes a different call for each, and a plan reading only
"raise lombok to the floor in this module" is carried out as whichever of them is easiest to reach.

WHAT A MANAGED ROW LOOKS LIKE IN declared_versions. A dependency that states no version prints, in
the version column, "(managed by org.springframework.boot:spring-boot-starter-parent <version>)",
or "(managed by something this module does not name)" when this module's own files name nothing
that could be managing it. Neither is a version below a floor. The first names the number and where
it lives; the second means the manager is a parent pom of this repository, and that parent is its
own module a few rows up in the same report. Plan against the module that owns the number.

THE SET MAY ALREADY BE ABOVE THE FLOOR, AND THEN THERE IS NOTHING TO PLAN. Compliance is measured
against the packages that RESOLVED, so a floor Boot already satisfies is met with nothing written
into this module, and NOTHING-OUTSTANDING is the whole answer. A managed row shows no number to
compare against, and inspect_jar, which reports which versions of an artifact are present in the
local repository, is what answers it. Planning the floor number into a managed property that is
already above it is a downgrade dressed as a pin, and it is not one this walk corrects later: the
number written here goes on winning after Boot moves at after-pins.

ON THE HOP THAT ALSO CARRIES THE KOTLIN ROW, BOOT PARAMETERISES KOTLIN THE SAME WAY. One
kotlin.version drives both the kotlin-bom Boot imports and the kotlin-maven-plugin it manages, so on
a Maven module inheriting Boot the plan is one property that reaches the compiler and the library
together, which is what that row is about: a compiler left below the floor falls back silently and
the gate reads it as an unraised bump. The coordinate in the row, org.jetbrains.kotlin:kotlin, is
the head of a line rather than anything a module declares, so name what this module declares. Where
the Kotlin plugin version sits in a Gradle plugins block or a version catalog, that is a placement
Boot does not own, and the plan says which it is rather than leaving it to be discovered.

NOTHING ELSE IN THE MANAGED SET IS OWED IN THIS PASS. tomcat, jackson, mockito, byte-buddy and
hamcrest are all inside the set and none of them is in the list above. They are after-pins' work,
behind the compile, where the Boot raise may carry them and where a break can be attributed to the
version that caused it.
