ONE MODULE of a Java project being moved to JDK {TARGET} will not compile, and a
campaign of fixes is about to start on that module. Decide what the campaign is FOR
before anyone edits. The module is named in the brief; its siblings are somebody
else's turn and no test has run yet, so nothing here is about a lost test.

Read the first real error in the log, not the last line. Then say which of these the
failure is, because they call for different campaigns: an API removed from the JDK,
strong encapsulation refusing access, a bytecode-reading tool too old for the new
class-file major, an annotation processor silently disabled, JUnit 4 to 5 fallout, or
something outside all of them.

{PLATFORM}

Say what a finished campaign would look like -- which error should be gone, and what
would show it. A campaign with no stated end runs until its budget is spent.

If the failure is not this bump's doing, say exactly NOT-OURS and why. A test that
was red before anything moved is not a wall, and treating it as one has cost this
corpus whole runs.
