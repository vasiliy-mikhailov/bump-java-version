THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and the row you have been handed is one of
the numbers it manages. Overriding a member of the managed set is the work of this phase, which is
the reverse of what the phase after the compile is told, and the reason is narrow: javac will not
start under the new JDK with a lombok that cannot read it, nothing in the bump raises lombok, and
Boot, which would carry it, cannot be moved until the JDK has moved. So the override happens here,
on this row, and on nothing else.

bump_patch REACHES A MANAGED NUMBER AND SAYS NOTHING ABOUT HAVING DONE SO. The recipe the harness
writes for it carries overrideManagedVersion, so on a Boot module it puts the version onto the
declaration itself and that artifact stops following the set. On this row that is the intended
effect. It is also why the same call is the wrong reflex at after-pins, where the same silence
covers a member the Boot raise would have carried.

WHERE THE OVERRIDE GOES, AND WHAT REACHES IT. declared_versions says which of these this module is:
- it names a Boot parent, its own or one at the end of an in-repo chain: the override is a
  lombok.version property in this module's own properties block, which
  org.openrewrite.maven.ChangePropertyValue reaches. Prefer it to writing the number onto the
  declaration: the dependency stays version-less, the rest of the set stays parameterised, and the
  number sits in one place the next phase can read and reconcile.
- it imports spring-boot-dependencies at import scope: the property route does nothing there,
  because the property is read in the imported pom's own context, and the override is a
  dependencyManagement entry ahead of the import, which org.openrewrite.maven.AddManagedDependency
  writes.
- it is Gradle under the Boot plugin: org.openrewrite.gradle.UpgradeTransitiveDependencyVersion
  raises a version that arrives through the platform, which is what a managed member is there.

READ WHAT THE SET ALREADY GIVES BEFORE YOU WRITE ANYTHING. A managed row prints no number to compare
against a floor, and inspect_jar reports which versions of an artifact are present in the local
repository, which is what resolved here. Where that is already at or above the floor, the floor is
met, because compliance is measured against the packages that RESOLVED rather than against what this
module declares: DONE over an empty diff is the correct answer and a common one. Writing the floor
number into a managed property that is already above it lowers the version, which is the one thing
this phase is told never to do.

THE NUMBER YOU WRITE OUTLIVES THIS PHASE, which is why it is worth being exact about. after-pins
moves Boot, the set moves with it, and your override goes on winning: the artifact holds the number
you gave it while everything around it advances. That is accepted here because the alternative is a
compile that never starts, and it is the reason to write the floor rather than anything below it,
and to say in your answer which placement you used, so the next phase is reconciling something it
can see.

BOOT IS NOT YOURS IN THIS PASS. bump_line crosses Spring Boot's lines and it is the right tool at
after-pins; run here, where the Boot floor names the 3.5 line, it files a parent that declares a
Java version this module has not reached. Where the only route you can find to this row runs through
Boot, that is BLOCKED with the reason named, and after-pins is where it gets picked up.

ON THE HOP THAT CARRIES THE KOTLIN ROW, ONE PROPERTY DOES BOTH HALVES. It is not a patch move, so
bump_patch refuses it, and bump_line knows Spring Boot's lines only, so it refuses too; neither
refusal is an obstacle. On a Maven module under Boot, kotlin.version is the parameter that drives
the kotlin-bom Boot imports and the kotlin-maven-plugin it manages, so ChangePropertyValue moves the
library and the compiler on one number. On Gradle the plugin version lives in a plugins block or a
version catalog, which Boot does not own; where nothing in apply_recipe's list reaches that
placement, say so as BLOCKED naming the placement declared_versions printed, rather than reporting a
raise that landed on the library alone.

WHAT FINISHED LOOKS LIKE when you call declared_versions afterwards: exactly one artifact in this
module has grown a number of its own, and it is the one in the list above. The starter rows still
read (managed by ...), and any other member that has acquired a version during this phase is a pin
the next stage has to undo.
