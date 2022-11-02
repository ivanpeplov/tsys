pipeline {
    agent {label ('master')}
    parameters {
        choice(name: 'REGION', choices: ['QA1', 'QA2', 'UAT1', 'UAT2', 'PROD', 'PREPROD'], description: '')
        choice(name: 'SCHEMA', choices: ['1', '2'], description: '')
        booleanParam(name: "ONLINE_APP", defaultValue: false)
        booleanParam(name: "DOWNLOAD_ARTIFACTS", defaultValue: true)
        booleanParam(name: "PATCH_ACS_APP", defaultValue: true)
        booleanParam(name: "PATCH_ACS_WEB", defaultValue: true)
        booleanParam(name: "PATCH_ACS_CS", defaultValue: true)
    } //parameters end
    stages {
        // Stage 1 (Set Global Env)
        stage('Set Global Env') {
            steps {
                script {
                    patch = load "${WORKSPACE}/lib/libpatch_acs.groovy"
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
                    patch.download_artifacts()
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
                    expression { return env.ONLINE_APP.toBoolean() }
                    expression { return env.PATCH_ACS_APP.toBoolean() }
                    expression { return env.PATCH_ACS_CS.toBoolean() }
                    expression { return env.PATCH_ACS_WEB.toBoolean() }
                }
            }
            steps {
                script {
                    switch(REGION) {
                        case ["PROD", "UAT1", "UAT2"]:
                        patch.nlb2_acsapp('disable')
                        patch.f5_acsweb('disable')
                        break
                        default:
                        println('No NLB/F5 for QA/PREPROD')
                        break
                    }
                    echo 'stop online'
                    //patch.state_app('online', 'stop')
                }
            }
        }
        // Stage 5.3 (Stop NCRYPT Application )
        stage('Stop NCrypt') {
            when {
                anyOf {
                    expression { return env.PATCH_ACS_APP.toBoolean() }
                    expression { return env.PATCH_ACS_CS.toBoolean() }
                    expression { return env.PATCH_ACS_WEB.toBoolean() }
                }
            }
            steps {
                script {
                    echo 'stop ncrypt'
                    //patch.state_app('ncrypt', 'stop')
                }
            }
        }
        //    *** Windows stages
        // Stage 27 (Update App Servers)
        stage('Update: App Servers') {
            when {
                expression { return env.PATCH_ACS_APP.toBoolean() }
            }
            steps {
                script {
                    echo 'update app'
                    patch.prepare_app_cs_web('APP.AccessControlServer', 'app')
                    patch.prepare_app_cs_web('APP.IdentityService', 'app')
                    patch.start_app_cs_web('app')
                }
            }
        }
        // Stage 28 (Update Web Servers)
        stage('Update: Web Servers') {
            when {
                expression { return env.PATCH_ACS_WEB.toBoolean() }
            }
            steps {
                script {
                    echo 'update web'
                    patch.prepare_app_cs_web('Authentication', 'web')
                    patch.prepare_app_cs_web('Challenge', 'web')
                    patch.start_app_cs_web('web')
                }
            }
        }
        // Stage 29 (Update CS Servers)
        stage('Update: CS Servers') {
            when {
                expression { return env.PATCH_ACS_CS.toBoolean() }
            }
            steps {
                script {
                    echo 'update cs'
                    patch.prepare_app_cs_web('WEB.AccessControlServerCustomerServices', 'cs')
                    patch.prepare_app_cs_web('WEB.AccessControlServerAdministration', 'cs')
                    patch.start_app_cs_web('cs')
                }
            }
        }

        // Stage 23.1 (Start NCrypt Application)
        stage('Start NCrypt') {
            when {
                anyOf {
                    expression { return env.PATCH_ACS_APP.toBoolean() }
                    expression { return env.PATCH_ACS_CS.toBoolean() }
                    expression { return env.PATCH_ACS_WEB.toBoolean() }
                }
            }
            steps {
                script {
                    echo 'start ncrypt'
                    //patch.state_app('ncrypt', 'start')
                }
            }
        }
        // Stage 23.2 (Start ONLINE Application)
       stage('Start Online') {
            when {
                anyOf {
                    expression { return env.ONLINE_APP.toBoolean() }
                    expression { return env.PATCH_ACS_APP.toBoolean() }
                    expression { return env.PATCH_ACS_CS.toBoolean() }
                    expression { return env.PATCH_ACS_WEB.toBoolean() }
                }
            }
            steps {
                script {
                    echo 'start online'
                    //patch.state_app('online', 'start')
                    switch(REGION) {
                    case ["PROD", "UAT1", "UAT2"]:
                        patch.nlb2_acsapp('enable')
                        patch.f5_acsweb('enable')
                        break
                        default:
                        println('No NLB/F5 for QA/PREPROD')
                        break
                    }
                }
            }
        }
        
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
                //patch.send_email()               
                // post to Jira
                echo "JIRA actions"
                //patch.jira_update ()
                // Clean Workspace
            cleanWs()
            }//script
        }//always
    } //post actions
} //pipeline
