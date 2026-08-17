THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and the rows above sit on both sides of
that. Boot itself is there, and so are members of the set it manages and artifacts it manages
nothing about, so this is no longer one number to read. It is one number, the manager's, and then a
question of order about everything else.

AN EMPTY DIFF IS A COMMON CORRECT OUTCOME OF THIS PHASE. Compliance is measured against the packages
that RESOLVED, not against what this module declares, so a floor Boot already satisfies transitively
is met with nothing written into this module. Answering `again` because you cannot see an edit is
how a Boot module ends up with a version written onto a managed artifact, which is the regression
this phase is most able to introduce. Read the numbers, not the diff.

THE MANAGER'S NUMBER IS THE PARENT, THE IMPORT OR THE PLUGIN LINE, AND 3.5 IS NOT A READING. 3.5.4
is twelve patches below the floor and the pom cannot tell you which of the two it is, so the digits
are the check and the line is not. Where this module names no Boot at all and its starter rows read
(managed by something this module does not name), the number lives in an in-repo parent pom with its
own row a few modules up and its own turn in this walk, and nothing written here is the right
outcome.

AN OVERRIDE OF A MEMBER IS JUDGED BY ITS ORDER, NOT BY WHETHER IT HAPPENED. Boot's ceiling on this
hop is the Boot floor in the list above: the last of the 2.x line below JDK 17, the 3.5 line from 17
up. Where Boot has landed there and a member row still reads below its floor against what actually
resolved, the manager has gone as far as it goes and the override is the only move left, so it is
`done`. Where the override was made and Boot was not moved, or was moved after it, that is `again`:
it preempted a raise that would have carried the member, the build is green, the scan is quiet, and
the artifact now holds that number while the set moves past it at every later raise. Say in the same
breath that raising Boot afterwards does not undo it, because nothing in apply_recipe's list takes a
managed entry back out, so the next attempt has to remove the entry as well as move the manager.

WHAT RESOLVED IS THE COMPARISON, AND inspect_jar IS HOW YOU SEE IT, with the caveat this phase makes
sharp: it reports what is present in the local repository, which is what has been resolved here
before, and after a Boot raise the old member and the new one are both present. An old Tomcat or an
old jackson in that list is not proof the set is still on it. The Boot number settles it.

THE WRONG-BUT-GREEN ANSWER TO CATCH IS A VERSION ON AN ARTIFACT WITH NO ROW AT ALL. This phase is
also started by a vulnerability, and on a Boot module the vulnerable artifact is often jackson,
snakeyaml, logback or netty, members of the set that the list above does not name. A version written
onto one of those clears the scanner and takes the artifact out of the set for good, so the row that
would have reported its next CVE reports what your colleague typed instead. That is `again` with the
module and the row named, however satisfied every floor now reads.

DO NOT SEND BACK A PLAIN RAISE. archunit and archunit-junit5, the jacoco plugin, the javax.xml.bind
and javax.annotation rows and the Gradle wrapper are outside anything Boot manages, so a number
written onto this module's own declaration for them is the finished shape of that pin and not an
override to object to. The version column is what says which side a row is on, since Boot imports
BOMs of its own; confusing the two costs the phase the floors it was actually able to meet.

A tomcat-embed-core VERSION IN THIS MODULE IS THE REGRESSION WEARING THE LIST'S OWN CLOTHES. Where
that row appears it carries its reason: it names the 9.0 line and it is for projects where Spring is
absent, since Boot brings a container of its own. On a module the 3.5 line has reached, the set is
already a major ahead of that row. On a module still on Boot 2 it is not a pin either, because
Tomcat 9 to Tomcat 10 is the jakarta rename.

`done` COVERS A CEILING REACHED. The Boot floor above is the top of what the free tooling reaches:
the last of the 2.x line below JDK 17, because Boot 3 needs Java 17, and the 3.5 line from 17 up,
because the only recipe for 4.1 is proprietary. A module sitting at that floor with a CVE still
standing is a finished phase and a reported fact, and a colleague who said so has answered
correctly.

`again` ALSO COVERS THE NUMBER THAT WAS TYPED RATHER THAN MIGRATED. A parent moved to a version
inside its own line by hand stops at whatever was typed, and the patch releases past it are where
the CVE fixes are, so a Boot 3 row left below the floor is outstanding even though it moved. Name
the line move rather than the number.

`replan` IS FOR A PLAN THAT ORDERED THE OVERRIDES AHEAD OF THE MANAGER, or aimed at this module when
the Boot version is owned by a different module of this repository. Repeating either spends the
budget writing versions the set will have to be detached from.
