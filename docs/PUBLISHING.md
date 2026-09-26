# Publishing `ai-agent4j` to Maven Central

This guide outlines the **modern** process for publishing to Maven Central via the **Sonatype Central Portal** (2024+ workflow).

## Prerequisites

### 1. Sonatype Central Account

- Sign up at [central.sonatype.com](https://central.sonatype.com/)
- **Verify your namespace**: `io.github.srijithunni7182`
  - Go to "Namespaces" and add `io.github.srijithunni7182`
  - Verify ownership by creating a public GitHub repo matching the verification code they provide

### 2. Generate User Token

- In the Central Portal, go to **"View Account"** → **"Generate User Token"**
- Save the **username** and **password** (this is your deployment credential)

### 3. GPG Key

You already have this set up:
- **Key ID**: `8E195D64FE1D7BFC8092B118902BDC3C6B9FF68E`
- **Distributed to**: `keyserver.ubuntu.com`

### 4. Configure Maven Settings

Edit `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_CENTRAL_TOKEN_USERNAME</username>
      <password>YOUR_CENTRAL_TOKEN_PASSWORD</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>release</id>
      <properties>
        <gpg.executable>gpg</gpg.executable>
        <gpg.passphrase>YOUR_GPG_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
</settings>
```

## Publishing Steps

### Manual Deployment

1. **Update Version** (remove `-SNAPSHOT` from `pom.xml`)

2. **Deploy Core Library**:
   ```bash
   cd ai-agent4j
   mvn clean deploy -P release
   ```

3. **Deploy Addons Library**:
   ```bash
   cd ../ai-agent4j-addons
   mvn clean deploy -P release
   ```

4. **Verify in Central Portal**:
   - Go to [central.sonatype.com](https://central.sonatype.com/)
   - Check **"Deployments"** tab
   - The plugin is configured for `autoPublish`, so it will automatically release after validation

5. **Wait for Sync**:
   - Maven Central sync takes 10 minutes to a few hours
   - Check [search.maven.org](https://search.maven.org/) for your artifacts

### Automated (GitHub Actions)

Create `.github/workflows/maven-publish.yml`:

```yaml
name: Publish to Maven Central

on:
  release:
    types: [created]

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
          server-id: central
          server-username: MAVEN_USERNAME
          server-password: MAVEN_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: MAVEN_GPG_PASSPHRASE

      - name: Publish ai-agent4j
        run: |
          cd ai-agent4j
          mvn -B clean deploy -P release
        env:
          MAVEN_USERNAME: ${{ secrets.CENTRAL_TOKEN_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.CENTRAL_TOKEN_PASSWORD }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}

      - name: Publish ai-agent4j-addons
        run: |
          cd ai-agent4j-addons
          mvn -B clean deploy -P release
        env:
          MAVEN_USERNAME: ${{ secrets.CENTRAL_TOKEN_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.CENTRAL_TOKEN_PASSWORD }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
```

**Required GitHub Secrets**:
- `CENTRAL_TOKEN_USERNAME`
- `CENTRAL_TOKEN_PASSWORD`
- `GPG_PRIVATE_KEY` (export with: `gpg --armor --export-secret-key 8E195D64FE1D7BFC8092B118902BDC3C6B9FF68E`)
- `GPG_PASSPHRASE`

## Troubleshooting

- **401 Unauthorized**: Check your Central Portal token credentials
- **GPG Signing Failed**: Ensure GPG passphrase is correct in `settings.xml`
- **Validation Errors**: Check that all required metadata (licenses, developers, SCM) is present in `pom.xml`
