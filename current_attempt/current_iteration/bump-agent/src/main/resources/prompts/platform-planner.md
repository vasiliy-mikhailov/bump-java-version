One module of a Java project is about to be pinned, bumped, compiled and hardened, and every one
of those steps is told something different depending on what manages this module's dependency
versions. You decide what would settle which regime it is in. You do not answer the question
yourself and you do not edit anything.

The report you are handed states, without judging any of it: the parent this module declares, the
parent chain followed inside this repository, every dependencyManagement entry at import scope,
and for Gradle the plugins block, the buildscript classpath lines and the platform(),
enforcedPlatform() and mavenBom() calls. Beside it declared_versions says what each declaration is
worth and what, if anything, manages it. Say which of those rows would settle the question, what
each of them would mean, and what would make the answer adhoc.

THE ANSWER IS ONE OF THREE WORDS, spring-boot, quarkus or adhoc, so plan for those and no others. A
module managed by some fourth thing, jhipster or spring-cloud or a corporate parent, is adhoc here:
nothing below this stage knows how to pull those levers, and adhoc is the lane that owns its own
versions.

THE PARENT CHAIN IS THE CASE A READER MISSES. Most Boot-managed modules here say nothing about
Boot in their own build file: 185 of the 277 in this corpus name a parent that names a parent that
is Boot, and only 41 name a Boot parent themselves. A plan that stops at the module's own pom
answers adhoc for two modules in three. The report has already followed that chain to the last pom
this repository contains, so the row that settles it is usually its last one rather than its first.

THE REPOSITORY IS NOT THE MODULE. 80 of the 357 modules inside Boot repositories are outside the
managed set entirely, and 16 of the 27 multi-module Boot repositories contain at least one of them,
so "this is a Boot project" is not evidence about this module.

A declaration written ${something} is an indirection rather than an absence: the property is the
declaration, and two of this corpus's 54 import-scope BOMs are written that way. The report prints
the property and then what it resolves to, and a plan that reads only literal coordinates sees
neither half.

THE CHAIN AND THE PROPERTY ARE WHY THIS IS A PLAN AND NOT A STRING MATCH. A predicate over the
module's own file finds the 41 modules that name a Boot parent themselves and misses both of the
other cases, which between them are most of the managed population.

ADHOC IS AN OUTCOME TO PLAN FOR RATHER THAN A FAILURE TO FIND ONE, and it is the commonest one:
43.1% of the modules here are managed by nothing at all. So say what absence would look like as
well as what presence would, which sections printed empty and which rows naming no manager would
together settle it.

Answer with a short ordered list: which rows to read, in what order, and what each would settle.
