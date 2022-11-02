pipeline {
    agent {label ('master')}
    parameters {
        booleanParam(name: "DISABLE", defaultValue: true)
        booleanParam(name: "ENABLE", defaultValue: false)
        choice(name: 'REGION', choices: ['QA1', 'QA2', 'UAT1', 'UAT2', 'PREPROD', 'PROD'], description: '')
        choice(name: 'SCHEMA', choices: ['1', '2'], description: '')
        booleanParam(name: "STREAMS", defaultValue: true)
        booleanParam(name: "LISTENER", defaultValue: true)
    }
    stages {
        // Stage 1 (Set Global Env)
        stage('Set Global Env') {
            steps {
                script {
                        patch = load "${WORKSPACE}/lib/lib_dba.groovy"
                        tools = load "${WORKSPACE}/lib/libtools.groovy"

                    patch.set_global_env()
                    patch.map_env()
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
        // Stage 4 (Get artifacts)
        stage('Get artifacts') {
             when { anyOf {
                expression { return params.DISABLE }
                expression { return params.ENABLE }
                }
            }
            steps {
                script {
                    retry(2) {
                        patch.download_artifacts()
                    }
                }
            }
        }
        // Stage 5 (Prepare for DB)
        stage('Prepare for DB') {
            when { anyOf {
                expression { return params.DISABLE }
                expression { return params.ENABLE }
                }
            }
            steps {
                echo 'db prepare'
                    script {
                    patch.db_prepare()
                }
            }
        }
        // Stage 6 (Disable Streams)
        stage('Disable Streams') {
            when { allOf {
                expression { return params.DISABLE }
                expression { return params.STREAMS }
                }
            }
            steps {
                echo 'stop streams'
                script {
                   patch.patch_db_ops("streams_stop")
                }
            }
        }

        // Stage 7 (Disable Listeners)
        stage('Disable Listeners') {
            when { allOf {
                expression { return params.DISABLE }
                expression { return params.LISTENER }
                }
            }

            steps {
                echo 'disable listener'
                script {
                   patch.patch_db_ops("stop_listeners")
                }
            }
        }
        // Stage 8 (Enable Streams)
        stage('Enable Streams') {
            when { allOf {
                expression { return params.ENABLE }
                expression { return params.STREAMS }
                }
            }

            steps {
                echo 'enable streams'
                script {
                   patch.patch_db_ops("streams_start")
                }
            }
        }
        // Stage 9 (Enable Listeners)
        stage('Enable Listeners') {
            when { allOf {
                expression { return params.ENABLE }
                expression { return params.LISTENER }
                }
            }
            steps {
                echo 'enable listeners'
                script {
                   patch.patch_db_ops("start_listeners")
                }
            }
        }
    }
    post {
        always {
            script {
                patch.send_email()
                cleanWs()
            }
        }
    }
}
