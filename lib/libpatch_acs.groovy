def set_global_env() {
    echo 'REGION and SCHEMA + base_url for local nexus'
    env.REGION = params.REGION
    env.SCHEMA = params.SCHEMA
    base_url = 'http://10.255.252.161:8081/repository/prime-artifacts-local/'
    //selection REGION from json, verifying with initial params.REGION
    filename='manifest.json'
    remoteUrl = base_url + filename
    tools.downloadFile(remoteUrl,filename)
    def props = readJSON file: './manifest.json'
    region_json = props.REGION."${REGION}"
        try {
            if (region_json==false) {
                println('REGION not selected, aborting')
                error('autoStop')
            }
    } catch(e) {
        if (e.message == 'autoStop') {
            currentBuild.result = 'ABORTED'
        }
        throw e
    }
}
currentBuild.description = "region:${env.REGION}\nschema:${env.SCHEMA}"
//Reading from environment.yaml
def set_region_env() {
    def yaml_cfg = readYaml file: "${WORKSPACE}/config/environment_acs.yaml"
    ZIP_PATH = yaml_cfg.get('zip_path')
    UNZIP_PATH = yaml_cfg.get('unzip_path')
    READY_PATH = yaml_cfg.get('ready_path')
    FINAL_DIR = yaml_cfg.get('final_dir')
    FINAL_DIR_TEST = yaml_cfg.get('final_dir_test')
    BACKUP_PATH = yaml_cfg.get('backup_path')
    env.MAIL_RECIPIENTS_DEV = yaml_cfg.get('mail_recipients_dev')
    //folder aliases for start_app_cs_web
    CS_CS_TARGET = yaml_cfg.get('cs_cs_target')
    CS_ADM_TARGET = yaml_cfg.get('cs_adm_target')
    CS_API_TARGET = yaml_cfg.get('cs_api_target')
    WEB_AUTH_TARGET = yaml_cfg.get('web_auth_target')
    WEB_CHAL_TARGET = yaml_cfg.get('web_chal_target')
    APP_ACS_TARGET = yaml_cfg.get('app_acs_target')
    APP_ID_TARGET = yaml_cfg.get('app_id_target')

    servers = yaml_cfg.server
}
//download acs artifact
def download_artifacts() {
    node ( "${REGION}" ) {
        // checkout our git
        checkout changelog: false, poll: false, scm: [
            $class: 'GitSCM', 
            branches: [[name: '*/master']], 
            extensions: [], 
            userRemoteConfigs: [[credentialsId: 'jenkins_gitlab_token', url: 'http://10.255.250.30/prime/jenkins-scripts.git']]]
            sh """
                [ ! -d distr ] || rm -rf distr
                mkdir -p distr
            """     
            //download from local  nexus. TODO: define constant repository
            filename='ACS_2022-04-04.zip'
            remoteUrl = base_url + REGION + '/' + filename  
            tools.downloadFile(remoteUrl,filename)
            //unzip and remove initial acs_artifact.zip
            unzip zipFile:'./ACS_2022-04-04.zip', dir:'./distr', quiet: true
            sh "rm -f ./ACS_2022-04-04.zip"
            }
        }
//Prepare artifacts for acs applications deployment
def prepare_app_cs_web(String operation, String role) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: role).split(' ')) {
        node ("${REGION}") {
        server_user = 'jenkwinadm_sa'
            //findfile in $workspace, Pipeline utility plugin
            // get an array *.7z sub-artifacts filename.ext strings | APP.AccessControlServer-20220404-533429.7z
            def f_name = []
            def file = findFiles(glob: '**/*.7z')
            file.each { f ->
            if (f.name) {
            f_name.add(f.name) } }
            //find required sub-app operation
            artifactname = f_name.findAll{it.contains(operation)}[0].toString()
            echo "${artifactname}"
            def zip_path = ZIP_PATH.replaceAll('\\\\','/')
            def unzip_path = UNZIP_PATH.replaceAll('\\\\','/')
            def ready_path = READY_PATH.replaceAll('\\\\','/')
            //list of configs.json for *.7z sub-artifacts, Pipeline utility plugin
            //get an array full filepath strings | distr/configs/QA1/app/APP.AccessControlServer/config1.json
            def j_name = []
            def j_file = findFiles(glob: '**/*.json')
            j_file.each { f ->
            if (f.path) {
            j_name.add(f.path) } }
            //get .json file and full path to .json
            def configs_path = j_name.findAll{it.contains(REGION)&&it.contains(role)&&it.contains(operation)}[0].toString()
            // distr/configs/QA1/app/APP.AccessControlServer/config1.json
            echo "${configs_path}"
            js_parts = configs_path.split("/");
            config_json= js_parts[js_parts.length-1];
            if (operation=='APP.AccessControlServer'){config_json='config.json'}
            echo "${config_json}"
            // config1.json (for acsweb AUTH)
            sh """
            ssh ${server_user}@${server} "rm -rf /home/${server_user}/jenkins; mkdir -p /home/${server_user}/jenkins"
            ssh ${server_user}@${server} "rm -rf ${zip_path}/*; mkdir -p ${zip_path}"
            ssh ${server_user}@${server} "rm -rf ${unzip_path}/*; mkdir -p ${unzip_path}"
            set +e
            scp ./src/win/win_ops.bat ${server_user}@${server}:/home/${server_user}/jenkins/win_ops.bat
            scp ./distr/binaries/${artifactname} ${server_user}@${server}:${zip_path}
            """
            //block for uat/prod region using f5/nlb units
            if (operation=='APP.AccessControlServer') {sh "scp ./${configs_path} ${server_user}@${server}:${zip_path}/config.json"}
            else {sh "scp ./${configs_path} ${server_user}@${server}:${zip_path}"}
            sh  """
            ssh ${server_user}@${server} "chmod u+x /home/${server_user}/jenkins/win_ops.bat"
            ssh ${server_user}@${server} "powershell \\\"./jenkins\\\\win_ops.bat ${ZIP_PATH}\\\\${artifactname} ${UNZIP_PATH}\\\\${operation}\\\""
            """
            //saving idssigning.pfx for APP.IdentityService
            if (operation =='APP.IdentityService') { sh """
            ssh ${server_user}@${server} "powershell copy-item  -force \\\"${FINAL_DIR}\\\\${operation}\\\\idssigning.pfx ${UNZIP_PATH}\\\\${operation}\\\\build \\\""
            """ }
            //adding new config.json and saved *.config from working app
            sh """
            ssh ${server_user}@${server} "powershell copy-item  -force \\\"${ZIP_PATH}\\\\${config_json} ${UNZIP_PATH}\\\\${operation}\\\\build \\\""
            """
            //condition for acsweb for MPS: RNPS(now), VISA-MC(later)
            if (operation =='Authentication') {sh """
            ssh ${server_user}@${server} "powershell copy-item  -force \\\"${FINAL_DIR}\\\\${operation}\\\\RNPS\\\\*.config ${UNZIP_PATH}\\\\${operation}\\\\build \\\""
            ssh ${server_user}@${server} "powershell copy-item -Recurse -force \\\"${UNZIP_PATH}\\\\${operation}\\\\build ${READY_PATH}\\\\${operation}\\\\RNPS \\\""
            """ }
            sh """
            ssh ${server_user}@${server} "powershell copy-item  -force \\\"${FINAL_DIR}\\\\${operation}\\\\*.config ${UNZIP_PATH}\\\\${operation}\\\\build \\\""
            ssh ${server_user}@${server} "powershell copy-item -Recurse -force \\\"${UNZIP_PATH}\\\\${operation}\\\\build ${READY_PATH}\\\\${operation} \\\""
            """         
        }
    }
}
//Update acs applications
def start_app_cs_web(String role) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: role).split(' ')) {
        node ("${REGION}") {
        server_user = 'jenkwinadm_sa'
            def zip_path = ZIP_PATH.replaceAll('\\\\','/')
            def unzip_path = UNZIP_PATH.replaceAll('\\\\','/')
            def ready_path = READY_PATH.replaceAll('\\\\','/')
            sh """
            ssh ${server_user}@${server} "powershell iisreset \\\"/stop\\\""
            sleep 10s
            ssh ${server_user}@${server} "powershell Compress-Archive \\\" -Update -Path ${FINAL_DIR}\\\\* -DestinationPath ('${BACKUP_PATH}\\\\${role}_' + (get-date -Format yyyyMMdd) + '_.zip')\\\""
            """
            switch (role) {
                case ('cs'):
                sh """
                ssh ${server_user}@${server} "powershell rm -r -fo \\\"${FINAL_DIR}\\\\${CS_CS_TARGET} \\\""
                ssh ${server_user}@${server} "powershell rm -r -fo \\\"${FINAL_DIR}\\\\${CS_ADM_TARGET} \\\""
                """
                break
                case ('web'):
                sh """
                ssh ${server_user}@${server} "powershell rm -r -fo \\\"${FINAL_DIR}\\\\${WEB_AUTH_TARGET} \\\""
                ssh ${server_user}@${server} "powershell rm -r -fo \\\"${FINAL_DIR}\\\\${WEB_CHAL_TARGET} \\\""
                """
                break
                default:
                sh """
                ssh ${server_user}@${server} "powershell rm -r -fo \\\"${FINAL_DIR}\\\\${APP_ACS_TARGET} \\\""
                ssh ${server_user}@${server} "powershell rm -r -fo \\\"${FINAL_DIR}\\\\${APP_ID_TARGET} \\\""
                """
                break }
            sh """
            ssh ${server_user}@${server} "powershell copy-item -Recurse -Force \\\"${READY_PATH}\\\\* ${FINAL_DIR_TEST} \\\""
            ssh ${server_user}@${server} "powershell iisreset \\\"/start\\\""
            ssh ${server_user}@${server} "rm -rf ${zip_path}/*; mkdir -p ${zip_path}"
            ssh ${server_user}@${server} "rm -rf ${unzip_path}/*; mkdir -p ${unzip_path}"
            ssh ${server_user}@${server} "rm -rf ${ready_path}/*; mkdir -p ${ready_path}"
            """           
        }
    }
}
//stop online/ncrypt services
def stop_app(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: app).split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}", role: app)
                sh  """
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
//start online/ncrypt services
def start_app(String app) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: app).split(' ')) {
        if (tools.getJenkinsSlave(name: "${server}")) {
            node ( tools.getJenkinsSlave(name: "${server}")) {
                server_user = tools.getServerUser(name: "${server}", role: app)
                try { if (app == "online") { sh  """
                        ssh ${server_user}@${server} "for i in \\\$(ps -e -o pid,command | grep \"/home/online/bin/runplsql\" | grep -v grep | awk '{print \\\$1}'); do kill -9 \\\$i; done;"
                        """ }
                    timeout(2) { sh  """
                        ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh start\"
                        ssh ${server_user}@${server} \"/home/${server_user}/jenkins/${app}_operation.sh status\"
                        """ } }
                catch (Exception e) { echo "Error" + e.toString() }      
            }
        }
    }
}
//--------------------------------------------------------------------
//          __ ACSweb1____ ____ACSapp1_____OnlineDB1_______
//         |              V                 Schema1        |
//         |              |                           Hsmi--OnlineApp1
//      F5-|             NLB2
//         |              |                           Hsmi--OnlineApp2
//         |              |                OnlineDB2       |              
//         |___ACSweb2____A____ACSapp2______Schema2________|
//
//NLB2 (win) switching for acsapp servers
def nlb2_acsapp(String state) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: 'app').split(' ')) {
        node ("${REGION}") {
            server_user = 'jenkwinadm_sa'
            //Hint for loclnode(nd).ini renaming
            nd = tools.getNode(name: "${server}", role: 'app')
            echo "$nd"
            if (state == 'disable') {
                switch (nd) {
                    case ('1'): sh """
                    ssh ${server_user}@${server} "powershell Stop-NlbClusterNode -Drain -Timeout 120 -HostName ${server}" """
                    break
                    case ('2'): sh """
                    ssh ${server_user}@${server} "powershell Stop-NlbClusterNode -Drain -Timeout 120 -HostName ${server}" """
                    break
                    case ('3'): sh """
                    ssh ${server_user}@${server} "powershell Stop-NlbClusterNode -Drain -Timeout 120 -HostName ${server}" """
                    break
                    case ('4'): sh """
                    ssh ${server_user}@${server} "powershell Stop-NlbClusterNode -Drain -Timeout 120 -HostName ${server}" """
                    break
                    default:
                    echo 'nothing'
                    break
                    }
                }
            if (state == 'enable') {
                switch (nd) {
                    case ('1'): sh """
                    ssh ${server_user}@${server} "powershell Start-NlbClusterNode -HostName ${server}" """
                    break
                    case ('2'): sh """
                    ssh ${server_user}@${server} "powershell Start-NlbClusterNode -HostName ${server}" """
                    break
                    case ('3'): sh """
                    ssh ${server_user}@${server} "powershell Start-NlbClusterNode -HostName ${server}" """
                    break
                    case ('4'): sh """
                    ssh ${server_user}@${server} "powershell Start-NlbClusterNode -HostName ${server}" """
                    break
                    default:
                    echo 'nothing'
                    break
                }
            }
        }
    }
}
//F5 (hardware) switching for acsweb servers
def f5_acsweb(String state) {
    for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "PROD", role: 'ff').split(' ')) {
    echo "${server}"
        node ("${REGION}") {
            server_user = 'jenkins'
            nd = tools.getNode(name: "${server}", role: 'ff').toString()
            nd1 = nd.substring(2)
            if (state == 'disable') { sh """  
            ssh ${server_user}@${server} modify /ltm node  PRO${nd1}-ACSWeb${SCHEMA} state user-down session user-disabled
            ssh ${server_user}@${server} show ltm node PRO${nd1}-ACSWeb${SCHEMA} """}
            if (state == 'enable') { sh """ 
            ssh ${server_user}@${server} modify /ltm node  PRO${nd1}-ACSWeb${SCHEMA} state user-up session user-enabled
            ssh ${server_user}@${server} show ltm node PRO${nd1}-ACSWeb${SCHEMA}  """}       
        }
    }
}
//F5 (hardware) switching for acsweb servers UAT1/2
def f5_acsweb_uat(String state) {
     for (server in tools.getServersByRole(schema: "${SCHEMA}", region: "${REGION}", role: 'ff').split(' ')) {
        node ("${REGION}") {
            server_user = 'jenkins'
            //u2_f5 = '172.17.50.236'
            //u1_f5 = '172.16.50.236'
            nd=REGION.substring(3)
            if (state == 'disable') { sh """
                ssh ${server_user}@${server} modify /ltm node TSYS-U${nd}-ACS-WEB${SCHEMA}  state user-down session user-disabled
                ssh ${server_user}@${server} show ltm node TSYS-U${nd}-ACS-WEB${SCHEMA}  """}
            if (state == 'enable') { sh """
                ssh ${server_user}@${server} modify /ltm node TSYS-U${nd}-ACS-WEB${SCHEMA} state user-up session user-enabled
                ssh ${server_user}@${server} show ltm node TSYS-U${nd}-ACS-WEB${SCHEMA}  """}
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