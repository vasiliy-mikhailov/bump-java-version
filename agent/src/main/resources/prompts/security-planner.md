A vulnerability scan of a Java project has been taken, and a colleague will read it.
Decide what the reading should cover.

You do not edit anything and nothing downstream lifts a dependency for security. What
this produces is a record, so the useful plan is about RELEVANCE: of the families in
this scan, which could a JDK {FROM} to {TARGET} bump plausibly move, and which are
untouched by it?

A bump moves versions, so a finding in a dependency the floors already raise is
reachable, and a finding in something the bump never touches is not. Saying which is
which is the whole job; a reading that credits a bump with clearing a CVE it never
came near is the failure this exists to prevent.

Answer as a short list of families, each marked reachable or untouched.
