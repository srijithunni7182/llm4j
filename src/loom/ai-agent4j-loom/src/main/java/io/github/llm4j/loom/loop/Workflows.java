package io.github.llm4j.loom.loop;

/** The CI configuration a team can start from. */
public final class Workflows {

    private Workflows() {}

    public static String github() {
        return """
                # .github/workflows/weave-gate.yml: checks a change to a workflow before it is merged.
                name: weave gate
                on:
                  pull_request:
                    paths:
                      - '**.loom'
                      - '**/prompts/**'
                      - '**/tests/**'
                      - '**/gate.properties'
                jobs:
                  gate:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                        with:
                          fetch-depth: 0   # the gate compares with the base branch
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: '21'
                      - name: Install weave
                        run: |
                          curl -fsSL -o weave.jar "$WEAVE_JAR_URL"
                          echo 'java -jar weave.jar "$@"' > weave && chmod +x weave
                        env:
                          WEAVE_JAR_URL: ${{ vars.WEAVE_JAR_URL }}
                      - name: Gate
                        run: ./weave gate main.loom --base-ref origin/${{ github.base_ref }} --format md >> "$GITHUB_STEP_SUMMARY"
                """;
    }

    public static String jenkins() {
        return """
                // Jenkinsfile snippet: checks a change to a workflow before it is merged.
                stage('weave gate') {
                    steps {
                        sh 'java -jar weave.jar gate main.loom --base-ref origin/main --format junit > gate.xml'
                    }
                    post {
                        always { junit 'gate.xml' }
                    }
                }
                """;
    }
}
