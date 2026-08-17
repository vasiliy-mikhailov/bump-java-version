THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and this list straddles the line between
what the manager owns and what it does not. It carries Boot itself, stated twice so that a module
inheriting the starter parent and a module importing spring-boot-dependencies each find their own
wording. It carries artifacts the set manages, byte-buddy and its agent, mockito-core, hamcrest and,
where the hop has one, tomcat. It carries artifacts Boot manages nothing about: archunit and its
junit5 companion, the jacoco plugin, the javax.xml.bind and javax.annotation rows JEP 320 left
behind, the Gradle wrapper. Sorting the rows into those three is the first thing this plan does,
because the right move differs in each.

DO NOT SORT THEM FROM MEMORY. Which artifacts a Boot line manages changes between lines, and Boot
imports BOMs of its own, so a row can belong to the set without Boot's name being on it: the jaxb
runtime arrives that way where the javax jaxb api does not. declared_versions
prints "(managed by ...)" in the version column exactly where this module leaves the number to the
set and prints a number where the module owns it, so that column is the sort. A row the module does
not declare at all has no line in the report, which is not the same as a floor unmet: it can still
be satisfied by what resolved.

BOOT FIRST, AND IT MAY BE MOST OF THE PLAN. One raise carries the members with it, measured on this
corpus: the recipe took a project's parent to 3.5.16, its Tomcat from 10.1.43 to 10.1.55 and its
jackson-databind along with them, without being asked for any of the three. Compliance is measured
against the packages that RESOLVED, so a member the new Boot carries to or past its floor is met
with no line for it in this plan at all. Plan the Boot line, then a second reading, rather than a
row per artifact.

THE SECOND READING IS WHERE THE ONLY LEGITIMATE OVERRIDE IS DECIDED, and this is the part the phase
before the bump has no equivalent of. Boot's ceiling on this hop is the Boot floor in your own list:
the last of the 2.x line below JDK 17, because Boot 3 needs 17, and the 3.5 line from 17 up, because
the only recipe past it is proprietary. Where the raise has landed there and a member row still
reads below its floor, the manager has gone as far as it goes and that row is an override worth
planning, named one member at a time with the number it is short of. Where the raise has not
happened yet, the same edit is a different thing: it preempts a raise that would have carried the
member, and it does not dissolve when the raise arrives, because a number written into this module
goes on winning while the set moves past it. The difference between the good move and the worst one
available here is the order, not the edit, so the plan states the order and the doer follows it.

THE ROWS NOTHING MANAGES ARE ORDINARY RAISES, AND BEING A BOOT MODULE CHANGES NOTHING ABOUT THEM. A
plan that routes archunit or the jacoco plugin through the Boot line has planned a move that will
not touch them, and a plan that hesitates over them has confused them with the members. They are
raised on this module's own declarations, in the same pass as the Boot line if you like, and saying
which rows those are is half of what keeps the doer from treating every row as a managed one.

THE TOMCAT ROW, WHERE THIS HOP CARRIES ONE, IS NOT A ROW YOU PLAN. It names the 9.0 line and carries
its own reason: it is for projects where Spring is absent, since Boot brings a container of its own.
Where the 3.5 line has reached this module the set is on Tomcat 10, so the row sits a major behind
what is already here, and that major is the jakarta rename rather than a number anyone writes. Where
this module is still on Boot 2, the Boot move is what raises the container. Either way the plan for
a Tomcat finding on a Boot module is a Boot line and the module that owns it.

WHERE THE BOOT VERSION LIVES DECIDES WHOSE TURN THIS IS, not which recipe runs. All four placements
move by the same named move, so placement here is the question of whether there is anything to plan
at all. declared_versions says which this module is:
- a parent block in this module's own pom (41 modules here): the number is yours.
- an import-scope spring-boot-dependencies entry (11): also yours, and the rows print in reading
  order so you can see what sits ahead of the import.
- nothing about Boot at all, with the starter rows reading (managed by something this module does
  not name) (185, and none of them says Boot anywhere in its own file): the number lives in an
  in-repo parent pom, which has its own row a few modules up and its own turn in this walk. A raise
  planned here writes a parent block or a managed entry into a module that had neither, which
  detaches this child from the chain it inherits from.
- the Boot Gradle plugin (40 files): the version arrives through the platform.

3.5 IS A LINE, NOT A READING. A parent that says 3.5.4 is twelve patches behind the floor above, and
nothing in the pom tells you which of the two it is. So a Boot 3 row is planned as a move to the
head of its line unless the number it shows is already at or above the floor you were given, and a
2.7 row reads the same way where the floor you were given names the 2.x line. Say which of two moves
you mean: inside a line is patch releases, CVE fixes and no API change, while across a line, Boot
2.7 to 3.5, is the jakarta rename, the highest-variance migration in this system and the one
measured losing 1916 of 2409 tests on a repository that took it unprepared. It is still the plan
where the floor names 3.5 and this module is on 2.x, because the alternative is a module left on a
line that went end of life in 2023 and cannot run the JDK it has just been raised to.

NOTHING-OUTSTANDING IS A COMMON ANSWER HERE AND IT IS NOT A SHRUG. A floor Boot already satisfies
transitively is met without a line being written into this module. Say it when the Boot number is at
or above the floor and the members resolve above theirs, and say it when the number belongs to a
parent module that is somebody else's turn. Where the Boot floor is itself the ceiling the free
tooling reaches, a finding that survives it is a fact for the doer to report and not a pin for you
to plan around.
