#!groovy

def call(body) {
  node {

    jdk = tool name: 'jdk8'
    env.JAVA_HOME = "${jdk}"

    def mvnHome
    def envServer
    def userInput
    def jiraTicket
    scmVars = null
    issueKey = null
    jobName = env.JOB_NAME
    branchName = env.BRANCH_NAME
    messageOk = 'Finalizado con éxito'
    messageNok = 'Finalizado con error'
    envQA = 'Testing'
    envProd = 'Production'
    version = null
    versionTicket = null
    flagJira = false

    // valida branch para asignar una variable de entorno
    if (env.BRANCH_NAME.contains('feature') || env.BRANCH_NAME.contains('bugfix')) {
      envServer = '-Pktphdi_dev'
      issueKey = branchName.split('/')
      preparation(true)
      compile(true)
      //		sonarqube_analysis(true)
      //        quality_gate(true)
      confirm_install(true)
      build_and_deploy(true, envServer)
      confirm_advance_to_next_step(true, envQA)
      create_pull_request(true, 'develop')
    } else if (env.BRANCH_NAME == 'develop') {
      preparation(false)
      compile(false)
      //		sonarqube_analysis(false)
      //        quality_gate(false)
      //create_release(false)
    } else if (env.BRANCH_NAME.contains('release') || env.BRANCH_NAME.contains('hotfix')) {
      //envServer = '-Pktphdi_test'
      issueKey = branchName.split('/')
      envServer = functionSetProfile()
      preparation(flagJira)
      compile(flagJira)
      //sonarqube_analysis(true)
      //quality_gate(true)		
      build_and_deploy(flagJira, envServer)
      assignIssueQA()
      //securityOwasp()
      confirm_advance_to_next_step(flagJira, envProd)
      //validAndAssignVersion()
      envServer = '-Pktphdi_prod'
      build_and_deploy(flagJira, envServer)
      publish(flagJira, envServer)
      create_pull_request(flagJira, 'develop')
      create_pull_request(flagJira, 'master')
    } else if (env.BRANCH_NAME == 'master') {
      envServer = '-Pktphdi_prod'
    }
  }
}

def functionSetProfile(Map config = [: ]) {
  def choice1
  def profilePRD

  stage('Testing Profile') {
    choice1 = input(id: 'userInput', message: 'Select your choice', parameters: [
      [$class: 'ChoiceParameterDefinition', choices: 'OnPremise_clv\nMigracion_aws', description: '', name: '']
    ])
    if (choice1.equals("OnPremise_clv")) {
      profilePRD = '-Pktphdi_test'
      echo "Se ha seleccionado el ambiente OnPremise_clv."
    } else if (choice1.equals("Migracion_aws")) {
      profilePRD = '-Pktphdi_test_01'
      echo "Se ha seleccionado el ambiente Migracion_aws."
    }
  }
  return profilePRD
}

def changeHibernateValidatorVersion(String pomFilePath, String newVersion) {
  def pomContent = readFile(file: pomFilePath)
  def updatedPomContent = pomContent.replaceAll('<hibernate-validator.version>.*</hibernate-validator.version>', "<hibernate-validator.version>${newVersion}</hibernate-validator.version>")
  writeFile(file: pomFilePath, text: updatedPomContent)
  echo "Se ha actualizado el valor de <hibernate-validator.version> con la version ${newVersion} en el archivo POM."
}

def preparation(flagJira) {
  stage('Preparation') {
    try {
      scmVars = checkout scm
      mvnHome = tool 'Maven3.6.0'
    } catch (e) {
      if (flagJira) {
        commentIssue('Preparation', e)
      }
    }
  }
}

def compile(flagJira) {
  stage('Compile') {
    try {
      sh "'${mvnHome}/bin/mvn' clean compile -U"
    } catch (e) {
      if (flagJira) {
        commentIssue('Compile', e)
        notifyIssue('Compile', e)
      }
    }
  }
}

def sonarqube_analysis(flagJira) {
  stage('SonarQube Analysis') {
    try {
      withSonarQubeEnv('SonarQube') {
        sh "'${mvnHome}/bin/mvn' sonar:sonar -Pno-sonar-gen"
      }
    } catch (e) {
      if (flagJira) {
        commentIssue('SonarQube Analysis', e)
        notifyIssue('SonarQube Analysis', e)
      }
    }
  }
}

def quality_gate(flagJira) {
  stage('Quality Gate') {
    try {
      timeout(time: 1, unit: 'HOURS') {
        def qg = waitForQualityGate()
        if (qg.status != 'OK') {
          error "Pipeline aborted due to quality gate failure: ${qg.status}"
        }
      }
    } catch (e) {
      if (flagJira) {
        commentIssue('SonarQube Analysis', e)
        notifyIssue('SonarQube Analysis', e)
      }
      currentBuild.result = 'FAILURE'
    }
  }
}

def junit_test(flagJira) {
  stage('Functional Test') {
    try {
      sh "'${mvnHome}/bin/mvn' surefire:test"
    } catch (e) {
      if (flagJira) {
        commentIssue('Functional Test', e)
        notifyIssue('Functional Test', e)
      }
    }
  }
}

def build_and_deploy(flagJira, envServer) {
  stage('Build and Deploy') {
    try {
      if (envServer.contains('dev')) {
        //sh "rm -rf  $workspace/dist/* "
        sh "rm -rf  $workspace/target/* "
        sh "'${mvnHome}/bin/mvn' clean install ${envServer}"

      } else if (envServer.contains('test')) {

        if (envServer.contains('Pktphdi_test_01') || envServer.contains('Pktphdi_test_02')) {

          sh "rm -rf  $workspace/target/* "
          def pomFilePath = 'pom.xml'
          def newVersion = '6.0.20.Final'

          changeHibernateValidatorVersion(pomFilePath, newVersion)
          sh "'${mvnHome}/bin/mvn' clean install ${envServer}"
          envServer = '-Pktphdi_test_02'

          sh "rm -rf  $workspace/target/* "
          //def pomFilePath = '$workspace/pom.xml'
          //def newVersion = '6.0.20.Final'
          changeHibernateValidatorVersion(pomFilePath, newVersion)
          sh "'${mvnHome}/bin/mvn' clean install ${envServer}"

        } else if (envServer.contains('Pktphdi_test')) {
          sh "rm -rf  $workspace/target/* "
          def pomFilePath = 'pom.xml'
          def newVersion = '5.1.0.Final'
          changeHibernateValidatorVersion(pomFilePath, newVersion)
          sh "'${mvnHome}/bin/mvn' clean install ${envServer}"
        }

      } else if (envServer.contains('prod')) {

        sh "rm -rf  $workspace/target/* "
        def pomFilePath = 'pom.xml'
        def newVersion = '5.1.0.Final'
        changeHibernateValidatorVersion(pomFilePath, newVersion)
        sh "'${mvnHome}/bin/mvn' clean install ${envServer}"
      }

    } catch (e) {
      if (flagJira) {
        commentIssue('Build and Deploy', e)
        notifyIssue('Build and Deploy', e)
      }
      sh "exit 1"
      currentBuild.result = 'FAILURE'
    } finally {
      if (flagJira) {
        commentIssue('Build and Deploy', messageOk)
      }
    }
  }
}

def jmeter_test(flagJira) {
  stage('Concurrency Test') {
    try {
      //sh '/opt/apache-jmeter-5.0/bin/jmeter.sh -n -t /opt/apache-jmeter-5.0/extras -l test.jtl'
      echo 'No test runner'
    } catch (e) {
      if (flagJira) {
        commentIssue('Concurrency Test', e)
        notifyIssue('Concurrency Test', e)
      }
    }
  }
}

def publish(flagJira, envServer) {
  stage('Publish and Tag') {
    try {
      withEnv(['JIRA_SITE=clavesistemas.atlassian.net']) {
        def issue = jiraGetIssue idOrKey: issueKey[1]
        def versionName = issue.data.fields.fixVersions[0].get('name')
        def team = env.JOB_NAME.split('/')[0].split('\\+')[0]
        def project = env.JOB_NAME.split('/')[0].split('\\+')[1]
        def commitHash = scmVars.GIT_COMMIT
        versionTicket = versionName

        sh "'${mvnHome}/bin/mvn' versions:set -DnewVersion=${versionName}"
        sh "'${mvnHome}/bin/mvn' deploy ${envServer}"

        if (versionTicket != null && !versionTicket.contains('SNAPSHOT')) {
          withCredentials([string(credentialsId: 'app-password-bitbucket', variable: 'SECRET')]) {
            sh "curl https://api.bitbucket.org/2.0/repositories/" + team + "/" + project + "/refs/tags -u clavedesarrollo:$SECRET -X POST -H 'Content-Type: application/json' -d '{\"name\": \"v" + versionName + "\",\"target\":{\"hash\":\"" + commitHash + "\"}}'"
          }
        }
      }
    } catch (e) {
      if (flagJira) {
        commentIssue('Publish and Tag', e)
        notifyIssue('Publish and Tag', e)
      }
      error "Ticket de jira sin version"
      sh "exit 1"
      currentBuild.result = 'FAILURE'
    }
  }
}

def confirm_install(flagJira) {
  stage('Confirm Deployment') {
    try {
      timeout(time: 2, unit: 'HOURS') {
        input(message: '¿Desea aprobar la instalación?',
          parameters: [booleanParam(defaultValue: true,
            description: 'Si desea continuar, presione el boton', name: 'Yes?')])
      }
    } catch (e) {
      if (flagJira) {
        commentIssue('Confirm Deployment', e)
        notifyIssue('Confirm Deployment', e)
      }
      sh "exit 1"
      currentBuild.result = 'FAILURE'
    }
  }
}

def confirm_advance_to_next_step(flagJira, environment) {
  stage('Confirm Advance to ' + environment) {
    try {
      timeout(time: 1, unit: 'HOURS') {
        def response = input(message: '¿Desea avanzar el desarrollo a ' + environment + '?',
          parameters: [booleanParam(defaultValue: true,
            description: 'Si desea continuar, presione el boton', name: 'Yes?')])
        if (response && environment == 'Production') {
          validateTestedStatus()
        } else if (!response) {
          echo "Rechazado manualmente por el usuario"
          unstable("Confirm => false");
        }
      }
    } catch (e) {
      if (flagJira) {
        commentIssue('Confirm Advance to ' + environment, e)
        notifyIssue('Confirm Advance to ' + environment, e)
      }
      unstable("Confirm => no response");
    }
  }
}

def create_pull_request(flagJira, environment) {
  stage('Pull-request to ' + environment) {
    try {
      def team = env.JOB_NAME.split('/')[0].split('\\+')[0]
      def project = env.JOB_NAME.split('/')[0].split('\\+')[1]

      withCredentials([string(credentialsId: 'app-password-bitbucket', variable: 'SECRET')]) {
        sh "curl https://api.bitbucket.org/2.0/repositories/" + team + "/" + project + "/pullrequests -u clavedesarrollo:$SECRET --request POST --header 'Content-Type: application/json' --data '{\"title\": \"" + branchName + "\",\"description\":\"\",\"source\": {\"branch\": {\"name\": \"" + branchName + "\"}},\"destination\": {\"branch\": {\"name\": \"" + environment + "\"}},\"close_source_branch\":\"true\"}'"
      }
    } catch (e) {
      if (flagJira) {
        commentIssue('Pull-request ' + environment, e)
        notifyIssue('Pull-request ' + environment, e)
      }
      sh "exit 1"
      currentBuild.result = 'FAILURE'
    } finally {
      if (flagJira) {
        commentIssue('Pull-request ' + environment, messageOk)
      }
    }
  }
}

def assignIssueQA() {
  try {
    withEnv(['JIRA_SITE=clavesistemas.atlassian.net']) {
      def displayName = assignQA()
      def body = '{"accountId":"' + displayName + '"}'
      def result = null
      def project = issueKey[1].split('-')[0]

      def addr = env.jiracloud + "/rest/api/3/issue/" + issueKey[1] + "/assignee"
      def response = httpRequest authentication: 'jira-cloud', url: "${addr}", httpMode: 'PUT', requestBody: body, contentType: 'APPLICATION_JSON'
      def transitionInput = [
        transition: [
          id: '21'
        ]
      ]
      jiraTransitionIssue idOrKey: issueKey[1], input: transitionInput
    }
  } catch (e) {
    try {
      withEnv(['JIRA_SITE=clavesistemas.atlassian.net']) {
        def transitionInput = [
          transition: [
            id: '21'
          ]
        ]
        jiraTransitionIssue idOrKey: issueKey[1], input: transitionInput
      }
    } catch (e1) {
      currentBuild.result = 'SUCCESS'
    }
  }
}

def validAndAssignVersion() {
  try {
    withEnv(['JIRA_SITE=clavesistemas.atlassian.net']) {
      def issue = jiraGetIssue idOrKey: issueKey[1]
      def versionName = issue.data.fields.fixVersions[0].get('name')
      def team = env.JOB_NAME.split('/')[0].split('\\+')[0]
      def project = env.JOB_NAME.split('/')[0].split('\\+')[1]
      def commitHash = scmVars.GIT_COMMIT
      versionTicket = versionName
      echo "versionTicket: " + versionTicket
    }
  } catch (e) {
    error "Ticket jira sin version"
    sh "exit 1"
    currentBuild.result = 'FAILURE'
  }
}

def commentIssue(step, message) {
  withEnv(['JIRA_SITE=clavesistemas.atlassian.net']) {
    def comment = [body: step + ': ' + message]
    jiraAddComment idOrKey: issueKey[1], input: comment
  }
}

def notifyIssue(step, message) {
  withEnv(['JIRA_SITE=clavesistemas.atlassian.net']) {
    def notify = [subject: step + ': error',
      textBody: message,
      htmlBody: message,
      to: [reporter: true,
        assignee: true,
        watchers: false,
        voters: false
      ]
    ]
    jiraNotifyIssue idOrKey: issueKey[1], notify: notify
  }
}

def assignQA() {
  def result = null
  def project = issueKey[1].split('-')[0]
  def addr = env.jiracloud + "/rest/api/3/project/${project}/role"
  def response = httpRequest authentication: 'jira-cloud', url: "${addr}"
  if (response.status == 200) {
    result = parseJSON(response.content)
    return getUserRole(result.QA)
  }
}

def assignQADisplayName() {
  def result = null
  def project = issueKey[1].split('-')[0]
  def addr = env.jiracloud + "/rest/api/3/project/${project}/role"
  def response = httpRequest authentication: 'jira-cloud', url: "${addr}"
  if (response.status == 200) {
    result = parseJSON(response.content)
    return getUserDisplayName(result.QA)
  }
}

def getUserRole(url) {
  def result = null
  def response = httpRequest authentication: 'jira-cloud', url: "${url}"
  if (response.status == 200) {
    result = parseJSON(response.content)
    return result.actors[0].actorUser.accountId
  }
}

def getUserDisplayName(url) {
  def result = null
  def response = httpRequest authentication: 'jira-cloud', url: "${url}"
  if (response.status == 200) {
    result = parseJSON(response.content)
    return result.actors[0].displayName
  }
}

def validateTestedStatus() {
  def result = null
  def addr = env.jiracloud + "/rest/api/3/issue/${issueKey[1]}"
  def response = httpRequest authentication: 'jira-cloud', url: "${addr}"
  if (response.status == 200) {
    result = parseJSON(response.content)
    def statusName = getStatus(result.fields.status.self)
    validAndAssignVersion()
    if (!statusName.equals('APROBADO-QA')) {
      error "Tarea Jira debe estar en estado APROBADO-QA. Estado actual: ${statusName}"
      currentBuild.result = 'FAILURE'
    } else if (versionTicket == null) {
      error "Tarea Jira no tiene version para la publicar"
      sh "exit 1"
      currentBuild.result = 'FAILURE'
    }
  }
}

def getStatus(url) {
  def result = null
  def response = httpRequest authentication: 'jira-cloud', url: "${url}"
  if (response.status == 200) {
    result = parseJSON(response.content)
    return result.name
  }
}

def parseJSON(text) {
  def slurper = new groovy.json.JsonSlurperClassic()
  def result = slurper.parseText(text)

  return result
}

def securityOwasp() {
  stage('Security - OWASP') {
    //mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 -DassemblyAnalyzerEnabled=false
    //mvn org.owasp:dependency-check-maven:check
    //mvn clean install -Powasp-dependency-check
    //mvn verify
    try {
      sh "'${mvnHome}/bin/mvn' org.owasp:dependency-check-maven:check -Ddownloader.quick.query.timestamp=false -DfailBuildOnCVSS=10 -DassemblyAnalyzerEnabled=false"
    } catch (Exception error) {
      if (error instanceof javax.net.ssl.SSLHandshakeException) {
        echo "Se produjo un error de SSLHandshakeException: ${error.getMessage()}"
      } else {
        echo "Security failed: Se detectaron vulnerabilidades con puntaje CVSS de nivel 10, por favor corregirlos de manera urgente."
      }
      echo "Error capturado: ${error.getMessage()}"
      echo "Tipo de error: ${error.getClass().getSimpleName()}"
      throw error
    }

    try {
      sh "'${mvnHome}/bin/mvn' org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 -DassemblyAnalyzerEnabled=false"
    } catch (err) {
      unstable('Security failed => Se detectaron vulnerabilidades con puntaje CVSS de nivel 7 o mayor, tener en cuenta que se debe revisar.')
    }
  }
}
