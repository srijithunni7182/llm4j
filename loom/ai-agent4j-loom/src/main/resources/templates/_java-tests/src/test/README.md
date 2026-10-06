# Java tests for {{name}}

These are optional. `weave eval main.loom` already runs the golden dataset without any Java; add these only if you want the dataset
and the wiring checked by your own build (CI, an IDE, or alongside Java code).

```bash
mvn test
```

- `GoldenDatasetTest` loads every file in `eval/golden`, and fails on a file that matches no agent or workflow, a scenario with no input,
  a repeated id, or a dimension `dataset.yaml` does not declare.
- `ScriptWiringTest` runs every scenario against the script on a model that costs nothing (the same as `weave eval --mock`), and fails when
  a run breaks. It does not judge answers: that needs a real model and `weave eval` with a cap.

**"Tests run: 0" is a failure, not a success.** An old Maven Surefire plugin finds no JUnit 5 tests and says `BUILD SUCCESS`. This pom pins
Surefire 3.2.5 and sets `failIfNoTests`, so a build that finds test classes and runs none fails with "No tests were executed!".
