                A colleague was asked to raise these versions on a project moving from JDK {FROM} to
                JDK {TARGET}. {WHEN}

{PINS}

                Call declared_versions and read what the build files say. That is the whole question: is
                every pin at or above its floor, in every module, or is one outstanding.

                A dependency a module does not use is satisfied -- these are floors, not
                requirements, and adding one would be a different bump. A version above the floor is
                satisfied. Only a version BELOW the floor is outstanding.

                declared_versions answers per module. A pin met in one module says nothing about a sibling,
                and this project has been wrong about exactly that: the check used to read the whole
                tree at once and report the first version it found anywhere, so one module could
                stand in for six that were still below the floor. Read the rows.

                You hold the loop. Answer with one of three words.

                `done` when nothing is outstanding, or when what remains is genuinely unreachable and
                your colleague said so.

                UNREACHABLE IS A REAL ANSWER AND YOU CAN CHECK IT. apply_recipe runs the OpenRewrite
                MAVEN plugin and this phase holds no other way to write, so on a Gradle module no
                pin here can be applied by anyone, however many times you ask. Call build_system.
                If it says the module is Gradle and your colleague said so too, that is `done`: name
                the pin and say it is unapplied and why, so the bump phase, which does hold an
                editor, picks it up. Answering `again` there sends someone back to a tool that
                cannot start, and it happened twice in one phase before this paragraph existed.

                `again: <which pins, in which modules, and what to try>` when the plan was right and
                the execution fell short. Name them from declared_versions rather than from the diff, and
                say something the next attempt can act on: which recipe suits where that version
                actually lives. An objection without that is the same as `done`.

                `replan: <why the plan cannot work>` when the plan itself was wrong -- it named the
                wrong module, or a placement that does not exist in this project, or an artifact this
                project does not use. Repeating a wrong plan spends the whole budget on it, so this
                word exists to stop that. Use it for the plan, not for a bad attempt at a good plan.
