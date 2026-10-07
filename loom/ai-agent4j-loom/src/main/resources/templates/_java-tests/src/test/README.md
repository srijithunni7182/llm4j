# Tests for {{name}}

`mvn test` runs the golden dataset as JUnit tests, with no key and no cost.

- `GoldenDatasetTest` loads every file in `src/test/resources/eval/golden` and fails on a file that matches no agent or workflow, a scenario with no input,
  a repeated id, or a dimension `dataset.yaml` does not declare.
- `ScriptWiringTest` makes every scenario its own test case and runs it through `src/main/resources/main.loom` on a model that costs nothing (the same as
  `weave eval --mock`). A case fails when its run breaks. It does not judge answers: that needs a real model.

To judge answers for real, run `weave eval src/main/resources/main.loom --max-tokens 200000` (it says what it will do and asks first). The checks that need no judge
(`expected_output_contains`, `expected_output_not_contains`, `expected_tools`) are decided by code in that run.

Tests for your own tasks, tools or screen are written in your own project and run by its own build; `weave` and this module do not run or check them. They go in this project's `src/test/java` and run in the same `mvn test`.

**"Tests run: 0" is a failure, not a success.** An old Maven Surefire plugin finds no JUnit 5 tests and says `BUILD SUCCESS`. This pom pins
Surefire 3.2.5 and sets `failIfNoTests`, so a build that finds test classes and runs none fails with "No tests were executed!".
