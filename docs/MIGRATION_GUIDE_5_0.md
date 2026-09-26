# Migration Guide: 0.1.x/SNAPSHOT to 5.0

This guide explains how to migrate existing projects to the stabilized 5.0 coordinate scheme.

## 1) Maven Coordinate Changes

Update dependencies from:

- `io.github.llm4j:ai-agent4j:0.1.0-SNAPSHOT`
- `io.github.llm4j:ai-agent4j-addons:0.1.0-SNAPSHOT`

to:

- `io.github.srijithunni7182:ai-agent4j:5.0`
- `io.github.srijithunni7182:ai-agent4j-addons:5.0`

## 2) Maven Example

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j</artifactId>
    <version>5.0</version>
</dependency>
```

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-addons</artifactId>
    <version>5.0</version>
</dependency>
```

## 3) Gradle Example

```gradle
implementation("io.github.srijithunni7182:ai-agent4j:5.0")
implementation("io.github.srijithunni7182:ai-agent4j-addons:5.0")
```

## 4) Validation Checklist

- Confirm your Java runtime is 17+.
- Run `mvn -DskipTests compile`.
- Run core unit tests in your project.
- If you use provider integrations, verify API keys and integration profiles.

## 5) Notes

- Java package names remain `io.github.llm4j.*`; only Maven coordinates changed.
- If you consumed SNAPSHOT artifacts, pin explicitly to `5.0` for reproducible builds.
