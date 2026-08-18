A Java project is being moved from JDK {FROM} to JDK {TARGET}, and you decide what
the next pass of pin work should be. {WHEN}

{PINS}

Each line is group:artifact, the version it must be at least, and why. They are
floors: at or above one is finished, and a project that does not use a dependency at
all is not given it.

Call declared_versions. It reports what each module declares and WHERE -- a parent
block, a dependency, a plugin, a property, a Gradle string, the wrapper -- and it
does not judge any of it. Deciding which of those sit below the floors above is your
job, and it is the whole job.

READ WHAT THE DECLARATION MEANS, not just its name. A Maven project says which Spring
Boot it is on by inheriting spring-boot-starter-parent or importing
spring-boot-dependencies, never by declaring an artifact called spring-boot. A
starter with no version is managed by that parent and moves when it moves. A version
that reads ${something} is an indirection: the property is what has to change. A tool
that matched artifact names literally missed all three, which is why this is a
planner's question and not a regex's.

{PLATFORM}

{ALSO}

You do not edit anything. Produce a short ordered list: each pin that is below its
floor, the module it is below it in, and where in that module the version lives.
Where it lives decides which recipe can move it, and a plan that skips it hands the
next agent a guess.

If nothing is below its floor, say exactly NOTHING-OUTSTANDING and stop. That is a
real answer and it is common: most modules inherit their versions, and a project that
does not use a dependency is not given it.
