THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS. Most of the floors above name artifacts
inside that set: lombok, byte-buddy and its agent, mockito-core, hamcrest, jackson, tomcat. Raising
one of those the ordinary way writes a version onto the declaration and takes it out of the set for
good, so that artifact keeps its number while the rest of the set moves past it at the next Boot
raise.

MOVING BOOT ITSELF IS bump_line, AND IT CARRIES NO RECIPE ID. Hand it
org.springframework.boot:spring-boot-starter-parent and the line to land on; the harness derives the
recipe and picks the actuator. Naming the recipe yourself is where this misfires: of the Spring
recipe ids written in this corpus, 42 were right, 12 named org.openrewrite.spring.something, a
package that does not exist, and 6 passed parameters to a recipe that takes none. What a name it
cannot resolve costs you depends on which build system you are on, and neither answer is good: the
Gradle actuator fails the run, and the Maven one reports a successful run that changed nothing, so
on the larger half of this corpus a misspelling reads exactly like a pin that was already met.

RAISING A MEMBER WITHOUT LEAVING THE SET is the move when the list above asks for one and carries no
Boot line. declared_versions says which of these this module is:
- it names a Boot parent, its own or one at the end of an in-repo chain: the override is a property
  in this module's own properties block, which org.openrewrite.maven.ChangePropertyValue reaches.
- it imports spring-boot-dependencies at import scope: the property route does nothing there,
  because the property is read in the imported pom's own context, and the override is a
  dependencyManagement entry ahead of the import, which org.openrewrite.maven.AddManagedDependency
  writes.
- it is Gradle under the Boot plugin: org.openrewrite.gradle.UpgradeTransitiveDependencyVersion
  raises a version that arrives through the platform, which is what a managed member is there.

A FLOOR CAN SIT BELOW WHAT THE SET ALREADY GIVES YOU, and a managed row shows no number to compare
it against. inspect_jar reports which versions of an artifact are present in the local repository,
which is what the set actually resolved, and that is the only way to compare a managed row to a
floor. Where your list carries a tomcat-embed-core line, that line says as much in its own text:
it is for projects where Spring is absent, because Boot brings a newer Tomcat, so writing it into
a Boot module is a downgrade dressed as a pin.

WHAT FINISHED LOOKS LIKE when you call declared_versions afterwards: the number sits in a property,
a dependencyManagement entry or the platform, and the starter rows still read (managed by ...). A
starter that has grown a version of its own is a pin the next stage has to undo.
