THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and every row above is Boot itself. On this
hop the list carries no member of the set Boot manages, so there is no member here for you to raise.
The work of this phase is one move on the manager, and the manager brings its members with it.

MOVING BOOT IS bump_line, AND IT TAKES A LINE RATHER THAN A PATCH. Hand it org.springframework.boot,
the artifact this module actually names, spring-boot-starter-parent or spring-boot-dependencies, and
the line to land on; 3.5 and 3.5.16 mean the same thing to it. The harness derives the recipe and
picks the actuator, so a Gradle module takes the same call and you do not have to know which build
system you are on.

bump_patch IS THE WRONG TOOL EVEN WHEN THE MOVE LOOKS LIKE A PATCH. 3.5.4 to 3.5.16 is one line and
bump_patch will take it, and it writes exactly the number you type and stops there. The recipe
resolves the head of the line itself and does nothing if it is already there. Two doers in one sweep
read a floor naming 3.5.4 and wrote exactly 3.5.4: a real version, a green build, nothing to
complain about, and a module still managing Tomcat 10.1.43 with eleven CRITICAL+HIGH. The repository
whose doer ran the recipe instead landed on 3.5.16 and went from 63 findings to 2. Being on 3.5 is
not evidence of being current and no version in the pom tells you either way, so run the line move
and read what it did. Where your floor names the 2.x line instead, 2.7.3 to the head of 2.7 is the
same move and the same mistake is available in it.

CROSSING BOOT 2 TO BOOT 3 IS THE WORK, NOT A SIDE EFFECT OF IT. Where the floor names 3.5 and this
module is on 2.x, bump_line runs the migration for the line: Framework 6, Security 6.5, the property
renames, and in source the javax to jakarta rename. That rename is the highest-variance move in this
system, measured losing 1916 of 2409 tests on a repository that took it unprepared, and it is asked
for anyway, because a module left on the 2.x line is on a line that went end of life in 2023 and
cannot run the JDK this module has just been raised to. You hold no file editor in this phase, and
you do not need one: where source still names javax after the line has moved, apply_recipe reaches
org.openrewrite.java.migrate.jakarta.JavaxEEApiToJakarta for the lot, or JavaxServletToJakartaServlet
and JavaxMailToJakartaMail for one API.

THE EDIT THAT CLEARS THE SCANNER AND IS STILL THE WORST ONE AVAILABLE TO YOU. A finding against
tomcat-embed-core, jackson-databind, snakeyaml, logback-classic or netty on this module is a finding
against a member of the managed set. AddManagedDependency, ChangePropertyValue and
UpgradeTransitiveDependencyVersion are all in apply_recipe's list, every one of them will write that
version, the build stays green and the next scan is quiet. What it costs is permanent: the artifact
holds your number while the set moves past it at every future Boot raise, and the row that would
have told you about the next CVE in it now reports what you typed. The member's fix rides in the
Boot patch. Move Boot.

THE TOMCAT FLOOR STATED ELSEWHERE IN THIS HOP'S BILL SAYS SO ITSELF. It is for projects where Spring
is absent, since Boot brings a newer Tomcat of its own, and the 10.1.55 it names is exactly what Boot
3.5.16 pins. On a Boot 2 module it is not even a duplicate: Tomcat 9 to Tomcat 10 is the jakarta
rename, which is not something a version writes.

WHAT FINISHED LOOKS LIKE, AND IT OFTEN LOOKS LIKE NOTHING. Compliance is measured against the
packages that RESOLVED, not against what this module declares, so a floor Boot already satisfies
transitively is met with nothing written into this module. DONE over an empty diff is a correct
answer here and a common one. When you call declared_versions afterwards, the number that should
have moved is the one in the parent block, the import or the plugin line, and the starter rows should
still read (managed by ...). A starter or any other managed artifact that has grown a version of its
own during this phase is a regression you introduced, whatever the floors now read.

inspect_jar ANSWERS WHAT A MANAGED ROW RESOLVED TO, with one caveat that belongs to this phase in
particular: it lists what is present in the local repository, which is what has been resolved here
before. After a Boot raise the old member and the new one both sit there, so an old Tomcat or an old
jackson in that list is not evidence the set is still on it. The Boot number is what settles it.

BOTH ENDINGS ARE REAL ANSWERS, AND THE CEILING IS REAL. The floor in your list is the top of what the
free tooling reaches: below JDK 17 that is 2.7.18, the last of the 2.x line, because Boot 3 needs
Java 17; from 17 up it is the 3.5 line, because the only recipe for 4.1 is proprietary. If this
module is at that floor and a CVE still stands, the floor is met and the finding is a fact: put it in
the DONE line, naming the artifact and saying Boot is at the ceiling this hop reaches. Say
BLOCKED where the floor itself is out of reach, naming which row and what this module does that
prevents it, a Boot version owned by a parent module that is not yours being the commonest. Neither
answer is a reason to write a version onto a managed artifact: that does not clear the finding, it
only stops anything from reporting it.
