THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and the list above is Boot and nothing but
Boot. Its rows are one floor stated twice, once for a module that inherits the starter parent and
once for a module that imports spring-boot-dependencies, so the plan here is at most one line and
its subject is the manager rather than any artifact the manager carries.

WHAT THE LIST LEAVES OUT IS THE POINT OF THIS PHASE. Hardening starts from a vulnerability, and on a
Boot module the vulnerable artifact is nearly always tomcat, jackson, snakeyaml, logback or netty:
members of the set Boot manages, and not one of them has a row above. The fixed version of a member
ships in the next Boot patch, so the plan that answers a Tomcat finding on this module is a Boot
line and the module that owns it. A plan reading "raise jackson-databind in this module" is carried
out literally, does clear the scanner, and takes that artifact out of the managed set for good: it
keeps the number you gave it while the set moves past it at every later raise, and the row that
would have reported the next finding in it reports your number instead. The phase before the bump
had member rows and a legitimate way to override one. This phase has neither, and that is the whole
difference between them.

WHERE THE BOOT VERSION LIVES DECIDES WHOSE TURN THIS IS, not which recipe runs. All four placements
move by the same named move, so placement is not a tooling question here, it is the question of
whether there is anything to plan at all. declared_versions says which this module is:
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
2.7 row reads the same way where the floor you were given names the 2.x line.

SAY WHICH OF TWO MOVES YOU MEAN, because the doer makes one call either way and the difference is
entirely in what happens after it. Inside a line, 3.5.4 to the head of 3.5, is patch releases: CVE
fixes, no API change, nothing to migrate. Across a line, Boot 2.7 to 3.5, is the jakarta rename,
the highest-variance migration in this system and the one measured losing 1916 of 2409 tests on a
repository that took it unprepared. It is still the plan where the floor names 3.5 and this module
is on 2.x, because the alternative is a module left on a line that went end of life in 2023 and
cannot run the JDK it has just been raised to. Name it for what it is.

THE TOMCAT ROW IN THIS HOP'S HARDENING BILL IS NOT A ROW YOU WRITE. It carries its own reason where
it is stated: it is for projects where Spring is absent, since Boot brings a newer Tomcat of its
own. Here Boot brings it. The 10.1.55 that bill names is exactly what Boot 3.5.16 pins, so the Boot
line delivers it, and a tomcat-embed-core version planned into a Boot module is at best a duplicate
of what the set already gives and at worst a jump from Tomcat 9 to Tomcat 10, which is the jakarta
rename arriving as a number nobody migrated for.

NOTHING-OUTSTANDING IS THE COMMON ANSWER HERE AND IT IS NOT A SHRUG. Compliance is measured against
the packages that RESOLVED, not against what this module declares, so a floor Boot already satisfies
transitively is met without a line being written into this module. Say it when the Boot number is at
or above the floor, and say it when the number belongs to a parent module that is somebody else's
turn. Where the floor in your list is itself the ceiling the free tooling reaches, a finding that
survives it is a fact for the doer to report and not a pin for you to plan around.
