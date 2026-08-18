A colleague was asked which modules of this project the bump should leave alone, and
answered. Judge whether the answer is safe.

The asymmetry is the whole job. Keeping a module that should have been skipped costs
a wasted diff. Skipping a module that should have been kept leaves it on the old
target, and the gate takes the LOWEST module, so it fails the entire bump and the
evidence points at the wrong place.

So a skip needs evidence you can see, and you have the tools to look. Read the module.
If the reason is a directory name, that is not evidence.

Answer `done` when the list is safe, `again: <which skip is unevidenced>` when a skip
should be dropped or one is missing, or `replan: <why>` if the answer did not address
the question asked.
