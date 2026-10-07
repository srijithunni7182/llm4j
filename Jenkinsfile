// The same stages as .github/workflows/build-and-deploy.yml, for a Jenkins that builds this repository.
// See docs/TESTING_STRATEGY.md (what each build runs) and docs/PUBLISHING.md (how a release is made).
pipeline {
    agent any

    tools {
        maven 'maven'       // Adjust version as needed for your Jenkins environment
        jdk 'JDK 17'        // Adjust version as needed
    }

    environment {
        // Central Portal user token (Account -> Generate User Token), and the GPG key that signs the release
        CENTRAL_USERNAME = credentials('maven-central-username')
        CENTRAL_PASSWORD = credentials('maven-central-password')
        MAVEN_GPG_PASSPHRASE = credentials('gpg-passphrase')
        // NVD API key for OWASP Dependency Check (run on demand, see below)
        NVD_API_KEY = credentials('nvd-api-key')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build and test') {
            steps {
                // verify, not test: the JaCoCo coverage rules run at verify. The default build is hermetic and fast.
                sh 'mvn -B verify'
            }
        }

        stage('Release check') {
            steps {
                // sources and javadoc jars, unsigned, tests already ran
                sh 'mvn -B -Prelease -Dgpg.skip=true -DskipTests verify'
            }
        }

        stage('Code Quality') {
            steps {
                sh 'mvn -B -pl ai-agent4j,ai-agent4j-addons,eval4j,eval4j-report spotless:check'
                // Dependency check is not run on every build (NVD rate limits). On demand:
                //   mvn org.owasp:dependency-check-maven:check -DnvdApiKey=$NVD_API_KEY
            }
        }

        stage('Extended tests') {
            when { triggeredBy 'TimerTrigger' }
            steps {
                // integration and fragile tests, engram, tantrik and the example applications
                sh 'mvn -B -fae -Pextended verify'
                dir('loom/ctk') {
                    sh 'mvn -B test'
                }
            }
        }

        stage('Deploy to Central') {
            when { buildingTag() }
            steps {
                input message: 'Deploy to Maven Central?', ok: 'Deploy'
                withCredentials([file(credentialsId: 'gpg-secret-key', variable: 'GPG_SECRET_KEYring')]) {
                    sh 'gpg --batch --import $GPG_SECRET_KEYring'
                    // one signed bundle of every library; left for review in the Central Portal unless -Dcentral.autoPublish=true
                    sh 'mvn -B -Prelease deploy --settings settings.xml'
                }
            }
        }
    }

    post {
        failure {
            echo 'Build failed!'
        }
    }
}
