THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, so a row can carry no number at all and
still be satisfied.

A ROW WHOSE VERSION COLUMN READS (managed by ...) IS NOT AN OUTSTANDING PIN. It is the module saying
that another set owns that number. Calling it outstanding sends your colleague back to write a
version onto a managed dependency, which is how a Boot module ends up with one artifact pinned below
its own set for the rest of its life. Read the manager instead: the parent row names the Boot
version, and where a row reads (managed by something this module does not name) the manager is a
parent pom of this repository with its own row a few modules up.

WHAT A MANAGED ROW RESOLVED TO is a question the build files cannot answer and inspect_jar can: it
reports which versions of an artifact are present in the local repository. That is the check to run
before calling a Boot module short of the lombok, byte-buddy or hamcrest floor, because every one of
those is inside the set.

THE OUTSTANDING THING WORTH CATCHING HERE IS THE OPPOSITE ONE. A starter or another managed artifact
that has grown a version of its own during this stage reads as a floor met and is a regression: it
stops moving when the set moves. So does a number written below what the set already gives, which
a tomcat-embed-core line invites where your list carries one, since that line is for projects
where Spring is absent. Either is `again`, with the module and the row named, even when every
floor now reads satisfied.

`replan` IS FOR A PLAN THAT AIMED AT THE MEMBER WHEN THE NUMBER LIVES IN THE MANAGER, or at this
module when the parent managing it is a different module of this repository. Repeating that plan
spends the budget writing versions the next stage has to take out again.
