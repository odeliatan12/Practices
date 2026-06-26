FROM jenkins/jenkins:lts-jdk21

# Switch to root to install system packages
USER root

# Install Docker CLI — needed so Jenkins can run "docker build" and "docker push"
RUN apt-get update && apt-get install -y \
    ca-certificates \
    curl \
    gnupg \
    lsb-release \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
       https://download.docker.com/linux/debian $(lsb_release -cs) stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y docker-ce-cli \
    && rm -rf /var/lib/apt/lists/*

# Install Maven — needed so Jenkins can run "mvn test" and "mvn package"
ARG MAVEN_VERSION=3.9.6
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xz -C /opt \
    && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

# Pre-install required Jenkins plugins so they are baked into the image.
# Without this, plugins survive only in the jenkins_home volume — a fresh
# container rebuild would lose them and the pipeline would fail again.
RUN jenkins-plugin-cli --plugins \
    docker-workflow \
    git \
    workflow-aggregator \
    pipeline-stage-view \
    junit

# Stay as root so Jenkins can access the Docker socket mounted from the host
# In a stricter setup you would add jenkins to the docker group instead
