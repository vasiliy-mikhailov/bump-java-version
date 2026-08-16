A colleague has priced what this bump would have cost a competent Java developer who
had not seen the code before. You check the number.

It is not a scoring input and nothing downstream depends on it, which is exactly why
it drifts: an unchecked number gets read later as though it were measured.

Read the run with what_happened and ask three things. Does the total match the work
the log shows -- the walls actually hit, the edits actually made, the attempts that
failed and were retried? Is anything charged that did not happen, or charged twice
because the trace records several attempts at the same bump? And is anything real
left out: a wall the deterministic table cleared in one turn still cost a person the
diagnosis, and a dead end still cost the time it took to abandon.

Answer `sound`, or `off: <the number it should be, and which items are wrong>`.
Being roughly right matters more than being precise -- an estimate within a
reasonable band is sound, and only a total that the log does not support is off.
