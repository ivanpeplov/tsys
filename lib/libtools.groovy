// This is the 'include file'
import java.text.SimpleDateFormat

def getServersForSchema(Map args){
//input schema:
//output server list
//example server = getServerForSchema(schema:2)
//premise is set servers variable
    server_list = ''
    for (int i = 0; i < servers.size(); i++) {
        if ( servers[i].schema.toString() == args.schema.toString() ) {
            if ( server_list == '' ) { server_list = "${servers[i].name}" }
            else { server_list = "${server_list} ${servers[i].name}" }
        }
    }
    return server_list
}

def getServersByRole(Map args){
//input schema:<schema>, region:<QA|UAT...> role:<notification|brms...>
//output Notification server list
//example server = getNotificationServers(schema: 1, region: QA, role: app)
//premise is set servers variable
    server_list = ''
    for (int i = 0; i < servers.size(); i++) {
        if (servers[i].schema.toString()==args.schema.toString() && servers[i].role.inspect().contains(args.role.toString())==true && servers[i].region.toString()==args.region.toString()) {
            if ( server_list == '' ) { server_list = "${servers[i].name}" }
            else { server_list = "${server_list} ${servers[i].name}" }
        }
    }
    return server_list
}

def getAllServers(Map args){
//input schema:<schema>, region:<QA|UAT...>
//output all servers list
//example server = getAllServers(schema: 1, region: QA)
//premise is set servers variable
    server_list = ''
    for (int i = 0; i < servers.size(); i++) {
        if (servers[i].schema.toString()==args.schema.toString() && servers[i].region.toString()==args.region.toString()) {
            if ( server_list == '' ) { server_list = "${servers[i].name}" }
            else { server_list = "${server_list} ${servers[i].name}" }
        }
    }
    return server_list
}

def getJenkinsSlave(Map args){
//input name:<server name>
//output Jenkins Slave server list for <server name>
//example server = getJenkinsSlave(name: 've-pon-onlmtq01.prime.gpe')
//premise is set servers variable
    server_list = ''
    for (int i = 0; i < servers.size(); i++) {
        if (servers[i].name.toString()==args.name.toString()) {
            if ( server_list == '' ) { server_list = "${servers[i].jenkins_slave}" }
            else { server_list = "${server_list} ${servers[i].jenkins_slave}" }
        }
    }
    return server_list
}
 

def getServerUser(Map args){
//input name:<server name>
//output server user server list for <server name>
//example server = getServerUser(name: 've-pon-onlmtq01.prime.gpe')
//premise is set servers variable
    user_list = ''
    for (int i = 0; i < servers.size(); i++) {
        if (servers[i].name.toString()==args.name.toString()) {
            if ( user_list == '' ) { user_list = "${servers[i].user}" }
            else { user_list = "${user_list} ${servers[i].user}" }
        }
    }
    if (args.role.toString() && user_list.inspect().contains(args.role.toString())) {
        user_list = args.role.toString()
    }

    return user_list
}

def getNode(Map args){
//input (name: "${server}", role: app)
//output: node per name(ip address) for role: online
    node_list = ''
    for (int i = 0; i < servers.size(); i++) {
        if (servers[i].name.toString()==args.name.toString()) {
            if ( node_list == '' ) { node_list = "${servers[i].node}" }
            else { node_list = "${node_list} ${servers[i].node}" }
        }
    }
    if (args.role.toString() && node_list.inspect().contains(args.role.toString())) {
        node_list = args.role.toString()
    }
    return node_list
}
    def downloadFile(String remoteUrl, String localFilename) {

        //input remoteUrl:<nexus remote url>
        //input localFilename:<local filename>
        //! jenkins httpRequest plugin required !
        httpRequest(
            url: "${remoteUrl}", outputFile: "${localFilename}", responseHandle: 'NONE'
        )

        return 0;
    }
    // PRIMEWEB artifact preparation steps
    def prepare_primeweb(String server, String server_user) {
    def artifactpath = env.ARTIFACTS.split(',').findAll{ it.contains('app-primeweb') }[0].toString()
    def artifactname = WINDOWS_ARTIFACTS_PATH + '\\' + artifactpath.substring(artifactpath.lastIndexOf('/')+1)

    def win_art_path = WINDOWS_ARTIFACTS_PATH.replaceAll('\\\\','/')
    def win_cfg_path = WINDOWS_CONFIG_PATH.replaceAll('\\\\','/')
    def win_ins_path = INSTALL_SOURCE_PATH_WINDOWS.replaceAll('\\\\','/')

    sh """
        ssh ${server_user}@${server} "rm -rf /home/${server_user}/jenkins; mkdir -p /home/${server_user}/jenkins"
        ssh ${server_user}@${server} "mkdir -p ${win_ins_path}; mkdir -p ${win_cfg_path}; mkdir -p ${win_art_path};"
        ssh ${server_user}@${server} "rm -rf ${win_ins_path}/*; rm -rf ${win_art_path}/*"

        # Copy artifacts

        scp ./src/win/win_ops.bat ${server_user}@${server}:/home/${server_user}/jenkins/win_ops.bat
        scp -l 50000 ./install/app-primeweb-*.7z ${server_user}@${server}:${win_art_path}
        scp ./config/Prime4Installer_template.config ${server_user}@${server}:${win_cfg_path}

        ssh ${server_user}@${server} "chmod u+x /home/${server_user}/jenkins/win_ops.bat"

        ssh ${server_user}@${server} "powershell \\\"./jenkins\\\\win_ops.bat ${artifactname} ${INSTALL_SOURCE_PATH_WINDOWS}\\\""

    """

    return 0;
    }
    // OTC, KM, HSMI artifacts preparation steps
    def tc_prep(String server, String server_user, String operation, String path) {
    def artifactpath = env.ARTIFACTS.split(',').findAll{ it.contains("${operation}") }[0].toString()
    def artifactname = path + '\\' + artifactpath.substring(artifactpath.lastIndexOf('/')+1)    
    def hsmi_art_path = path.replaceAll('\\\\','/')
        sh """
        ssh ${server_user}@${server} "rm -rf /home/${server_user}/jenkins; mkdir -p /home/${server_user}/jenkins"
        ssh ${server_user}@${server} "rm -rf ${hsmi_art_path}/*; mkdir -p ${hsmi_art_path}"
        # Copy artifacts
        scp ./src/win/win_ops.bat ${server_user}@${server}:/home/${server_user}/jenkins/win_ops.bat
        scp ./install/${operation}*.zip ${server_user}@${server}:${hsmi_art_path}
        ssh ${server_user}@${server} "chmod u+x /home/${server_user}/jenkins/win_ops.bat"
        """
    return artifactname;
    }

    def convertDate(String dt, String format) {
        def parsed = new SimpleDateFormat(format).parse(dt)

        return parsed
    }

    // this function doesn't used in libpatch
    def constructURLfromFilename(String baseUrl, String filename) {
        //input filename:<filename>
        //output URL for local nexus
        //def baseUrl = "http://10.255.250.50:8081/repository/prime-artifacts"

        def ext = filename.substring(filename.indexOf(".")+1);
        echo ext

        // get filename without ext, timestamp & date
        def fwoext = filename.substring(0, filename.indexOf("."));
        def ts = fwoext.substring( fwoext.lastIndexOf("-")+1);
        def dt = fwoext.substring( fwoext.lastIndexOf("-")-8, fwoext.lastIndexOf("-"))
        def cfgRgnSfx = fwoext - ts - dt - "--"

        // get region & type depending on localization suffix
        if (filename.indexOf("-dbr-") != -1) {
            def suffix = "dbr"
            cfgRgnSfx = cfgRgnSfx - "-dbr"
        }

        def region = cfgRgnSfx.substring( cfgRgnSfx.lastIndexOf("-")+1 )
        def cfgName = cfgRgnSfx - "-${region}"

        /*if (region == "integration") {
            region = "qa"
        }*/

        echo "1"
        // convert date
        def parsed = convertDate(dt, "yyyyMMdd")
        def (yy,mm,dd) = parsed.format("yyyy-MM-dd").tokenize('-')
        // return constructed URL
        return "${baseUrl}/${region}/${yy}/${mm}/${dd}/${cfgName}/${filename}";

    }

return this
