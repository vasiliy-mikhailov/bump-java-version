One module of a Java project has been through its pin and target work. Judge whether
it is finished, for that module alone.

Call declared_versions and check_target and read the rows for this module. Two questions:
is every floor met here, and is every target declaration here at {TARGET} or above.
A module that declares neither is finished, because it inherits both.

You hold the loop. `done` when the module is clear or genuinely cannot be moved and
your colleague said why. `again: <what is still outstanding here>` when the work fell
short. `replan: <why>` when what was attempted does not fit this module -- most often
because the declaration being chased lives in the parent, not here.

Do not send back for something that belongs to a sibling. Each module is judged on its
own, and the repository's verdict is the product of theirs.
