pipeline {
    agent {label ('master')}
    parameters {
        base64File 'xmlConfigBase64'
        string(name: 'BASE_URL', defaultValue: 'http://10.255.250.50:8081/repository/prime-artifacts', description: '', trim: true)
        choice(name: 'REGION', choices: ['PREPROD', 'QA1', 'QA2', 'UAT1', 'UAT2', 'PROD'], description: '')
        choice(name: 'SCHEMA', choices: ['1', '2'], description: '')
        //text(name: 'EXCLUDE', defaultValue: '', description: 'Multiline string to exclude artifacts')
        booleanParam(name: "DOWNLOAD_ARTIFACTS", defaultValue: true)
        booleanParam(name: "PATCH_ONLINE_APP", defaultValue: true)
        booleanParam(name: "PATCH_NCRYPT_APP", defaultValue: true)
        booleanParam(name: "PATCH_DB", defaultValue: true)
        booleanParam(name: "PATCH_DB_CONFIGS", defaultValue: true)
        booleanParam(name: "PATCH_RBA_APP", defaultValue: true)
        booleanParam(name: "PATCH_FGA_APP", defaultValue: true)
        booleanParam(name: "PATCH_BRMS_APP", defaultValue: true)
        booleanParam(name: "PATCH_PRIMEWEB", defaultValue: true)
        booleanParam(name: "PATCH_OTC", defaultValue: true)
        booleanParam(name: "PATCH_KM", defaultValue: true)
        booleanParam(name: "PATCH_HSMI", defaultValue: true)
    } //parameters end
    stages {
        // Stage 1 (Set Global Env)
        stage('Set Global Env') {
            steps {
                script {
                        patch = load "${WORKSPACE}/lib/libpatch.groovy"
                        tools = load "${WORKSPACE}/lib/libtools.groovy"

                    patch.set_global_env()
                }
            }
        }
        // Stage 3 (Set Region Env)
        stage('Set Region Env') {
            steps {
                script {
                    patch.set_region_env()
                    patch.check_artifact_params()
                }
            }
        } 
        // Stage 4 (Download Artifacts)
        stage('Download Artifacts') {
            when {
                expression { return params.DOWNLOAD_ARTIFACTS }
            }
            steps {
                script {
                    retry(2) {
                        patch.download_artifacts()
                    }
                }
            }
        }
        /* 
            Modification ALERT
            ALL Preliminary steps completed !
            Next pipeline stages are modifying data !
        */
        //    *** Linux stages    
        // Stage 5.2 (Stop ONLINE Application)
        stage('Stop Online') {
            when {
                anyOf {
                    expression { return env.PATCH_NCRYPT_APP.toBoolean() }
                    expression { return env.PATCH_ONLINE_APP.toBoolean() }
                    expression { return env.PATCH_DB.toBoolean() }
                    expression { return env.PATCH_DB_CONFIGS.toBoolean() }
                }
            }
            steps {
                script {
                    switch(REGION) { //acsapp server switching
                        case ["PROD", "UAT1", "UAT2"]:
                        patch.nlb2_acsapp('disable')
                        break
                        default:
                        println('No NLB for QA/PREPROD')
                        break
                    }
                    echo 'stop online'
                    patch.state_app('online', 'stop')
                }
            }
        }
        // Stage 5.3 (Stop NCRYPT Application )
        stage('Stop NCrypt') {
            when {
                anyOf {
                    expression { return env.PATCH_NCRYPT_APP.toBoolean() }
                    expression { return env.PATCH_ONLINE_APP.toBoolean() }
                }
            }
            steps {
                script {
                    echo 'stop ncrypt'
                    patch.state_app('ncrypt', 'stop')
                }
            }
        }

        // Stage 6.1 (Patch ONLINE Application)
        stage('Patch Online') {
            when {
                expression { return env.PATCH_ONLINE_APP.toBoolean() }
            }
            steps {
                script {
                    echo 'patch online'
                    patch.patch_app('online')
                }
            }
        }
        // Stage 6.2 (Patch NCRYPT Application)
        stage('Patch NCrypt') {
            when {
                expression { return env.PATCH_NCRYPT_APP.toBoolean() }
            }
            steps {
                script {
                    echo 'patch online'
                    patch.patch_app('ncrypt')
                }
            }
        }
        // Stage 7.1 (Prepare DB)
        stage('Prepare DB') {
            when {
                expression { return env.PATCH_DB.toBoolean() }
            }
            steps {
                script {
                    script {
                        patch.db_prepare("prime4db")
                    }
                }
            }
        }
        // Stage 7.2 (Prepare DB: Configs)
        stage('Prepare DB: Configs') {
            when {
                expression { return env.PATCH_DB_CONFIGS.toBoolean() }
            }
            steps {
                script {
                    script {
                        patch.db_prepare("prime-configs")
                    }
                }
            }
        }

        // Stage 7 (Disable PRIME ONLINE Streams)
        stage('Disable Streams') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("streams_stop")
                }
            }
        }
        // Stage 8 (Disable PRIME Listeners)
        stage('Disable PRIME Listeners') {
            when {
                anyOf {
                expression { return env.PATCH_DB.toBoolean() }
                expression { return env.PATCH_DB_CONFIGS.toBoolean() }
                }
            }
            steps {
                script {
                    patch.db_ops("stop_listeners")
                }
            }
        }
        // Stage 9 (Patch DB: Install RS admin)
        stage('Patch DB: Install RS admin') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("install_rdsadmin")
                }
            }
        }
        // Stage 10 (Patch DB: Install RS admin)
        stage('Patch DB: Install admin') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("install_admin")
                }
            }
        }
        // Stage 11 (Patch DB: Install dblinks)
        //dblinks stage required only after change to tnsname or password change.
        //regular: verifying checksum in liquibase only
        stage('Patch DB: Install dblinks') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                        patch.db_ops("db_links")
                }
            }
        }
        // Stage 12 (Patch DB: Install EBR PRIME)
        stage('Patch DB: Install EBR PRIME') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("install_ebr_prime")
                }
            }
        }

        // Stage 13 (Patch DB: Install EBR ONLINE)
        stage('Patch DB: Install EBR ONLINE') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("install_ebr_online")
                }
            }
        }
        // Stage 14 (Patch DB: Install Code Schema). Schema 1/2
        stage('Patch DB: Install Code Schema') {
            when {
                expression { return env.PATCH_DB.toBoolean() }
            }
            steps {
                script {
                    patch.db_ops("install_code_schema")
                }
            }
        }
        // Stage 15 (Patch DB: Install TCTSTRM Schema)
        stage('Patch DB: Install TCTSTRM Schema') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("install_tctstrm_schema")
                }
            }
        }
        // Stage 16 (Patch DB: Install Institutions Admin)
        stage('Patch DB: Install Institutions Admin') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("install_institutions_admin")
                }
            }
        }

        // Stage 17 (Patch DB: Install Post Code Schema). Schema 1/2
        stage('Patch DB: Install Post Code Schema') {
            when {
                expression { return env.PATCH_DB.toBoolean() }
            }
            steps {
                script {
                    patch.db_ops("install_post_code_schema")
                }
            }
        } 
        // Stage 18 (Patch DB: Install Post TCTSTRM Schema)
        stage('Patch DB: Install Post TCTSTRM Schema') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("install_post_tctstrm_schema")
                }
            }
        }
        // Stage 19 (Sync PRIME ONLINE Streams)
        stage('Sync Streams') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("streams_sync")
                }
            }
        }
        // Stage 20 (Enable PRIME ONLINE Streams)
        stage('Enable Streams') {
            when {
                allOf {
                    expression { return env.PATCH_DB.toBoolean() }
                    expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.db_ops("streams_start")
                }
            }
        }
        // Stage 21 (Patch DB: Configs). Schema 1/2
        //May be patched without streams restart. Only Online and Listener are required
        stage('Patch DB: Configs') {
            when {
                expression { return env.PATCH_DB_CONFIGS.toBoolean() }
            }
            steps {
                script {
                    patch.db_config()
                }
            }
        }
        // Stage 22 (Enable PRIME Listeners)
        stage('Enable PRIME Listeners') {
            when {
                anyOf {
                expression { return env.PATCH_DB.toBoolean() }
                expression { return env.PATCH_DB_CONFIGS.toBoolean() }
                }
            }
            steps {
                script {
                    patch.db_ops("start_listeners")
                }
            }
        }
        // Stage 23.1 (Start NCrypt Application)
        stage('Start NCrypt') {
            when {
                anyOf {
                    expression { return env.PATCH_NCRYPT_APP.toBoolean() }
                    expression { return env.PATCH_ONLINE_APP.toBoolean() }
                }
            }
            steps {
                script {
                    echo 'start ncrypt'
                    patch.state_app('ncrypt', 'start')
                }
            }
        }
        // Stage 23.2 (Start ONLINE Application)
       stage('Start Online') {
            when {
                anyOf {
                    expression { return env.PATCH_NCRYPT_APP.toBoolean() }
                    expression { return env.PATCH_ONLINE_APP.toBoolean() }
                    expression { return env.PATCH_DB.toBoolean() }
                    expression { return env.PATCH_DB_CONFIGS.toBoolean() }
                }
            }
            steps {
                script {
                    patch.state_app('online', 'start')
                    switch(REGION) {
                        case ["PROD", "UAT1", "UAT2"]:
                        patch.nlb2_acsapp('enable')
                        break
                        default:
                        println('No NLB for QA/PREPROD')
                        break
                    }
                }
            }
       }
        // Stage 24 (Patch FGA)
        stage('Patch FGA') {
            when {
                expression { return env.PATCH_FGA_APP.toBoolean() }
            }
            steps {
                script {
                    patch.rba_fga_brms("fg")
                }
            }
        }
        // Stage 25 (Patch RBA)
        stage('Patch RBA') {
            when {
                expression { return env.PATCH_RBA_APP.toBoolean() }
            }
            steps {
                script {
                    patch.rba_fga_brms("rba")
                }
            }
        }
        // Stage 26 (Patch BRMS)
        stage('Patch BRMS') {
            when {
                expression { return env.PATCH_BRMS_APP.toBoolean() }
            }
            steps {
                script {
                    patch.rba_fga_brms("brms")
                }
            }
        }
        //    *** Windows stages
        // Stage 27 (Update App Servers)
        stage('Update: App Servers') {
            when {
                expression { return env.PATCH_PRIMEWEB.toBoolean() }
            }
            steps {
                script {
                    patch.prime_web("app")
                }
            }
        }
        // Stage 28 (Update Web Servers)
        stage('Update: Web Servers') {
            when {
                expression { return env.PATCH_PRIMEWEB.toBoolean() }
            }
            steps {
                script {
                    patch.prime_web("web")
                }
            }
        }
        // Stage 29 (Update Notify Servers)
        stage('Update: Notify Servers') {
            when {
                expression { return env.PATCH_PRIMEWEB.toBoolean() }
            }
            steps {
                script {
                    patch.prime_web("notification")
                }
            }
        }
        // Stage 30 (Update PRIME Thick Clients)
        //Works on utility servers. It's a part of primeweb* artifact
        stage('Update: PRIME Thick Clients') {
             when { 
                allOf {
                expression { REGION == "QA1" }
                expression { return env.PATCH_PRIMEWEB.toBoolean() }
                expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.prime_web("utility")
                }
            }
        } 
        // Stage 31 (Update ONLINE Thick Clients)
        //Works on utility servers
        stage('Update: ONLINE Thick Clients') {
            when { 
                allOf {
                expression { REGION == "QA1" }
                expression { return env.PATCH_OTC.toBoolean() }
                expression  { SCHEMA == "1" }
                }
            }
            steps {
                script {
                    patch.otc_km("online-thick")
                }
            }
        } 
        // Stage 32 (Update: Key Mgmt)
        //Works on utility servers
        stage('Update: Key Mgmt') {
            when { 
                allOf {
                expression { REGION == "QA1" }
                expression { return env.PATCH_KM.toBoolean() }
                expression  { SCHEMA == "1" }
            }
            }
            steps {
                script {
                    patch.otc_km("key-man")
                }
            }
        }
         //Stage 33.1 (Update Win Ncrypt(HSMI)). Should be start before Stage 23 (Start Online & NCrypt)
         //It works only in UAT1/UAT2 regions now and includes km* and hsmi* artifacts
        stage('Update NCrypt: HSMI') {
            when { 
                allOf {
                expression { REGION == "UAT1"}
                expression { return env.PATCH_HSMI.toBoolean() }
                expression  { SCHEMA == "1" }
            }
            }
            steps {
                script {
                    patch.ncrypt_win("hsmi")
                    patch.ncrypt_win("key-man")

                }
            }
        }
        //Stage 33.2 (Update Win Ncrypt(HSMI)). Should be start before Stage 23 (Start Online & NCrypt)
        //It works only in UAT1/UAT2 regions now and includes km* and hsmi* artifacts
        /*stage('Update NCrypt: Key Mgmt') {
            when { 
                allOf {
                expression { REGION == "UAT1" }
                expression { return env.PATCH_KM.toBoolean() }
                expression  { SCHEMA == "1" }
            }
            }
            steps {
                script {
                    patch.ncrypt_win("key-man")
                }
            }
        }*/

    }   //stages finished
/*  
Stages block end
//
Post actions 
*/
    post {
        always {
            script {
                //emailing
                echo "email notification"
                patch.send_email()               
                // post to Jira
                echo "JIRA actions"
                patch.jira_update ()
                // Clean Workspace
            cleanWs()
            }//script
        }//always
    } //post actions
} //pipeline
