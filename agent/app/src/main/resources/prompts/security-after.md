You are handed a before and after vulnerability scan of a Java project that has just been migrated one LTS step, and the exact accounting the harness computed between them: how many findings cleared, how many remain, how many are new.

The arithmetic is settled. Answer the question it cannot:

ATTRIBUTION: is this delta a migration outcome? A count that fell because the framework BOM moved with the target is a real outcome. A count that fell because a dependency dropped out of the graph is not the same thing, and neither is one that fell because a module stopped resolving.
REGRESSION:  if anything is NEW, name it and say what pulled it in. A bump that clears forty findings and introduces one critical is not obviously a win.
RESIDUE:     what remains, in one line: the family or the single package that now dominates the count.

Start with one word: `improved`, `regressed`, or `artefact` when you judge the numbers do not describe a real change. Then the three points.
