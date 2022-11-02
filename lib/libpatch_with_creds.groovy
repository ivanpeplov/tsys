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
        //base_url = 'http://10.255.250.50:8081/repository/prime-artifacts'
        def xmlUrl = BASE_URL + '/' + url_dir + '/' + xml_file_name
        tools.downloadFile(xmlUrl, xml_file_name)

        xmlConfig = readFile(xml_file_name)
    } else {
        println("Parameters XML file & Region are set simulteniously")
        currentBuild.result = 'FAILURE'
    }

    def xmlData = new XmlSlurper().parseText(xmlConfig)

    def xmlImplementationId = xmlData.implementation.implementationid.toString()
    def oldImplementationId = ""
    
    if (!params.xmlConfigBase64) {
        def oldBuild = currentBuild.previousBuild

        for (i in 1..20) {
            if (oldBuild.description) {
                def (previousRegionStr, previousSchemaStr, previousImpIdStr) = oldBuild.description.tokenize('\n')
                def previousRegion = previousRegionStr.tokenize(':')[1]
                def previousSchema = previousSchemaStr.tokenize(':')[1]
                def previousImpId = previousImpIdStr.tokenize(':')[1]

                if (previousRegion == env.REGION && previousSchema == env.SCHEMA) {
                    oldImplementationId = previousImpId
                    break
                }
            }
            oldBuild = oldBuild.previousBuild
        }
    }

    def artifactsList = []
    xmlData.implementation.application.each { app ->
        artifactsList = artifactsList << app.deployment.artifactname.toString()
    }
    artifactsList.unique()
    
   /* if (params.EXCLUDE != '') {
        def exclList = params.EXCLUDE.split('\n')

        exclList.each { exclName ->
            artifactsList.removeAll( artifactsList.findAll { it.contains(exclName) })
        }
    }*/

    // get patch version
    def uParsedDt = artifactsList[0].tokenize("-")[-2]
    def parsedDt = tools.convertDate(uParsedDt,"yyyyMMdd")

    // set environment
    env.ARTIFACTS = artifactsList.join(',')
    env.REGION = xmlData.implementation.region.text()
    env.IMPLEMENTATION_ID = xmlImplementationId
    env.DC = xmlData.implementation.datacentre.text()
    env.PATCH_VERSION = parsedDt.format("yyyyMMdd")
    env.SCHEMA = params.SCHEMA // CHECK IN XML !!!

    currentBuild.description = "region:${env.REGION}\nschema:${env.SCHEMA}\nimplementation_id:${env.IMPLEMENTATION_ID}"

    try {
        if (!params.xmlConfigBase64) {
            if (oldImplementationId == xmlImplementationId) {
                println('Old Implementaion ID == New Implementaion ID, aborting')
                error('autoStop')
            }
        }
    } catch(e) {
        if (e.message == 'autoStop') {
            currentBuild.result = 'ABORTED'
        }
        throw e
    }
}
//Reading from environment.yaml
def set_region_env() {
    def yaml_cfg = readYaml file: "${WORKSPACE}/config/environment.yaml"

    INSTALL_SOURCE_PATH_WINDOWS = yaml_cfg.get('install_source_path_windows')
    INSTALL_OTC_PATH = yaml_cfg.get('install_otc_path')
    INSTALL_HSMI_PATH = yaml_cfg.get('install_hsmi_path')
    HSMI_ARTIFACTS_PATH = yaml_cfg.get('hsmi_artifacts_path')
    WINDOWS_INSTALLER_PATH = yaml_cfg.get('windows_installer_path')
    WINDOWS_ARTIFACTS_PATH = yaml_cfg.get('windows_artifacts_path')
    WINDOWS_CONFIG_PATH = yaml_cfg.get('windows_config_path')
    SYSTEM_NAME_PRIME = yaml_cfg.get('system_name_prime')
    SYSTEM_NAME_ONLINE = yaml_cfg.get('system_name_online')
    DB_ONLINE_LIQUIBASE_URL = yaml_cfg.get(REGION).get('db_online_liquibase_url')
    DB_PRIME_LIQUIBASE_URL = yaml_cfg.get(REGION).get('db_prime_liquibase_url')
    RDSADMIN_DIRECTORIES_PATH = yaml_cfg.get(REGION).get('RDSADMIN_directories_path')
    DB_HOST_PRIME =  yaml_cfg.get(REGION).get('db_host_prime')
    DB_HOST_ONLINE = yaml_cfg.get(REGION).get('db_host_online')
    DB_PORT_PRIME = yaml_cfg.get(REGION).get('db_port_prime')
    DB_PORT_ONLINE = yaml_cfg.get(REGION).get('db_port_online')
    DB_SERVICE_PRIME = yaml_cfg.get(REGION).get('db_service_prime')
    DB_SERVICE_ONLINE = yaml_cfg.get(REGION).get('db_service_online')
    DB_SERVICE_NAME_PRIME = yaml_cfg.get(REGION).get('db_service_name_prime')
    DB_SERVICE_NAME_ONLINE = yaml_cfg.get(REGION).get('db_service_name_online')
    INSTITUTION_LIST = yaml_cfg.get(REGION).get('institution_list')
    CONFIG_INSTITUTION_LIST = yaml_cfg.get(REGION).get('config_institution_list')

    env.MAIL_RECIPIENTS_DEV = yaml_cfg.get('mail_recipients_dev')
    servers = yaml_cfg.server
    //Masked Credentials for Oracle DB from HashiCorp Vault
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
                    //def remoteUrl = tools.constructURLfromFilename(params.BASE_URL,filename)
                    //tools.downloadFile(remoteUrl,filename)

                    def remoteUrl = params.BASE_URL + "/" + filename
                    def localfilename = filename.substring(filename.lastIndexOf('/')+1)
                    
                    tools.downloadFile(remoteUrl,localfilename)
                }
            }
        }
    
}

def check_artifact_params() {
    /* ! sometimes there is partial deployment described in xml file, 
        so we need to recheck our job input parameters !
        artifacts.findAll check in Manifest xml
    */
    def artifacts = env.ARTIFACTS.split(',')
    env.PATCH_ONLINE_APP = params.PATCH_ONLINE_APP && artifacts.findAll{it.toLowerCase().contains('app-online'.toLowerCase())}.any{true} ? true : false
    env.PATCH_NCRIPT_APP = params.PATCH_NCRYPT_APP && artifacts.findAll{it.toLowerCase().contains('app-ncrypt'.toLowerCase())}.any{true} ? true : false
    env.PATCH_DB = params.PATCH_DB && artifacts.findAll{it.toLowerCase().contains('prime4-db'.toLowerCase())}.any{true} ? true : false
    env.PATCH_DB_CONFIGS = params.PATCH_DB_CONFIGS && artifacts.findAll{it.toLowerCase().contains('prime-configs'.toLowerCase())}.any{true} ? true : false
    env.PATCH_RBA_APP = params.PATCH_RBA_APP && artifacts.findAll{it.toLowerCase().contains('app-rba'.toLowerCase())}.any{true} ? true : false
    env.PATCH_FGA_APP = params.PATCH_FGA_APP && artifacts.findAll{it.toLowerCase().contains('app-fg'.toLowerCase())}.any{true} ? true : false
    env.PATCH_BRMS_APP = params.PATCH_BRMS_APP && artifacts.findAll{it.toLowerCase().contains('app-brms'.toLowerCase())}.any{true}? true : false
    env.PATCH_PRIMEWEB = params.PATCH_PRIMEWEB && artifacts.findAll{it.toLowerCase().contains('app-primeweb'.toLowerCase())}.any{true} ? true : false
    env.PATCH_OTC = params.PATCH_OTC && artifacts.findAll{it.toLowerCase().contains('online-thick'.toLowerCase())}.any{true} ? true : false
    env.PATCH_KM = params.PATCH_KM && artifacts.findAll{it.toLowerCase().contains('key-man'.toLowerCase())}.any{true} ? true : false
    env.PATCH_HSMI = params.PATCH_HSMI && artifacts.findAll{it.toLowerCase().contains('hsmi'.toLowerCase())}.any{true} ? true : false
}

//db_prepare for separate artifacts prime4* and prime-configs*
def db_prepare(String operation) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: "database").split(' ')) {
        node ( tools.getJenkinsSlave(name: "${server}")) {
            sh  "[ ! -d ${WORKSPACE}/${operation} ] || rm -rf ${WORKSPACE}/${operation}"
            sh  "mkdir ${WORKSPACE}/${operation}"
        switch (operation) {
            case "prime-configs":
            sh  "pushd ${WORKSPACE}/${operation} && unzip -q ${WORKSPACE}/install/${operation}-*.zip"
            break
            default:
            sh  "pushd ${WORKSPACE}/${operation} && unzip -q ${WORKSPACE}/install/prime4-db-*.zip"
            break
            }
            sh "chmod u+x ${WORKSPACE}/${operation}/*.sh"
        }
    }
}

def db_ops(String operation) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: "database").split(' ')) {
        node ( tools.getJenkinsSlave(name: "${server}")) {
            dir('prime4db'){
                switch(operation) {
                    case "streams_stop":
                        withCredentials([usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW')]){
                        sh    "./Streams.Stop.sh ${SYSTEM_NAME_PRIME} "+'${CODE_SCHEMA_PSW}'+" ${DB_PRIME_LIQUIBASE_URL}"
                        sh    "./Streams.Stop.sh ${SYSTEM_NAME_ONLINE} "+'${CODE_SCHEMA_PSW}'+" ${DB_ONLINE_LIQUIBASE_URL}"
                        }
                        break
                    case "streams_sync":
                        withCredentials([usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW')]){
                        sh    "./Prime.Streams.SyncData.sh ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'+" ${DB_PRIME_LIQUIBASE_URL}"
                        }   
                        break
                    case "streams_start":
                        withCredentials([usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW')]){
                        sh    "./Streams.Refresh.sh ${SYSTEM_NAME_PRIME} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'+" ${DB_PRIME_LIQUIBASE_URL}"
                        sh    "./Streams.Refresh.sh ${SYSTEM_NAME_ONLINE} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'+" ${DB_ONLINE_LIQUIBASE_URL}"
                        sh    "./Streams.Start.sh ${SYSTEM_NAME_PRIME} "+'${CODE_SCHEMA_PSW}'+" ${DB_PRIME_LIQUIBASE_URL}"
                        sh    "./Streams.Start.sh ${SYSTEM_NAME_ONLINE} "+'${CODE_SCHEMA_PSW}'+" ${DB_ONLINE_LIQUIBASE_URL}"
                        }
                        break
                    case "stop_listeners":
                        withCredentials([usernamePassword(credentialsId: "prime_app_schema${SCHEMA}_${REGION}", usernameVariable: 'PRIME_APP_USR', passwordVariable: 'PRIME_APP_PSW')]){
                        sh    "./Prime.Stop.Listeners.sh ${PRIME_APP_USR} "+'${PRIME_APP_PSW}'+" ${CODE_SCHEMA_USR} ${DB_PRIME_LIQUIBASE_URL}"
                        }
                        break
                    case "start_listeners":
                        withCredentials([usernamePassword(credentialsId: "prime_app_schema${SCHEMA}_${REGION}", usernameVariable: 'PRIME_APP_USR', passwordVariable: 'PRIME_APP_PSW')]){
                        sh   "./Prime.Start.Listeners.sh ${PRIME_APP_USR} "+'${PRIME_APP_PSW}'+" ${CODE_SCHEMA_USR} ${DB_PRIME_LIQUIBASE_URL}"
                        }
                        break
                    case "install_rdsadmin":
                        withCredentials([usernamePassword(credentialsId: "deployment_admin_${REGION}", usernameVariable: 'DEPLOYMENT_ADMIN_USR', passwordVariable: 'DEPLOYMENT_ADMIN_PSW')]){
                        sh    "./install_rdsadmin.sh ${DB_PRIME_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" RDSADMIN ${RDSADMIN_DIRECTORIES_PATH} /oracle/app/scripts DEPLOY_TBS TEMP"
                        sh    "./install_rdsadmin.sh ${DB_ONLINE_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" RDSADMIN ${RDSADMIN_DIRECTORIES_PATH} /oracle/app/scripts DEPLOY_TBS TEMP"
                        }
                        break
                    case "install_admin":
                        withCredentials([usernamePassword(credentialsId: "deployment_admin_${REGION}", usernameVariable: 'DEPLOYMENT_ADMIN_USR', passwordVariable: 'DEPLOYMENT_ADMIN_PSW')]){
                        sh   "./install_admin.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" ${RDSADMIN_DIRECTORIES_PATH}"
                        sh   "./install_admin.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" ${RDSADMIN_DIRECTORIES_PATH}"
                        }
                        break
                    //dblinks stage required only after change to tnsname or password change.
                    //regular: verifying checksum in liquibase only
                    case "db_links":
                        withCredentials([
                            usernamePassword(credentialsId: "deployment_admin_${REGION}", usernameVariable: 'DEPLOYMENT_ADMIN_USR', passwordVariable: 'DEPLOYMENT_ADMIN_PSW'),
                            usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW'),
                            usernamePassword(credentialsId: "code_schema2_${REGION}", usernameVariable: 'CODE_SCHEMA2_USR', passwordVariable: 'CODE_SCHEMA2_PSW'),
                            usernamePassword(credentialsId: "data_schema_${REGION}", usernameVariable: 'DATA_SCHEMA_USR', passwordVariable: 'DATA_SCHEMA_PSW'),
                            usernamePassword(credentialsId: "tctstrm_${REGION}", usernameVariable: 'TCTSTRM_USR', passwordVariable: 'TCTSTRM_PSW')
                            ]){
                        sh    "./install_db_links.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" ${DB_HOST_ONLINE} ${DB_PORT_ONLINE} ${DB_SERVICE_ONLINE} ${DB_SERVICE_NAME_ONLINE}"+' ${CODE_SCHEMA_PSW} ${CODE_SCHEMA2_PSW} ${DATA_SCHEMA_PSW} ${TCTSTRM_PSW}'
                        sh    "./install_db_links.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" ${DB_HOST_PRIME} ${DB_PORT_PRIME} ${DB_SERVICE_PRIME} ${DB_SERVICE_NAME_PRIME}"+' ${CODE_SCHEMA_PSW} ${CODE_SCHEMA2_PSW} ${DATA_SCHEMA_PSW} ${TCTSTRM_PSW}'
                        }
                        break
                    case "prod_db_links":
                       println("Temporary fake for PROD")
                        break
                    case "install_ebr_prime":
                        withCredentials([usernamePassword(credentialsId: "ebr_${REGION}", usernameVariable: 'EBR_USR', passwordVariable: 'EBR_PSW')]){
                        sh    "./install_ebr_schema.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} "+'${EBR_PSW}'
                        }
                        withCredentials([usernamePassword(credentialsId: "data_schema_${REGION}", usernameVariable: 'DATA_SCHEMA_USR', passwordVariable: 'DATA_SCHEMA_PSW')]){
                        sh    "./install_data_schema.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} "+'${DATA_SCHEMA_PSW}'
                        }
                        break
                    case "install_ebr_online":
                        withCredentials([usernamePassword(credentialsId: "ebr_${REGION}", usernameVariable: 'EBR_USR', passwordVariable: 'EBR_PSW')]){
                        sh    "./install_ebr_schema.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} "+'${EBR_PSW}'
                        }
                        withCredentials([usernamePassword(credentialsId: "data_schema_${REGION}", usernameVariable: 'DATA_SCHEMA_USR', passwordVariable: 'DATA_SCHEMA_PSW')]){
                        sh    "./install_data_schema.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} "+'${DATA_SCHEMA_PSW}'
                        }
                        break
                    case "install_code_schema":
                        withCredentials([usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW')]){
                        sh    "./install_code_schema.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'
                        sh    "./install_code_schema.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'
                        }
                        break
                    case "install_tctstrm_schema":
                        withCredentials([usernamePassword(credentialsId: "tctstrm_${REGION}", usernameVariable: 'TCTSTRM_USR', passwordVariable: 'TCTSTRM_PSW')]){
                        sh    "./install__tctstrm_schema.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} "+'${TCTSTRM_PSW}'
                        sh    "./install__tctstrm_schema.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} "+'${TCTSTRM_PSW}'
                        }
                        break
                    case "install_institutions_admin":
                        withCredentials([usernamePassword(credentialsId: "deployment_admin_${REGION}", usernameVariable: 'DEPLOYMENT_ADMIN_USR', passwordVariable: 'DEPLOYMENT_ADMIN_PSW')]){
                        sh    "./install_institutions_admin.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" ${INSTITUTION_LIST}"
                        sh    "./install_institutions_admin.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} ${DEPLOYMENT_ADMIN_USR} "+'${DEPLOYMENT_ADMIN_PSW}'+" ${INSTITUTION_LIST}"
                        }
                        break
                    case "install_post_code_schema":
                        withCredentials([usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW')]){
                        sh    "./install_post_code_schema.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'
                        sh    "./install_post_code_schema.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'
                        }
                        break
                    case "install_post_tctstrm_schema":
                        withCredentials([usernamePassword(credentialsId: "tctstrm_${REGION}", usernameVariable: 'TCTSTRM_USR', passwordVariable: 'TCTSTRM_PSW')]){
                        sh   "./install_post_tctstrm_schema.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} "+'${TCTSTRM_PSW}' 
                        }
                        break
                    default:
                        println("Entered DB patch role is unknown")
                        break
                }
            }
        }
    }
}

def db_config() {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: "database").split(' ')) {
        node ( tools.getJenkinsSlave(name: "${server}")) {
            dir('prime-configs'){
                withCredentials([usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW')]){
                 sh   "./install.sh ${SYSTEM_NAME_PRIME} ${DB_PRIME_LIQUIBASE_URL} ${DATA_SCHEMA_USR} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}'+ " ${CONFIG_INSTITUTION_LIST}"
                }
                if ( SCHEMA == "1" ) { 
                withCredentials([usernamePassword(credentialsId: "code_schema${SCHEMA}_${REGION}", usernameVariable: 'CODE_SCHEMA_USR', passwordVariable: 'CODE_SCHEMA_PSW')]){        
                     sh   "./install.sh ${SYSTEM_NAME_ONLINE} ${DB_ONLINE_LIQUIBASE_URL} ${DATA_SCHEMA_USR} ${CODE_SCHEMA_USR} "+'${CODE_SCHEMA_PSW}' +" ${CONFIG_INSTITUTION_LIST}"
                }
                }
            }
        }
    }
}

def stop_app(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: app).split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}", role: app)

                sh """
                    ssh ${server_user}@${server} \"rm -rf /home/${server_user}/jenkins; mkdir -p /home/${server_user}/jenkins\"    
               
                    scp ./src/${app}_operation.sh ${server_user}@${server}:/home/${server_user}/jenkins/${app}_operation.sh

                    ssh ${server_user}@${server} 'chmod +x ~/jenkins/*.sh'

                    ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh stop\"
                    ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh status\"
                """
            }
        }
    }
}

def start_app(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: app).split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}", role: app)

                try {
                    if (app == "online") {
                        sh """
                            ssh ${server_user}@${server} "for i in \\\$(ps -e -o pid,command | grep \"/home/online/bin/runplsql\" | grep -v grep | awk '{print \\\$1}'); do kill -9 \\\$i; done;"
                        """
                    }

                    timeout(2) {
                        sh """
                            ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh start\"
                            ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh status\"
                        """
                    }
                }
                catch (Exception e) {
                    echo "Error" + e.toString()
                }
            }
        }
    }
}

def patch_app(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: app).split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}", role: app)
                //Hint for loclnode(nd).ini renaming
                nd = tools.getNode(name: "${server}", role: app)
                sh """
                    scp ./install/app-${app}-*.tar.gz ${server_user}@${server}:/home/${server_user}/jenkins/

                    ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh deploy ${server_user} ${PATCH_VERSION} ${REGION}\"
                    ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh install ${server_user} ${PATCH_VERSION}\"
                """
                if (app == 'online') {
                    switch (nd) {
                        case ('1'):
                        sh """
                        ssh ${server_user}@${server} \"cd /home/${server_user}/jenkins; tar -xzf app-${app}-*.tar.gz artifacts/online/data/loclnode.ini\"
                        ssh ${server_user}@${server} \"cp /home/${server_user}/jenkins/artifacts/online/data/loclnode.ini /home/${server_user}/data/loclnode.ini\"
                        """
                        break
                        //change for nd==2,3,4
                        default:
                        sh """
                        ssh ${server_user}@${server} \"cd /home/${server_user}/jenkins; tar -xzf app-${app}-*.tar.gz artifacts/online/data/loclnode.ini${nd}\"
                        ssh ${server_user}@${server} \"cp /home/${server_user}/jenkins/artifacts/online/data/loclnode.ini${nd} /home/${server_user}/data/loclnode.ini\"
                        """
                        break
                    }
                }
            }
        }
    }
}

// FGA RBA BRMS update
def rba_fga_brms(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: app).split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}")

                sh """
                    ssh ${server_user}@${server} \"rm -rf /home/${server_user}/jenkins; mkdir -p /home/${server_user}/jenkins\"
                """
                switch (app) {
                    case ("brms"):
                    sh """
                    scp ./install/app-${app}-*.tar.xz ${server_user}@${server}:/home/${server_user}/jenkins/
                    ssh ${server_user}@${server} \"cd jenkins; tar -xf /home/${server_user}/jenkins/app-brms-*.tar.xz\"
                    ssh ${server_user}@${server} \"cd /home/${server_user}/Wildfly/bin; ./stopBRMS.sh >/dev/null 2>&1 &\"
                    ssh ${server_user}@${server} \"cd /home/${server_user}/Wildfly/bin; ./stopService.sh >/dev/null 2>&1 &\"
                    sleep 20
                    ssh ${server_user}@${server} \"cp -a /home/${server_user}/jenkins/artifacts/wildfly/bin/lib/* /home/${server_user}/Wildfly/bin/lib\"
                    ssh ${server_user}@${server} \"cd /home/${server_user}/Wildfly/bin; ./runService.sh >/dev/null 2>&1 &\"
                    ssh ${server_user}@${server} \"cd /home/${server_user}/Wildfly/bin; ./runBRMS.sh >/dev/null 2>&1 &\"
                """
                break
                // fga rba by default
                default:
                    sh """
                    scp ./install/app-${app}-*.zip ${server_user}@${server}:/home/${server_user}/jenkins/
                    ssh ${server_user}@${server} \"cd jenkins; unzip -q /home/${server_user}/jenkins/app-${app}-*.zip\"
                    ssh ${server_user}@${server} \"/home/${server_user}/${app}/bin/stop.sh\"
                    sleep 20
                    ssh ${server_user}@${server} \"cp -a /home/${server_user}/jenkins/${app}/lib/* /home/${server_user}/${app}/lib\"
                    ssh ${server_user}@${server} \"cd /home/${server_user}/${app}/bin; ./start.sh >/dev/null 2>&1 &\"

                """
                break
                }
            }
        }
    }
}

def prime_web(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: app).split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}")
                tools.prepare_primeweb(server, server_user)
                switch(app) {
                    case "app":
                        sh """
                            ssh ${server_user}@${server} "powershell \\\"${INSTALL_SOURCE_PATH_WINDOWS}\\\\${WINDOWS_INSTALLER_PATH} -m 3 -c 2 -f ${WINDOWS_CONFIG_PATH}\\\\Prime4Installer_template.config\\\""
                        """
                        break
                    case "web":
                        sh """
                            ssh ${server_user}@${server} "powershell \\\"${INSTALL_SOURCE_PATH_WINDOWS}\\\\${WINDOWS_INSTALLER_PATH} -m 3 -c 3 -f ${WINDOWS_CONFIG_PATH}\\\\Prime4Installer_template.config\\\""
                            ssh ${server_user}@${server} "powershell \\\"${INSTALL_SOURCE_PATH_WINDOWS}\\\\${WINDOWS_INSTALLER_PATH} -m 3 -c 4 -f ${WINDOWS_CONFIG_PATH}\\\\Prime4Installer_template.config\\\""
                            ssh ${server_user}@${server} "powershell \\\"${INSTALL_SOURCE_PATH_WINDOWS}\\\\${WINDOWS_INSTALLER_PATH} -m 3 -c 6 -f ${WINDOWS_CONFIG_PATH}\\\\Prime4Installer_template.config\\\""
                        """
                        break
                    case "notification":
                        sh """
                            ssh ${server_user}@${server} "powershell \\\"${INSTALL_SOURCE_PATH_WINDOWS}\\\\${WINDOWS_INSTALLER_PATH} -m 3 -c 7 -f ${WINDOWS_CONFIG_PATH}\\\\Prime4Installer_template.config -nowebservices\\\""
                        """
                        break
                    // Prime Thick Client on Utility servers
                    default:
                        sh """
                            ssh ${server_user}@${server} "powershell \\\"${INSTALL_SOURCE_PATH_WINDOWS}\\\\${WINDOWS_INSTALLER_PATH} -m 3 -c 8 -f ${WINDOWS_CONFIG_PATH}\\\\Prime4Installer_template.config -nowebservices\\\""
                        """
                        break
                }
            }
        }
    }
}

// ONLINE Thick Clients, Key Management, artifacts for WIN. Deployment on Utility(Terminal) servers
def otc_km(String operation) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: 'utility').split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}")
                def artifactname = tools.tc_prep(server, server_user, operation, WINDOWS_ARTIFACTS_PATH)
                switch (operation) {
                    case ("online-thick"):
                    sh """
                    scp ./src/win/otc_get_process.ps1 ${server_user}@${server}:/home/${server_user}/jenkins/otc_get_process.ps1
                    ssh ${server_user}@${server} "chmod u+x /home/${server_user}/jenkins/otc_get_process.ps1"
                    ssh ${server_user}@${server} "powershell \\\"./jenkins\\\\otc_get_process.ps1\\\""
                    ssh ${server_user}@${server} "powershell \\\"./jenkins\\\\win_ops.bat ${artifactname} ${INSTALL_OTC_PATH}\\\""
                    """
                    break
                    default:
                    sh """
                    ssh ${server_user}@${server} "powershell \\\"./jenkins\\\\win_ops.bat ${artifactname} ${INSTALL_OTC_PATH}\\\""
                    """ 
                    break
                }
            }
        }
    }
}

// HSMI (NCrypt fo Win) with Key-Mgmt artifacts for Boris Glushkov separated Win station
def ncrypt_win(String operation) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: 'hsmi').split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}")
                def artifactname = tools.tc_prep(server, server_user, operation, HSMI_ARTIFACTS_PATH)
                switch (operation) {
                case ("hsmi"):
                sh """
                ssh ${server_user}@${server} "powershell "net stop hsmiserver""
                ssh ${server_user}@${server} "powershell \\\"./jenkins\\\\win_ops.bat ${artifactname} ${INSTALL_HSMI_PATH}\\\""
                ssh ${server_user}@${server} "powershell "net start hsmiserver""
                """
                break
                default:
                sh """
                ssh ${server_user}@${server} "powershell \\\"./jenkins\\\\win_ops.bat ${artifactname} ${INSTALL_HSMI_PATH}\\\""
                """
                break
                }
            }
        }
    }
}

//Igor Kirienko priceless gift for TSYS PRYME
//          __ ACSweb1____ ____ACSapp1_____OnlineDB1_______
//         |              V                 Schema1        |
//         |              |                           Hsmi--OnlineApp1
//      F5-|             NLB2
//         |              |                           Hsmi--OnlineApp2
//         |              |                                |              
//         |___ACSweb2____A____ACSapp2_____OnlineDB2_______|
//                                          Schema2
// Sync ACS Application with hsmi/online app during main deployment
def nlb2_acsapp(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: 'nlb').split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) { //check if there are hosts in schema 2. if NOT - skip stage
        node ("${REGION}") { server_user = 'jenkwinadm_sa'
                if (app == 'disable') {
                    sh """ssh ${server_user}@${server} "powershell Stop-NlbClusterNode -Drain -Timeout 120 -HostName ${server}" """
                }
                if (app == 'enable') {
                    sh """ssh ${server_user}@${server} "powershell Start-NlbClusterNode -HostName ${server}" """
                }
            }
        }
    }
}

// JIRA TSYS ISSUE UPDATE
def jira_update () {
// post to Jira
def j_field = "cf[10322]"
def j_transition = [ transition: [ id : 31]]
def j_comment =  [ body: """
Artifact Name: ${env.PATCH_VERSION}
Deployment Unique ID: ${env.IMPLEMENTATION_ID}
Region Deployed: ${env.REGION}
Schema:  ${env.SCHEMA}
Deployment Start time & Date: ${startTime}
Deployment Finish time & Date: ${finishTime}
Deployment status: ${currentBuild.result}
 """ ]

if (currentBuild.result == 'SUCCESS'){
    if (SCHEMA == '2'||REGION.contains('QA')){
        def result = jiraJqlSearch jql:"${j_field} ~ ${env.IMPLEMENTATION_ID}", site: 'JIRA_TSYS'
        for (def issue in result.data.issues) {
                    jiraAddComment idOrKey: "${issue.key}", site: 'JIRA_TSYS', input: j_comment
                    jiraTransitionIssue idOrKey: "${issue.key}", site: 'JIRA_TSYS', input: j_transition
        }
    }       else    {
                    sh "echo 'Hi! Its only SCHEMA${env.SCHEMA} & ${env.REGION} region!'"
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
        Region Deployed: ${env.REGION}
        Schema:  ${env.SCHEMA}
        Deployment Start time & Date: ${startTime}
        Deployment Finish time & Date: ${finishTime}
        Deployment Status: ${currentBuild.result}""", 
            from: 'prime_news@ucscards.ru',
            subject: "Build Log ${env.REGION}, ${BUILD_DISPLAY_NAME}, schema ${env.SCHEMA}, ${env.PATCH_VERSION},  ${currentBuild.result}", 
            to: "${env.MAIL_RECIPIENTS_DEV}";
}

return this
