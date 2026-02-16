# Jenkins Setup Guide

## Required Credentials

The `Jenkinsfile` requires the following credentials to be configured in **Manage Jenkins -> Credentials**.

### 1. GPG Signing Keys
Used to sign artifacts before uploading to Maven Central.

*   **`gpg-key-name`** (Secret Text): Your GPG Key ID.
    *   **How to find**: Run `gpg --list-secret-keys --keyid-format LONG`.
    *   Look for the line starting with `sec`. The ID is the part after the slash (e.g., `3C7E...` in `ed25519/3C7E...`).
*   **`gpg-passphrase`** (Secret Text): The password you use for your GPG key.
    *   **How to find**: This is known only to you (the one you type when signing).
*   **`gpg-secret-key`** (Secret File): The actual private key file.
    *   **How to generate**: Run `gpg --export-secret-keys -a <YOUR_KEY_ID> > secring.asc`.
    *   Upload the `secring.asc` file.

### 2. Maven Central (Sonatype Portal)
Used for the `central-publishing-maven-plugin` (Modern Portal).

*   **`central-user`** (Secret Text): The "Username" from your generated token.
*   **`central-token`** (Secret Text): The "Password" from your generated token.
    *   **How to find**:
        1.  Log in to [https://central.sonatype.com/account](https://central.sonatype.com/account).
        2.  Click **Generate Token**.
        3.  Save the **Username** and **Password**.

### 3. OSSRH (Legacy Sonatype) / Fallback
Used if falling back to standard `mvn deploy` via `nexus-staging-maven-plugin`.

*   **`maven-central-username`** (Secret Text): Your Sonatype JIRA username (or same as `central-user` if migrating).
*   **`maven-central-password`** (Secret Text): Your Sonatype JIRA password (or same as `central-token`).

## Initial Setup Commands

Run these locally to get your values:

```bash
# Get Key ID
gpg --list-secret-keys --keyid-format LONG

# Export Secret Key
gpg --export-secret-keys -a <YOUR_KEY_ID> > secring.asc
```
