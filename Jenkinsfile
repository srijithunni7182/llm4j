pipeline {
    agent any

    tools {
        maven 'maven'       // Adjust version as needed for your Jenkins environment
        jdk 'JDK 17'        // Adjust version as needed
    }

    environment {
        // GPG Key details for signing - should be configured in Jenkins Credentials
        GPG_KEY_NAME = credentials('gpg-key-name')
        GPG_PASSPHRASE = credentials('gpg-passphrase')
        // Central Portal Token
        CENTRAL_TOKEN = credentials('central-token')
        CENTRAL_USER = credentials('central-user')
        // NVD API Key for OWASP Dependency Check
        NVD_API_KEY = credentials('nvd-api-key')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Core') {
            steps {
                dir('ai-agent4j') {
                    sh 'mvn -version'
                    sh 'mvn clean install -DskipTests -DnvdApiKey=${NVD_API_KEY}'
                }
            }
        }

        stage('Build Addons') {
            steps {
                dir('ai-agent4j-addons') {
                    sh 'mvn clean install -DskipTests -DnvdApiKey=${NVD_API_KEY}'
                }
            }
        }

        stage('Unit Tests & Coverage') {
            steps {
                parallel(
                    "Core Tests": {
                        dir('ai-agent4j') {
                            sh 'mvn test jacoco:report'
                            // Enforce coverage check
                            sh 'mvn jacoco:check'
                        }
                    },
                    "Addons Tests": {
                        dir('ai-agent4j-addons') {
                            sh 'mvn test jacoco:report'
                            // Enforce coverage check
                            sh 'mvn jacoco:check'
                        }
                    }
                )
            }
        }

        stage('Code Quality') {
            steps {
                parallel(
                    "Core Quality": {
                        dir('ai-agent4j') {
                            // Spotless check
                            sh 'mvn spotless:check'
                            // Dependency check (can be slow, maybe separating?)
                            // sh 'mvn org.owasp:dependency-check-maven:check'
                        }
                    },
                    "Addons Quality": {
                        dir('ai-agent4j-addons') {
                            sh 'mvn spotless:check'
                        }
                    }
                )
            }
        }

        stage('Deploy to Central') {
            when {
                branch 'main'
            }
            steps {
                script {
                    withCredentials([
                        string(credentialsId: 'maven-central-username', variable: 'CENTRAL_USERNAME'),
                        string(credentialsId: 'maven-central-password', variable: 'CENTRAL_PASSWORD'),
                        file(credentialsId: 'gpg-secret-key', variable: 'GPG_SECRET_KEYring')
                    ]) {
                        // Import GPG key
                        sh 'gpg --batch --import $GPG_SECRET_KEYring'
                        
                        dir('ai-agent4j') {
                            sh 'mvn deploy -P release -DskipTests --settings ../settings.xml'
                        }
                        dir('ai-agent4j-addons') {
                            sh 'mvn deploy -P release -DskipTests --settings ../settings.xml'
                        }
                    }
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
