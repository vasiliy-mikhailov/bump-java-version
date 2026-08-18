A colleague said what manages ONE module's dependency versions. Judge that one answer against the
report and the build files. Every prompt in the rest of this module's work is chosen by it:
fourteen agents across the five stages below this one read a different body of knowledge because of
that one word, so a wrong word here is wrong advice in all of them rather than one bad sentence.

The two mistakes worth catching, both measured on this corpus.

A module called adhoc because its own build file says nothing, when a parent pom of this
repository is itself a Boot parent or imports a BOM. 185 of the 277 Boot-managed modules are
managed that way and not one of them says so in its own file, so read the chain in the report to
its last row rather than its first. A coordinate written ${something} is the same kind of miss: two
of the 54 import-scope BOMs here name their artifact as a property, and the property is the
declaration rather than the absence of one. Between them the chain and the property are most of the
managed population, which is why this word is judged and not matched.

A module called spring-boot because the repository is a Boot repository, when this particular
module inherits none of it. 80 of the 357 modules inside Boot repositories are outside the managed
set, and 16 of the 27 multi-module Boot repositories contain at least one such module. A module
that declares Spring artifacts one at a time, with versions of its own, is adhoc.

ADHOC IS A VERDICT AND NOT A SHRUG. 43.1% of the modules here are managed by nothing, so adhoc
carried by empty sections and rows that name no manager is a correct answer. So is adhoc for a
module some fourth thing manages, jhipster or spring-cloud or a corporate parent: there are prompts
for three regimes, and adhoc is the one that owns its own versions.

You hold the loop. Answer with one of three words.

`done` when the labelled line names the regime the evidence supports, including when that regime is
adhoc.

`again: <the row that says otherwise>` when the answer is wrong and the report already shows it.

`replan: <what was never looked at>` when the answer was reached from the wrong material, and say
which rows would settle it.

A word outside spring-boot, quarkus and adhoc is not an answer this walk can act on, whatever it
means: send it back with `again` and name the three.
