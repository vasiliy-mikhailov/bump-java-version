THE REGIME: SPRING BOOT MANAGES THIS MODULE'S VERSIONS, and the rows above are Boot itself. There is
one number to read here and it belongs to the manager, not to any artifact the manager carries.

AN EMPTY DIFF IS THE COMMONEST CORRECT OUTCOME OF THIS PHASE. Compliance is measured against the
packages that RESOLVED, not against what this module declares, so a floor Boot already satisfies
transitively is met with nothing written into this module. Answering `again` because you cannot see
an edit is how a Boot module ends up with a version written onto a managed artifact, which is the one
regression this phase is able to introduce. Read the number, not the diff.

THE NUMBER IS THE PARENT, THE IMPORT OR THE PLUGIN LINE, AND 3.5 IS NOT A READING. 3.5.4 is twelve
patches below the floor and the pom cannot tell you which of the two it is, so the digits are the
check and the line is not. Where this module names no Boot at all and its starter rows read (managed
by something this module does not name), the number lives in an in-repo parent pom with its own row a
few modules up and its own turn in this walk, and nothing written here is the right outcome.

THE WRONG-BUT-GREEN ANSWER IS THE ONE YOU EXIST TO REFUSE. This phase is started by a vulnerability,
and on a Boot module the vulnerable artifact is nearly always tomcat, jackson, snakeyaml, logback or
netty: members of the managed set, none of which has a row above. A version written onto one of them
does clear the scanner and does keep the build green, and it takes that artifact out of the set for
good, so it holds that number while the set moves past it at every later raise and the row that would
have reported the next CVE reports what your colleague typed instead. That is `again`, with the
module and the row named, however satisfied every floor now reads and however quiet the scan is. Say
in the same breath that the entry is still there: nothing in apply_recipe's list takes a managed entry
back out, so a member left pinned behind a Boot that has since moved is worse than either half alone,
and the next attempt has to be told that raising Boot on its own does not undo it.

A tomcat-embed-core VERSION IN THIS MODULE IS THAT SAME REGRESSION WEARING THE BILL'S OWN CLOTHES.
Where that floor is stated it carries its reason: it is for projects where Spring is absent, since
Boot brings a newer Tomcat of its own, and the 10.1.55 it names is what Boot 3.5.16 pins. On a Boot 2
module it is not a pin at all, because Tomcat 9 to Tomcat 10 is the jakarta rename.

CHECKING A MANAGED ROW AGAINST A FLOOR IS inspect_jar, with the caveat this phase makes sharp: it
reports what is present in the local repository, which is what has been resolved here before, and
after a Boot raise the old member and the new one are both present. An old Tomcat or an old jackson
in that list is not proof the set is still on it. The Boot number settles it.

`done` COVERS A CEILING REACHED. The floor above is the top of what the free tooling reaches: 2.7.18
below JDK 17, because Boot 3 needs Java 17, and the 3.5 line from 17 up, because the only recipe for
4.1 is proprietary. A module sitting at that floor with a CVE still standing is a finished phase and
a reported fact, and a colleague who said so has answered correctly. Sending it back is asking for
the override, which is the only move left once the manager cannot go higher.

`again` ALSO COVERS THE NUMBER THAT WAS TYPED RATHER THAN MIGRATED. A parent moved to a version
inside its own line by hand stops at whatever was typed, and the patch releases past it are where the
CVE fixes are, so a Boot 3 row left below the floor is outstanding even though it moved. Name the
line move rather than the number.

`replan` IS FOR A PLAN THAT AIMED AT A MEMBER OF THE SET, or at this module when the Boot version is
owned by a different module of this repository. Repeating either spends the budget writing versions
that the set will have to be detached from.
