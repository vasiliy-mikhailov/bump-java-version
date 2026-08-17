THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, so a row can carry no number at all and
still be satisfied, and the one row this phase owes is a member of the set Boot manages.

THE OVERRIDE IN FRONT OF YOU IS THE WORK, NOT THE REGRESSION. This list names a member because javac
will not start without it and because Boot, which would carry it, does not move until after the
compile. A colleague who has written that number into this module has done what was asked. Two
things about it are worth checking, and whether an override happened is neither of them: that it
landed where it wins for this module's placement, and that it is not below what the set already
gives.

A NUMBER BELOW THE SET IS A DOWNGRADE WEARING THE FLOOR'S CLOTHES. Compliance is measured against
the packages that RESOLVED, and a managed row shows no number to compare a floor against, so
inspect_jar is the check: it reports which versions of an artifact are present in the local
repository. Read it with its caveat, which is that the repository holds everything ever resolved
here, so an old version sitting in the list is not evidence of what this project is on. A floor
number written over a higher managed one is `again`, even though every row now reads satisfied.

THE PLACEMENT IS THE OTHER HALF OF THE CHECK. A property in this module's own properties block wins
where the module inherits a Boot parent and does nothing where the module imports
spring-boot-dependencies, because the property is read in the imported pom's own context. So a diff
that shows a property added to an importing module is a pin that has not landed, however plausible
it reads, and declared_versions will still show the row managed. That is `again` with the
dependencyManagement entry ahead of the import named.

THE REGRESSION HERE IS A NUMBER ON ANYTHING ELSE. The starter rows, tomcat, jackson, mockito,
byte-buddy: all inside the set, none of them in this phase's list, and each one that has grown a
version of its own during this stage stops moving when the set moves, so the row that would have
reported its next CVE reports what your colleague typed. Nothing in apply_recipe's list takes a
managed entry back out, so this is worth catching now rather than at the phase that would inherit
it. Name the module and the row.

A BOOT MOVE ATTEMPTED IN THIS PHASE IS ALSO `again`. The JDK has not been raised yet and the Boot
floor from JDK 17 up names a line whose parent declares a Java version this module has not reached,
so a Boot raise here is either a build that cannot resolve or a migration run in the dark, ahead of
the compile that exists to catch it. It belongs to after-pins, and saying so is the objection.

AN EMPTY DIFF IS A CORRECT ANSWER AND SO IS NOTHING-OUTSTANDING. Where the set already resolves at
or above the floor, nothing is written into this module and nothing should be. Answering `again`
for lack of a visible edit is how a Boot module acquires a version it did not need, which is the one
regression this phase can introduce on its own.

`replan` IS FOR A PLAN THAT AIMED AT A MODULE WHOSE PARENT OWNS THE NUMBER, or at a placement this
module does not have. A property override planned for an importing module is the second of those:
carried out, it writes a property nothing reads, and the run reports a working tree that changed
beside a floor that did not move. Repeating it spends the budget on an edit the next stage removes.
