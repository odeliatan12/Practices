// ─────────────────────────────────────────────────────────────────────────────
// OMS Pipeline — runs on every push to main or a pull request
//
// Stages:
//   1. Checkout     — Git pulls the code
//   2. Test         — mvn test for shared-lib, order-service, api-gateway
//   3. Build JARs   — mvn package (skip tests, already done)
//   4. Build Images — docker build for order-service and api-gateway
//   5. Push Images  — docker push to GitHub Container Registry (ghcr.io)
//   6. Deploy       — kubectl rolling update on the target cluster
//
// Required Jenkins credentials (add in Manage Jenkins → Credentials):
//   registry-credentials  — Username/Password:
//                             Username = your GitHub username
//                             Password = GitHub PAT with write:packages scope
//   kubeconfig            — Secret File containing your kubeconfig
// ─────────────────────────────────────────────────────────────────────────────

pipeline {

    agent any

    // ── Environment variables ─────────────────────────────────────────────────
    environment {
        // GitHub Container Registry — images are public by default (change visibility
        // in GitHub → Packages → package settings if you want them private).
        REGISTRY       = 'ghcr.io/odeliatan12/oms'

        // Image names
        ORDER_IMAGE    = "${REGISTRY}/order-service"
        GATEWAY_IMAGE  = "${REGISTRY}/api-gateway"

        // Tag every image with the Git commit SHA — every build is traceable
        IMAGE_TAG      = "${GIT_COMMIT[0..7]}"

        // Maven flags used in every mvn call
        MVN_FLAGS      = '-B -q'
    }

    // ── Pipeline options ──────────────────────────────────────────────────────
    options {
        timeout(time: 30, unit: 'MINUTES')  // kill the build if it hangs
        disableConcurrentBuilds()           // one build at a time per branch
        buildDiscarder(logRotator(numToKeepStr: '10'))  // keep last 10 builds
    }

    // ── Stages ────────────────────────────────────────────────────────────────
    stages {

        // ── 1. Checkout ───────────────────────────────────────────────────────
        stage('Checkout') {
            steps {
                // Jenkins clones the repo configured in the pipeline job.
                // GIT_COMMIT, GIT_BRANCH, etc. are set automatically after checkout.
                checkout scm
                echo "Branch: ${GIT_BRANCH}  Commit: ${GIT_COMMIT}"
            }
        }

        // ── 2. Test ───────────────────────────────────────────────────────────
        stage('Test') {
            steps {
                // -pl shared-lib,order-service,api-gateway -am = build these modules
                // and anything they depend on (-am = also make dependencies)
                sh "mvn test -pl shared-lib,order-service,api-gateway -am ${MVN_FLAGS}"
            }
            post {
                // Publish JUnit test results so Jenkins shows the pass/fail graph
                always {
                    junit allowEmptyResults: true,
                          testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        // ── 3. Build JARs ─────────────────────────────────────────────────────
        stage('Build JARs') {
            steps {
                // Tests already passed — skip them here to save time
                sh "mvn package -pl shared-lib,order-service,api-gateway -am -DskipTests ${MVN_FLAGS}"
            }
        }

        // ── 4. Build Docker Images ────────────────────────────────────────────
        stage('Build Images') {
            // Build both images in parallel — they are independent
            parallel {
                stage('order-service image') {
                    steps {
                        sh """
                            docker build \
                                -f order-service/Dockerfile \
                                -t ${ORDER_IMAGE}:${IMAGE_TAG} \
                                -t ${ORDER_IMAGE}:latest \
                                .
                        """
                    }
                }
                stage('api-gateway image') {
                    steps {
                        sh """
                            docker build \
                                -f api-gateway/Dockerfile \
                                -t ${GATEWAY_IMAGE}:${IMAGE_TAG} \
                                -t ${GATEWAY_IMAGE}:latest \
                                .
                        """
                    }
                }
            }
        }

        // ── 5. Push Images ────────────────────────────────────────────────────
        stage('Push Images') {
            steps {
                // 'registry-credentials' is the ID of the Username/Password credential
                // you added in Manage Jenkins → Credentials
                withCredentials([usernamePassword(
                    credentialsId: 'registry-credentials',
                    usernameVariable: 'REGISTRY_USER',
                    passwordVariable: 'REGISTRY_PASS'
                )]) {
                    sh """
                        echo "${REGISTRY_PASS}" | docker login ghcr.io -u ${REGISTRY_USER} --password-stdin

                        docker push ${ORDER_IMAGE}:${IMAGE_TAG}
                        docker push ${ORDER_IMAGE}:latest

                        docker push ${GATEWAY_IMAGE}:${IMAGE_TAG}
                        docker push ${GATEWAY_IMAGE}:latest
                    """
                }
            }
        }

        // ── 6. Deploy ─────────────────────────────────────────────────────────
        // This stage runs only on the main branch — not on feature branches or PRs.
        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                // 'kubeconfig' is the ID of the Secret File credential containing
                // your kubeconfig (downloaded from your cluster's admin panel).
                // kubectl reads this file to know which cluster to deploy to.
                withCredentials([file(credentialsId: 'kubeconfig', variable: 'KUBECONFIG')]) {
                    sh """
                        # Rolling update — Kubernetes pulls the new image tag from
                        # the registry and replaces pods one at a time (zero downtime)
                        kubectl set image deployment/order-service \
                            order-service=${ORDER_IMAGE}:${IMAGE_TAG} \
                            --record

                        kubectl set image deployment/api-gateway \
                            api-gateway=${GATEWAY_IMAGE}:${IMAGE_TAG} \
                            --record

                        # Wait until the rollout finishes before marking the build green
                        kubectl rollout status deployment/order-service --timeout=120s
                        kubectl rollout status deployment/api-gateway   --timeout=120s
                    """
                }
            }
        }
    }

    // ── Post-build actions ────────────────────────────────────────────────────
    post {
        success {
            echo "Build ${BUILD_NUMBER} deployed successfully. Image tag: ${IMAGE_TAG}"
        }
        failure {
            echo "Build ${BUILD_NUMBER} FAILED. Check logs above."
            // Add email/Slack notification here when ready:
            // slackSend channel: '#deployments', message: "FAILED: ${JOB_NAME} #${BUILD_NUMBER}"
        }
        always {
            // Remove dangling images created during this build to free disk space
            sh 'docker image prune -f'
        }
    }
}
