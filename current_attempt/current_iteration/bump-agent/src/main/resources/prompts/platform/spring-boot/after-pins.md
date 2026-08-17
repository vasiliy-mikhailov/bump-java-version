THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and the list above holds rows on both sides
of that. Some of it is Boot itself. Some of it is inside the set Boot manages, byte-buddy and its
agent, mockito-core, hamcrest and, where this hop carries one, tomcat. Some of it Boot manages
nothing about: archunit and its junit5 companion, the jacoco plugin, the javax rows, the Gradle
wrapper. The order of work follows from that: Boot first, then read what the set now resolves to,
then whatever is still short.

MOVING BOOT IS bump_line, AND IT TAKES A LINE RATHER THAN A PATCH. Hand it org.springframework.boot,
the artifact this module actually names, spring-boot-starter-parent or spring-boot-dependencies, and
the line to land on; 3.5 and 3.5.16 mean the same thing to it. The harness derives the recipe and
picks the actuator, so a Gradle module takes the same call and you do not have to know which build
system you are on.

bump_patch IS THE WRONG TOOL FOR BOOT EVEN WHEN THE MOVE LOOKS LIKE A PATCH. 3.5.4 to 3.5.16 is one
line and bump_patch will take it, and it writes exactly the number you type and stops there. The
recipe resolves the head of the line itself and does nothing if it is already there. Two doers in
one sweep read a floor naming 3.5.4 and wrote exactly 3.5.4: a real version, a green build, nothing
to complain about, and a module still managing Tomcat 10.1.43 with eleven CRITICAL+HIGH. The
repository whose doer ran the recipe instead landed on 3.5.16 and went from 63 findings to 2. Being
on 3.5 is not evidence of being current and no version in the pom tells you either way, so run the
line move and read what it did. Where your floor names the 2.x line instead, 2.7.3 to the head of
2.7 is the same move and the same mistake is available in it.

CROSSING BOOT 2 TO BOOT 3 IS THE WORK, NOT A SIDE EFFECT OF IT. Where the floor names 3.5 and this
module is on 2.x, bump_line runs the migration for the line: Framework 6, Security 6.5, the property
renames, and in source the javax to jakarta rename. That rename is the highest-variance move in this
system, measured losing 1916 of 2409 tests on a repository that took it unprepared, and it is asked
for anyway, because a module left on the 2.x line is on a line that went end of life in 2023 and
cannot run the JDK this module has just been raised to. You hold no file editor in this phase, and
you do not need one: where source still names javax after the line has moved, apply_recipe reaches
org.openrewrite.java.migrate.jakarta.JavaxEEApiToJakarta for the lot, or
JavaxServletToJakartaServlet and JavaxMailToJakartaMail for one API.

THEN READ WHAT THE SET RESOLVED TO, BECAUSE THAT IS WHERE THE REST OF YOUR LIST IS ANSWERED. Call
declared_versions again and inspect_jar for the members: one Boot raise can close several rows at
once, and compliance is measured against the packages that RESOLVED rather than against what this
module declares, so a member the new Boot carries past its floor needs nothing written for it. A
member row still below its floor after the raise is a different case, and the one place an override
belongs in this phase: Boot's ceiling on this hop is the Boot floor in your own list, the last of
the 2.x line below JDK 17 and the 3.5 line from 17 up, and once the raise has landed there the
manager has gone as far as it goes. Then the override is the placement move, ChangePropertyValue on
the property this module declares, AddManagedDependency ahead of an import, or
UpgradeTransitiveDependencyVersion on Gradle, made one member at a time and reported by name.

THE SAME EDIT MADE IN THE OTHER ORDER IS THE WORST MOVE AVAILABLE TO YOU. Before the Boot raise it
preempts a raise that would have carried the member, and it does not come undone when the raise
arrives: the artifact holds your number while the set moves past it at every later raise, and the
row that would have reported the next CVE in it reports what you typed. bump_patch will not warn
you, because the recipe the harness writes for it carries overrideManagedVersion and reaches a
managed number silently, so reaching for the sanctioned tool is not what makes the move right. The
order is.

A FINDING AGAINST AN ARTIFACT WITH NO ROW AT ALL IS A BOOT MOVE, NOT A PIN. jackson-databind,
snakeyaml, logback-classic and netty are members of the set and none of them is in your list; their
fixed versions ship in the next Boot patch. AddManagedDependency, ChangePropertyValue and
UpgradeTransitiveDependencyVersion will all write such a version, the build stays green and the next
scan is quiet, and what it costs is permanent. Move Boot.

THE ROWS BOOT MANAGES NOTHING ABOUT ARE PLAIN RAISES. archunit with archunit-junit5, the jacoco
plugin, the javax.xml.bind and javax.annotation rows, the Gradle wrapper: no managed set reconciles
them, so bump_patch onto this module's own declaration is the whole move, and hesitating over them
because this is a Boot module leaves a floor unmet for a reason that does not apply to them. Let the
version column decide rather than the coordinate, since Boot imports BOMs of its own and the jaxb
runtime arrives through one of them where the javax jaxb api does not.

THE TOMCAT ROW, WHERE THIS HOP CARRIES ONE, SAYS ITS OWN REASON. It names the 9.0 line and it is for
projects where Spring is absent, since Boot brings a container of its own. On a module the 3.5 line
has reached, the set is on Tomcat 10 and that row is a major behind it, and the major is the jakarta
rename rather than something a version writes. On a module still on Boot 2 the Boot move is what
raises the container. Either way it is not a version you write into this module.

inspect_jar ANSWERS WHAT A MANAGED ROW RESOLVED TO, with one caveat that belongs to this phase in
particular: it lists what is present in the local repository, which is what has been resolved here
before. After a Boot raise the old member and the new one both sit there, so an old Tomcat or an old
jackson in that list is not evidence the set is still on it. The Boot number is what settles it.

BOTH ENDINGS ARE REAL ANSWERS, AND THE CEILING IS REAL. Compliance reads resolved packages, so DONE
over an empty diff is correct here and common. If this module is at the Boot floor and a CVE still
stands, the floor is met and the finding is a fact: put it in the DONE line, naming the artifact and
saying Boot is at the ceiling this hop reaches. Say BLOCKED where a floor itself is out of reach,
naming which row and what this module does that prevents it, a Boot version owned by a parent module
that is not yours being the commonest. Neither answer is a reason to write a version onto a managed
artifact ahead of the raise: that does not clear the finding, it only stops anything from reporting
it.
