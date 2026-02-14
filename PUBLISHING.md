# Publishing `ai-agent4j` to Maven Central

This guide outlines the steps to publish the `ai-agent4j` library to Maven Central via Sonatype OSSRH.

## Prerequisites

1.  **Sonatype OSSRH Account**:
    - Sign up at [issues.sonatype.org](https://issues.sonatype.org/).
    - Create a "New Project" ticket to claim your namespace.
    - **Namespace**: `io.github.llm4j` (if you own the GitHub org) or `io.github.srijithunni7182` (if you own the user).
    - *Note*: If using `io.github.llm4j`, you must own https://github.com/llm4j. If you only own `srijithunni7182/llm4j`, you should change the Group ID in `pom.xml` to `io.github.srijithunni7182`.

2.  **GPG Key**:
    - You need a GPG key to sign artifacts.
    - Generate one: `gpg --gen-key`
    - Distribute public key: `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`

3.  **Maven Settings (`~/.m2/settings.xml`)**:
    - Add your OSSRH credentials and GPG passphrase.

    ```xml
    <settings>
      <servers>
        <server>
          <id>ossrh</id>
          <username>your-jira-username</username>
          <password>your-jira-password</password>
        </server>
      </servers>
      <profiles>
        <profile>
          <id>ossrh</id>
          <activation>
            <activeByDefault>true</activeByDefault>
          </activation>
          <properties>
            <gpg.executable>gpg</gpg.executable>
            <gpg.passphrase>your-gpg-passphrase</gpg.passphrase>
          </properties>
        </profile>
      </profiles>
    </settings>
    ```

## Publishing Steps

1.  **Prepare Release**:
    - Update versions in `pom.xml` (remove `-SNAPSHOT`).

2.  **Deploy Core Library (`ai-agent4j`)**:
    Since `ai-agent4j-addons` depends on `ai-agent4j`, you **MUST** publish the core library first.

    ```bash
    cd ai-agent4j
    mvn clean deploy -P release
    ```

3.  **Deploy Addons Library (`ai-agent4j-addons`)**:
    Once the core library is staged/released, publish the addons:

    ```bash
    cd ../ai-agent4j-addons
    mvn clean deploy -P release
    ```

4.  **Release**:
    - Log in to [s01.oss.sonatype.org](https://s01.oss.sonatype.org/).
    - Go to "Staging Repositories".
    - Select your repository (check content to confirm), click "Close".
    - If checks pass, click "Release".

## Automated Release (CI/CD)

For automated publishing via GitHub Actions, see `.github/workflows/publish.yml` (to be created).
