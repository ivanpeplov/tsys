def map_env() {
    env.ENABLE == params.ENABLE
    env.DISABLE == params.DISABLE
    env.STREAMS == params.STREAMS
    env.LISTENER == params.LISTENER
    env.SCHEMA == params.SCHEMA
}

def set_global_env() {
    // get data from XML config
    if (params.xmlConfigBase64) {
        xmlConfig = new String(xmlConfigBase64.decodeBase64())
    } 
    else if (!params.xmlConfigBase64 && params.REGION) {
        switch(params.REGION) {
            case "QA1":
                url_dir = 'integration'
                xml_file_name =  'DeploymentManifest_' + params.REGION + '.xml'
                break
            case "QA2":
                url_dir = 'uat-biweekly'
                xml_file_name =  'DeploymentManifest_' + params.REGION + '.xml'
                break
            case "UAT1":
                url_dir = 'uat'
                xml_file_name =  'DeploymentManifest_' + params.REGION + '.xml'
                break
            case "UAT2":
                url_dir = 'uat-biweekly'
                xml_file_name =  'DeploymentManifest_' + params.REGION + '.xml'
                break
            case "PREPROD":
                url_dir = 'pre-prod'
                xml_file_name =  'DeploymentManifest_' + params.REGION + '.xml'
                break
            case "PROD":
                url_dir = 'main'
                xml_file_name =  'DeploymentManifest_' + params.REGION + '.xml'
                break
            default:
                println("Region ${params.REGION} unknown")
                currentBuild.result = 'FAILURE'
                break
        }
        base_url = 'http://10.255.250.50:8081/repository/prime-artifacts'
        def xmlUrl = base_url + '/' + url_dir + '/' + xml_file_name
        tools.downloadFile(xmlUrl, xml_file_name)

        xmlConfig = readFile(xml_file_name)
    } else {
        println("Parameters XML file & Region are set simulteniously")
        currentBuild.result = 'FAILURE'
    }

    def xmlData = new XmlSlurper().parseText(xmlConfig)

    def xmlImplementationId = xmlData.implementation.implementationid.toString()
    def oldImplementationId = ""
    
    def artifactsList = []
    xmlData.implementation.application.each { app ->
        artifactsList = artifactsList << app.deployment.artifactname.toString()
    }
    artifactsList.unique()
    def artifactsListdb = artifactsList.findAll{it.toLowerCase().contains('prime4-db'.toLowerCase())}
    // get patch version
    def uParsedDt = artifactsList[0].tokenize("-")[-2]
    def parsedDt = tools.convertDate(uParsedDt,"yyyyMMdd")

    // set environment
    env.ARTIFACTS = artifactsListdb.join(',')
    env.REGION = xmlData.implementation.region.text()
    env.IMPLEMENTATION_ID = xmlImplementationId
    env.DC = xmlData.implementation.datacentre.text()
    env.PATCH_VERSION = parsedDt.format("yyyyMMdd")
    env.STREAMS_STATUS = 'NOT CHANGED'
    env.LISTENER_STATUS = 'NOT CHANGED'

    currentBuild.description = "region:${env.REGION}\nschema:${env.SCHEMA}"

        try { if (DISABLE == ENABLE) {
            println ('DISABLE == ENABLE, aborting')
            error ('autoStop')} }
        catch(e) { if (e.message == 'autoStop') {
            currentBuild.result = 'ABORTED' }
        throw e }

        try { if (SCHEMA == '2' && STREAMS == 'true' || SCHEMA == '2' && STREAMS == 'false') {
            println('STREAMS ops - NO support for SCHEMA 2')
            error('autoStop') } }
        catch(e) { if (e.message == 'autoStop') {
            currentBuild.result = 'ABORTED' }
        throw e }
        
        try { if (SCHEMA == '2' && REGION.contains('QA')) {
            println('REGION QA - NO support for SCHEMA 2')
            error('autoStop')} } 
        catch(e) { if (e.message == 'autoStop') {
            currentBuild.result = 'ABORTED' }
        throw e }
        
        try { if (STREAMS == 'false' && LISTENER == 'false') {
            println('STREAMS/LISTENER not selected aborting')
            error('autoStop') } } 
        catch(e) { if (e.message == 'autoStop') {
            currentBuild.result = 'ABORTED' }
        throw e }
}

def set_region_env() {
    def yaml_cfg = readYaml file: "${WORKSPACE}/config/environment.yaml"

    SYSTEM_NAME_PRIME = yaml_cfg.get('system_name_prime')
    SYSTEM_NAME_ONLINE = yaml_cfg.get('system_name_online')
    DB_ONLINE_LIQUIBASE_URL = yaml_cfg.get(REGION).get('db_online_liquibase_url')
    DB_PRIME_LIQUIBASE_URL = yaml_cfg.get(REGION).get('db_prime_liquibase_url')
    env.MAIL_RECIPIENTS_DB = yaml_cfg.get('mail_recipients_db')
    servers = yaml_cfg.server
}

def download_artifacts() {
        node ( "${REGION}" ) {
            // checkout our git
            checkout changelog: false, poll: false, scm: [
                $class: 'GitSCM', 
                branches: [[name: '*/master']], 
                extensions: [], 
                userRemoteConfigs: [[credentialsId: 'jenkins_gitlab_token', url: 'http://10.255.250.30/prime/jenkins-scripts.git']]
            ]

            // drop existing install dir
            sh """
                [ ! -d install ] || rm -rf install
                mkdir -p install
            """
            // download artifacts
            // v2
            dir("install") {
                env.ARTIFACTS.split(',').each { filename ->
                    def remoteUrl = base_url + "/" + filename
                    def localfilename = filename.substring(filename.lastIndexOf('/')+1)
                    tools.downloadFile(remoteUrl,localfilename)
                }
            }
        }
}

def check_artifact_params() {
    /* ! sometimes there are no artifacts described in xml file, 
        so we need to recheck our job input parameters !
    */
    def artifacts = env.ARTIFACTS.split(',')
    env.PATCH_DB = params.PATCH_DB && artifacts.findAll{it.toLowerCase().contains('prime4-db'.toLowerCase())}.any{true} ? true : false
}

def db_prepare() {
    node ("${REGION}") {
        // create link out of $WORKSPACE
        sh """
            [ ! -d ${WORKSPACE}/prime4db ] || rm -rf ${WORKSPACE}/prime4db
            mkdir ${WORKSPACE}/prime4db
            pushd ${WORKSPACE}/prime4db && unzip -q ${WORKSPACE}/install/prime4-db-*.zip

            # @DIRTY HACK
            chmod u+x ${WORKSPACE}/prime4db/*.sh
        """
    }
}
def patch_db_ops(String operation) {
    node ("${REGION}") {
        dir('prime4db'){
            prime_app_usr='PRM_APP0000'
            //Masked Oracle Credentials. Based on HashiCorp Vault
            def ora_creds = [
            [path: 'secrets/creds/ora/${REGION}/PRM', secretValues: [
                [envVar: 'prime_app_pwd', vaultKey: 'password']]],
            [path: 'secrets/creds/ora/${REGION}/TCT', secretValues: [
                [envVar: 'code_schema_pwd', vaultKey: 'password']]]
            ]
            switch(operation) {
                case "streams_stop":
                    STREAMS_STATUS = 'DISABLE'
                    wrap([$class: 'VaultBuildWrapper', vaultSecrets: ora_creds]) {
                    sh    "./Streams.Stop.sh ${SYSTEM_NAME_PRIME} "+'${code_schema_pwd}'+" ${DB_PRIME_LIQUIBASE_URL}"
                    sh    "./Streams.Stop.sh ${SYSTEM_NAME_ONLINE} "+'${code_schema_pwd}'+" ${DB_ONLINE_LIQUIBASE_URL}"
                    }
                    break
                case "streams_sync":
                    wrap([$class: 'VaultBuildWrapper', vaultSecrets: ora_creds]) {
                    sh    "./Prime.Streams.SyncData.sh TCTCD${SCHEMA} "+'${code_schema_pwd}'+" ${DB_PRIME_LIQUIBASE_URL}"
                    }   
                    break
                case "streams_start":
                    STREAMS_STATUS = 'ENABLE'
                    wrap([$class: 'VaultBuildWrapper', vaultSecrets: ora_creds]) {
                    sh    "./Streams.Refresh.sh ${SYSTEM_NAME_PRIME} TCTCD${SCHEMA} "+'${code_schema_pwd}'+" ${DB_PRIME_LIQUIBASE_URL}"
                    sh    "./Streams.Refresh.sh ${SYSTEM_NAME_ONLINE} TCTCD${SCHEMA} "+'${code_schema_pwd}'+" ${DB_ONLINE_LIQUIBASE_URL}"
                    sh    "./Streams.Start.sh ${SYSTEM_NAME_PRIME} "+'${code_schema_pwd}'+" ${DB_PRIME_LIQUIBASE_URL}"
                    sh    "./Streams.Start.sh ${SYSTEM_NAME_ONLINE} "+'${code_schema_pwd}'+" ${DB_ONLINE_LIQUIBASE_URL}"
                    }
                    break
                case "stop_listeners": //schema1 schema2
                    LISTENER_STATUS = 'DISABLE'
                    wrap([$class: 'VaultBuildWrapper', vaultSecrets: ora_creds]) { 
                    if (SCHEMA == '2') {prime_app_usr='PRM_APP0000_2'} //username condition for schema2
                    sh  "./Prime.Stop.Listeners.sh ${prime_app_usr} "+'${prime_app_pwd}'+" TCTCD${SCHEMA} ${DB_PRIME_LIQUIBASE_URL}" }
                break
                case "start_listeners": //schema1 schema2
                    LISTENER_STATUS = 'ENABLE'
                    wrap([$class: 'VaultBuildWrapper', vaultSecrets: ora_creds]) { 
                    if (SCHEMA == '2') {prime_app_usr='PRM_APP0000_2'} //username condition for schema2
                    sh  "./Prime.Start.Listeners.sh ${prime_app_usr} "+'${prime_app_pwd}'+" TCTCD${SCHEMA} ${DB_PRIME_LIQUIBASE_URL}" }
                break
                default:
                    println("Entered DB patch role is unknown")
                    break
            }
        }
    }
}
//E-mailing Build Report
def send_email () {
    startTime = new Date(currentBuild.startTimeInMillis).format('dd.MM.yyyy HH:mm')
    finishTime = new Date(System.currentTimeMillis()).format('dd.MM.yyyy HH:mm')           
        emailext attachLog: true, 
            compressLog: true,
            body: """
            Artifact Name: ${env.PATCH_VERSION}
            Deployment Unique ID: ${env.IMPLEMENTATION_ID}
            Region: ${env.REGION}
            Schema: ${SCHEMA}
            Launched by ${BUILD_USER_ID}
            Streams ${STREAMS_STATUS}
            Listener ${LISTENER_STATUS}
            Date & time: ${startTime}
            Status: ${currentBuild.result} """, 
                from: 'prime_news@ucscards.ru',
                subject: "Region: ${env.REGION}, ${env.PATCH_VERSION},  ${currentBuild.result}, Streams ${STREAMS_STATUS}, Listener ${LISTENER_STATUS}",
                to: "${MAIL_RECIPIENTS_DB}";
}
return this
