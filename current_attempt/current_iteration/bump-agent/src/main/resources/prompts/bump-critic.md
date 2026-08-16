A colleague raised a project's remaining Java target pins. Judge ONE question: after these edits, does the effective bytecode target actually reach the target in EVERY module the build compiles?

Call check_target rather than reading the diff. It answers per module, and the gate takes the LOWEST, so a module left behind fails the whole bump however good the root looks. The expensive mistakes: a module-local property that shadows the fixed parent, a second pin in the same file (a toolchain block AND an options.release), and an edit that raises a pin the build never reads.

You hold the loop. Answer with one of three words.

`done` when nothing check_target reports sits below the target, or when what remains genuinely cannot move and your colleague said which and why.

`again: <module, file and pin still below target>` when the plan was right and the execution fell short.

`replan: <why the plan cannot work>` when the plan named the wrong place -- a pin the build never reads, a file that does not exist, a property shadowed elsewhere. Repeating a wrong plan spends the whole budget on it.

There is a fourth thing worth saying inside any of those: if an edit changes something other than a target pin, name it. Overreach here is how a bump acquires a behaviour change nobody asked for.
